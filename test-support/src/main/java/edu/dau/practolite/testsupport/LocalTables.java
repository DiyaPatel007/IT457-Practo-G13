package edu.dau.practolite.testsupport;

import java.util.List;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * Creates the four tables exactly as infra/template.yaml defines them (same keys and indexes).
 * If you change a key schema, change it in all three places: template.yaml, here, scripts/local_tables.py.
 */
public final class LocalTables {

    private LocalTables() {
    }

    public record Names(String users, String doctors, String slots, String appointments) {
        public static Names withPrefix(String prefix) {
            return new Names(prefix + "-Users", prefix + "-Doctors", prefix + "-Slots", prefix + "-Appointments");
        }
    }

    public static Names createAll(DynamoDbClient dynamo, String prefix) {
        Names n = Names.withPrefix(prefix);
        create(dynamo, n.users(), List.of(attr("userId")), key("userId", null), List.of());
        create(dynamo, n.doctors(),
                List.of(attr("doctorId"), attr("searchCity"), attr("specKey")),
                key("doctorId", null),
                List.of(gsi("search-index", "searchCity", "specKey")));
        create(dynamo, n.slots(), List.of(attr("doctorId"), attr("slotStart")), key("doctorId", "slotStart"),
                List.of());
        create(dynamo, n.appointments(),
                List.of(attr("appointmentId"), attr("patientId"), attr("doctorId"), attr("slotStart")),
                key("appointmentId", null),
                List.of(gsi("patient-index", "patientId", "slotStart"), gsi("doctor-index", "doctorId", "slotStart")));
        return n;
    }

    private static void create(DynamoDbClient dynamo, String name, List<AttributeDefinition> attrs,
                               List<KeySchemaElement> key, List<GlobalSecondaryIndex> indexes) {
        try {
            dynamo.createTable(b -> {
                b.tableName(name).attributeDefinitions(attrs).keySchema(key).billingMode(BillingMode.PAY_PER_REQUEST);
                if (!indexes.isEmpty()) {
                    b.globalSecondaryIndexes(indexes);
                }
            });
        } catch (ResourceInUseException alreadyThere) {
            // Shared in-memory database: another test class created it first.
        }
    }

    private static AttributeDefinition attr(String name) {
        return AttributeDefinition.builder().attributeName(name).attributeType(ScalarAttributeType.S).build();
    }

    private static List<KeySchemaElement> key(String hash, String range) {
        KeySchemaElement h = KeySchemaElement.builder().attributeName(hash).keyType(KeyType.HASH).build();
        if (range == null) {
            return List.of(h);
        }
        return List.of(h, KeySchemaElement.builder().attributeName(range).keyType(KeyType.RANGE).build());
    }

    private static GlobalSecondaryIndex gsi(String name, String hash, String range) {
        return GlobalSecondaryIndex.builder()
                .indexName(name)
                .keySchema(key(hash, range))
                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                .build();
    }
}
