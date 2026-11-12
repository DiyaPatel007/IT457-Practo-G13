package edu.dau.practolite.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings shared by all three services. Values come from practo-common.yml, which reads
 * environment variables set by the SAM template (or by you when running locally).
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String region,
        String dynamodbEndpoint,
        Tables tables,
        Security security,
        Diagnostics diagnostics) {

    public record Tables(String users, String doctors, String slots, String appointments) {
    }

    /**
     * @param devHeaders when true, identity is read from X-Dev-User-Id / X-Dev-Groups headers.
     *                   For local runs only; the app refuses to start with it enabled inside Lambda.
     */
    public record Security(boolean devHeaders) {
    }

    /**
     * @param headers when true, responses carry X-Cold-Start, X-Lambda-Request-Id and
     *                X-Function-Version headers, which the cold-start probe uses.
     */
    public record Diagnostics(boolean headers) {
    }
}
