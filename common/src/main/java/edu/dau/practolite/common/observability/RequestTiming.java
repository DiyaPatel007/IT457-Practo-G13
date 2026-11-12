package edu.dau.practolite.common.observability;

/**
 * Per-request total of time spent in DynamoDB calls. A Lambda execution environment handles
 * one request at a time, and Spring MVC runs it on one thread, so a ThreadLocal is enough.
 */
public final class RequestTiming {

    private static final ThreadLocal<long[]> DYNAMO = ThreadLocal.withInitial(() -> new long[2]);

    private RequestTiming() {
    }

    public static void reset() {
        long[] v = DYNAMO.get();
        v[0] = 0;
        v[1] = 0;
    }

    static void addDynamoCall(long nanos) {
        long[] v = DYNAMO.get();
        v[0] += nanos;
        v[1]++;
    }

    public static double dynamoMillis() {
        return DYNAMO.get()[0] / 1_000_000.0;
    }

    public static long dynamoCalls() {
        return DYNAMO.get()[1];
    }
}
