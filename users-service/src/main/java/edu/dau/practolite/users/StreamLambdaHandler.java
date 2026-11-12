package edu.dau.practolite.users;

import com.amazonaws.serverless.proxy.model.AwsProxyResponse;
import com.amazonaws.serverless.proxy.model.HttpApiV2ProxyRequest;
import com.amazonaws.serverless.proxy.spring.SpringBootLambdaContainerHandler;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import edu.dau.practolite.common.lambda.SpringLambdaHandlers;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Lambda entry point (Handler in template.yaml: edu.dau.practolite.users.StreamLambdaHandler::handleRequest).
 *
 * <p>The static block runs once per execution environment, during INIT (or once before the SnapStart
 * snapshot). It starts the whole Spring Boot context. Each invocation then only routes one event
 * through Spring MVC, so warm requests are fast.
 */
public class StreamLambdaHandler implements RequestStreamHandler {

    private static final SpringBootLambdaContainerHandler<HttpApiV2ProxyRequest, AwsProxyResponse> HANDLER =
            SpringLambdaHandlers.httpApi(UsersApplication.class);

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        HANDLER.proxyStream(input, output, context);
    }
}
