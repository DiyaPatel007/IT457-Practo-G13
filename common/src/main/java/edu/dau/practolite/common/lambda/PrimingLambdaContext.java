package edu.dau.practolite.common.lambda;

import com.amazonaws.services.lambda.runtime.ClientContext;
import com.amazonaws.services.lambda.runtime.CognitoIdentity;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;

/** Minimal Lambda Context used only for the fake priming request. */
final class PrimingLambdaContext implements Context {

    @Override
    public String getAwsRequestId() {
        return "priming";
    }

    @Override
    public String getLogGroupName() {
        return "priming";
    }

    @Override
    public String getLogStreamName() {
        return "priming";
    }

    @Override
    public String getFunctionName() {
        return System.getenv().getOrDefault("AWS_LAMBDA_FUNCTION_NAME", "priming");
    }

    @Override
    public String getFunctionVersion() {
        return System.getenv().getOrDefault("AWS_LAMBDA_FUNCTION_VERSION", "priming");
    }

    @Override
    public String getInvokedFunctionArn() {
        return "priming";
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
        return 60_000;
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
                System.out.println(new String(message, java.nio.charset.StandardCharsets.UTF_8));
            }
        };
    }
}
