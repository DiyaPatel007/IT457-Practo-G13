package edu.dau.practolite.testsupport;

/**
 * Points the Spring Boot services at DynamoDB Local by setting system properties, which Spring gives
 * priority over application.yml. Call this BEFORE the first use of a StreamLambdaHandler class,
 * because its static block starts the Spring context.
 */
public final class LocalEnvironment {

    private LocalEnvironment() {
    }

    public static LocalTables.Names useDynamoDbLocal(String tablePrefix) {
        String endpoint = DynamoDbLocal.endpoint();
        LocalTables.Names names;
        try (var client = DynamoDbLocal.client()) {
            names = LocalTables.createAll(client, tablePrefix);
        }
        System.setProperty("app.dynamodb-endpoint", endpoint);
        System.setProperty("app.tables.users", names.users());
        System.setProperty("app.tables.doctors", names.doctors());
        System.setProperty("app.tables.slots", names.slots());
        System.setProperty("app.tables.appointments", names.appointments());
        System.setProperty("app.diagnostics.headers", "true");
        return names;
    }
}
