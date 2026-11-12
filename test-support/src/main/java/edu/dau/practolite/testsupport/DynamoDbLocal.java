package edu.dau.practolite.testsupport;

import com.amazonaws.services.dynamodbv2.local.main.ServerRunner;
import com.amazonaws.services.dynamodbv2.local.server.DynamoDBProxyServer;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Starts AWS's DynamoDB Local in-memory, inside the test JVM, once per test run.
 * It speaks the real DynamoDB HTTP API (including transactions and condition expressions),
 * so repository and race tests exercise the same requests the Lambda functions send in AWS.
 *
 * <p>DynamoDB Local needs the sqlite4java native library; Maven copies it to target/native-libs
 * and surefire passes its path in the sqlite4java.library.path system property.
 */
public final class DynamoDbLocal {

    private static DynamoDBProxyServer server;
    private static String endpoint;

    private DynamoDbLocal() {
    }

    public static synchronized String endpoint() {
        if (server == null) {
            try {
                int port = freePort();
                server = ServerRunner.createServerFromCommandLineArgs(
                        new String[] {"-inMemory", "-sharedDb", "-port", Integer.toString(port)});
                server.start();
                endpoint = "http://localhost:" + port;
                Runtime.getRuntime().addShutdownHook(new Thread(DynamoDbLocal::stopQuietly));
            } catch (Exception e) {
                throw new IllegalStateException("Could not start DynamoDB Local", e);
            }
        }
        return endpoint;
    }

    public static DynamoDbClient client() {
        return DynamoDbClient.builder()
                .endpointOverride(URI.create(endpoint()))
                .region(Region.AP_SOUTH_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    private static void stopQuietly() {
        try {
            server.stop();
        } catch (Exception ignored) {
            // JVM is exiting
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
