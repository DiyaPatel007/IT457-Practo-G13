package edu.dau.practolite.common.lambda;

import com.amazonaws.serverless.exceptions.ContainerInitializationException;
import com.amazonaws.serverless.proxy.model.AwsProxyResponse;
import com.amazonaws.serverless.proxy.model.HttpApiV2ProxyRequest;
import com.amazonaws.serverless.proxy.spring.SpringBootLambdaContainerHandler;

/**
 * Creates the bridge between Lambda and Spring Boot.
 *
 * <p>{@link SpringBootLambdaContainerHandler} (from aws-serverless-java-container) starts the Spring
 * application context once, then for each Lambda event it converts the API Gateway HTTP API (payload
 * format 2.0) JSON into an {@code HttpServletRequest}, runs it through the normal Spring MVC and
 * Spring Security filter chain, and converts the {@code HttpServletResponse} back into the JSON Lambda
 * returns to API Gateway. No Tomcat runs; the library provides the servlet container.
 */
public final class SpringLambdaHandlers {

    private SpringLambdaHandlers() {
    }

    public static SpringBootLambdaContainerHandler<HttpApiV2ProxyRequest, AwsProxyResponse> httpApi(
            Class<?> springBootApplication) {
        try {
            SpringBootLambdaContainerHandler<HttpApiV2ProxyRequest, AwsProxyResponse> handler =
                    SpringBootLambdaContainerHandler.getHttpApiV2ProxyHandler(springBootApplication);
            SnapStartPriming.registerIfEnabled(handler);
            return handler;
        } catch (ContainerInitializationException e) {
            throw new IllegalStateException("Could not initialise the Spring Boot application", e);
        }
    }
}
