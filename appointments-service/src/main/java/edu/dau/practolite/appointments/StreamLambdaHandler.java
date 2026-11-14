package edu.dau.practolite.appointments;

import com.amazonaws.serverless.proxy.model.AwsProxyResponse;
import com.amazonaws.serverless.proxy.model.HttpApiV2ProxyRequest;
import com.amazonaws.serverless.proxy.spring.SpringBootLambdaContainerHandler;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import edu.dau.practolite.common.lambda.SpringLambdaHandlers;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Lambda entry point: edu.dau.practolite.appointments.StreamLambdaHandler::handleRequest. */
public class StreamLambdaHandler implements RequestStreamHandler {

    private static final SpringBootLambdaContainerHandler<HttpApiV2ProxyRequest, AwsProxyResponse> HANDLER =
            SpringLambdaHandlers.httpApi(AppointmentsApplication.class);

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        HANDLER.proxyStream(input, output, context);
    }
}
