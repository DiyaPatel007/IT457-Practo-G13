package edu.dau.practolite.testsupport;

import com.amazonaws.services.lambda.runtime.ClientContext;
import com.amazonaws.services.lambda.runtime.CognitoIdentity;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Calls a Lambda handler exactly as the Lambda runtime would: with the JSON event API Gateway HTTP API
 * (payload format 2.0) sends, including the JWT claims its authorizer adds. Used by the *LambdaHandlerTest
 * classes to test the real Spring Boot + aws-serverless-java-container path without deploying.
 */
public final class LambdaInvoker {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LambdaInvoker() {
    }

    public record Response(int status, Map<String, String> headers, JsonNode body) {
    }

    /**
     * @param claims JWT claims as API Gateway passes them (all strings, groups like "[Patient]"),
     *               or null for a public route without an authorizer
     */
    public static Response invoke(RequestStreamHandler handler, String method, String path, String query,
                                  String body, Map<String, String> claims) throws Exception {
        return invoke(handler, method, path, query, body, claims, Map.of());
    }

    public static Response invoke(RequestStreamHandler handler, String method, String path, String query,
                                  String body, Map<String, String> claims, Map<String, String> extraHeaders)
            throws Exception {
        ObjectNode event = MAPPER.createObjectNode();
        event.put("version", "2.0");
        event.put("routeKey", method + " " + path);
        event.put("rawPath", path);
        event.put("rawQueryString", query == null ? "" : query);
        ObjectNode headers = event.putObject("headers");
        headers.put("host", "test.execute-api.ap-south-1.amazonaws.com");
        headers.put("accept", "application/json");
        extraHeaders.forEach((k, v) -> headers.put(k.toLowerCase(java.util.Locale.ROOT), v));
        if (body != null) {
            headers.put("content-type", "application/json");
            event.put("body", body);
        }
        event.put("isBase64Encoded", false);
        ObjectNode ctx = event.putObject("requestContext");
        ctx.put("requestId", UUID.randomUUID().toString());
        ctx.put("routeKey", method + " " + path);
        ctx.put("stage", "$default");
        ctx.put("timeEpoch", System.currentTimeMillis());
        ObjectNode http = ctx.putObject("http");
        http.put("method", method);
        http.put("path", path);
        http.put("protocol", "HTTP/1.1");
        http.put("sourceIp", "127.0.0.1");
        http.put("userAgent", "junit");
        if (claims != null) {
            ObjectNode jwt = ctx.putObject("authorizer").putObject("jwt");
            ObjectNode c = jwt.putObject("claims");
            claims.forEach(c::put);
            jwt.putArray("scopes");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        handler.handleRequest(new ByteArrayInputStream(MAPPER.writeValueAsBytes(event)), out, new TestContext());
        JsonNode response = MAPPER.readTree(out.toByteArray());
        String responseBody = response.path("body").asText("");
        JsonNode parsed = responseBody.isEmpty() ? MAPPER.nullNode() : MAPPER.readTree(responseBody);
        Map<String, String> responseHeaders = MAPPER.convertValue(response.path("headers"),
                MAPPER.getTypeFactory().constructMapType(Map.class, String.class, String.class));
        return new Response(response.path("statusCode").asInt(), responseHeaders, parsed);
    }

    public static Map<String, String> claims(String sub, String groups) {
        return Map.of("sub", sub, "cognito:groups", groups, "token_use", "access");
    }

    static final class TestContext implements Context {
        private final String id = UUID.randomUUID().toString();

        @Override
        public String getAwsRequestId() {
            return id;
        }

        @Override
        public String getLogGroupName() {
            return "test";
        }

        @Override
        public String getLogStreamName() {
            return "test";
        }

        @Override
        public String getFunctionName() {
            return "test";
        }

        @Override
        public String getFunctionVersion() {
            return "1";
        }

        @Override
        public String getInvokedFunctionArn() {
            return "arn:aws:lambda:ap-south-1:000000000000:function:test";
        }

        @Override
        public CognitoIdentity getIdentity() {
            return null;
        }

        @Override
        public ClientContext getClientContext() {
            return null;
        }

        @Override
        public int getRemainingTimeInMillis() {
            return 30_000;
        }

        @Override
        public int getMemoryLimitInMB() {
            return 1024;
        }

        @Override
        public LambdaLogger getLogger() {
            return new LambdaLogger() {
                @Override
                public void log(String message) {
                    System.out.println(message);
                }

                @Override
                public void log(byte[] message) {
                    System.out.println(new String(message, StandardCharsets.UTF_8));
                }
            };
        }
    }
}
