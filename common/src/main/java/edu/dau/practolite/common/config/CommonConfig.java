package edu.dau.practolite.common.config;

import edu.dau.practolite.common.observability.DynamoTimingInterceptor;
import java.net.URI;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

/**
 * Beans shared by all services. Spring creates them once per Lambda execution environment
 * (during INIT, or before the SnapStart snapshot), so warm requests reuse them.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppProperties.class)
public class CommonConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The default chain, deliberately. Without SnapStart, Lambda puts the role credentials in environment
     * variables; with SnapStart it serves them from the container credentials endpoint instead (so they are
     * fresh after a restore). The default chain handles both, an environment-variable-only provider would not.
     */
    @Bean
    public AwsCredentialsProvider awsCredentialsProvider() {
        return DefaultCredentialsProvider.builder().build();
    }

    @Bean(destroyMethod = "close")
    public DynamoDbClient dynamoDbClient(AppProperties props, AwsCredentialsProvider credentials) {
        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .region(Region.of(props.region()))
                .credentialsProvider(credentials)
                .httpClient(UrlConnectionHttpClient.create())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .addExecutionInterceptor(new DynamoTimingInterceptor())
                        .build());
        if (props.dynamodbEndpoint() != null && !props.dynamodbEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(props.dynamodbEndpoint()));
        }
        return builder.build();
    }
}
