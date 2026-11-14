package edu.dau.practolite.users.config;

import edu.dau.practolite.common.config.AppProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

@Configuration(proxyBeanMethods = false)
public class CognitoConfig {

    @ConfigurationProperties(prefix = "cognito")
    public record CognitoProperties(String userPoolId) {
    }

    @Bean(destroyMethod = "close")
    public CognitoIdentityProviderClient cognitoClient(AppProperties props, AwsCredentialsProvider credentials) {
        return CognitoIdentityProviderClient.builder()
                .region(Region.of(props.region()))
                .credentialsProvider(credentials)
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }
}
