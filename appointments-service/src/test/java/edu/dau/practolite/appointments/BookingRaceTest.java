package edu.dau.practolite.appointments;

import static org.assertj.core.api.Assertions.assertThat;

import edu.dau.practolite.appointments.config.BookingProperties;
import edu.dau.practolite.appointments.repo.AppointmentRecord;
import edu.dau.practolite.appointments.repo.AppointmentRepository;
import edu.dau.practolite.appointments.repo.BookingTransactions;
import edu.dau.practolite.appointments.repo.SlotStateRepository;
import edu.dau.practolite.appointments.service.BookingStrategy;
import edu.dau.practolite.appointments.service.NaiveBookingStrategy;
import edu.dau.practolite.appointments.service.TransactionalBookingStrategy;
import edu.dau.practolite.common.config.AppProperties;
import edu.dau.practolite.common.model.AppointmentStatus;
import edu.dau.practolite.common.model.TimeSupport;
import edu.dau.practolite.common.web.ApiException;
import edu.dau.practolite.testsupport.DynamoDbLocal;
import edu.dau.practolite.testsupport.LocalTables;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * The core correctness property, tested against a real DynamoDB engine (DynamoDB Local) with real threads:
 * many patients book the same slot at the same moment.
 *
 * <ul>
 *   <li>Transactional strategy: exactly one booking wins, every other request gets 409, one appointment row.</li>
 *   <li>Naive strategy (read, check, write): several requests can win, i.e. a double booking. This is the
 *       local version of the Experiment 4 contrast.</li>
 * </ul>
 * The same experiment is repeated on AWS with k6 (load-tests/k6/race.js), where it counts as the real result.
 */
class BookingRaceTest {

    private static final int THREADS = 50;

    private static DynamoDbClient db;
    private static LocalTables.Names tables;
    private static AppProperties props;

    @BeforeAll
    static void setUp() {
        db = DynamoDbLocal.client();
        tables = LocalTables.createAll(db, "race");
        props = new AppProperties("ap-south-1", DynamoDbLocal.endpoint(),
                new AppProperties.Tables(tables.users(), tables.doctors(), tables.slots(), tables.appointments()),
                new AppProperties.Security(false), new AppProperties.Diagnostics(false));
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    @Test
    void transactionalBookingAllowsExactlyOneWinner() throws Exception {
        String doctor = "doc-" + UUID.randomUUID();
        String slot = createSlot(doctor);
        BookingStrategy strategy = new TransactionalBookingStrategy(
                new BookingTransactions(db, props), new AppointmentRepository(db, props));

        RaceResult result = race(strategy, doctor, slot, THREADS);

        assertThat(result.successes()).isEqualTo(1);
        assertThat(result.conflicts409()).isEqualTo(THREADS - 1);
        assertThat(result.other()).isZero();
        assertThat(appointmentsFor(doctor, slot)).hasSize(1);
        assertThat(slotStatus(doctor, slot)).isEqualTo("BOOKED");
    }

    @Test
    void naiveBookingCanDoubleBook() throws Exception {
        String doctor = "doc-" + UUID.randomUUID();
        String slot = createSlot(doctor);
        // A 300 ms pause between "check" and "write" makes the race window wide enough to show reliably.
        BookingStrategy naive = new NaiveBookingStrategy(new SlotStateRepository(db, props),
                new AppointmentRepository(db, props), new BookingProperties("naive", 300, true));

        RaceResult result = race(naive, doctor, slot, 10);

        assertThat(result.successes()).as("naive read-then-write lets several patients win").isGreaterThan(1);
        assertThat(appointmentsFor(doctor, slot)).hasSize(result.successes());
    }

    private record RaceResult(int successes, int conflicts409, int other) {
    }

    private RaceResult race(BookingStrategy strategy, String doctor, String slot, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        Map<String, Integer> other = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String patient = "patient-" + i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    strategy.book(appointment(patient, doctor, slot));
                    ok.incrementAndGet();
                } catch (ApiException e) {
                    if (e.status().value() == 409) {
                        conflict.incrementAndGet();
                    } else {
                        other.merge(e.code(), 1, Integer::sum);
                    }
                }
                return null;
            }));
        }
        start.countDown(); // release all threads at once
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        return new RaceResult(ok.get(), conflict.get(), other.values().stream().mapToInt(Integer::intValue).sum());
    }

    private static AppointmentRecord appointment(String patient, String doctor, String slot) {
        String now = TimeSupport.toKey(Instant.now());
        return new AppointmentRecord(UUID.randomUUID().toString(), patient, null, doctor, "Dr Test", slot,
                AppointmentStatus.BOOKED, null, null, now, now);
    }

    private static String createSlot(String doctor) {
        String slot = TimeSupport.toKey(Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS));
        db.putItem(b -> b.tableName(tables.slots()).item(Map.of(
                "doctorId", AttributeValue.fromS(doctor),
                "slotStart", AttributeValue.fromS(slot),
                "status", AttributeValue.fromS("AVAILABLE"))));
        return slot;
    }

    /** Ground truth: scan the table, as scripts/consistency_check.py does on AWS. */
    private static List<Map<String, AttributeValue>> appointmentsFor(String doctor, String slot) {
        return db.scan(b -> b.tableName(tables.appointments())).items().stream()
                .filter(i -> doctor.equals(i.get("doctorId").s()) && slot.equals(i.get("slotStart").s()))
                .toList();
    }

    private static String slotStatus(String doctor, String slot) {
        return db.getItem(b -> b.tableName(tables.slots()).consistentRead(true).key(Map.of(
                "doctorId", AttributeValue.fromS(doctor), "slotStart", AttributeValue.fromS(slot))))
                .item().get("status").s();
    }
}
