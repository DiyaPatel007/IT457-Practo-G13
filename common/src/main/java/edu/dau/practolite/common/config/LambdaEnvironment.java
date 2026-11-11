package edu.dau.practolite.common.config;

/** Reads the environment variables that the Lambda runtime sets in every execution environment. */
public final class LambdaEnvironment {

    private LambdaEnvironment() {
    }

    public static boolean isLambda() {
        return System.getenv("AWS_LAMBDA_FUNCTION_NAME") != null;
    }

    public static String functionName() {
        return System.getenv("AWS_LAMBDA_FUNCTION_NAME");
    }

    public static String functionVersion() {
        return System.getenv("AWS_LAMBDA_FUNCTION_VERSION");
    }

    public static String memoryMb() {
        return System.getenv("AWS_LAMBDA_FUNCTION_MEMORY_SIZE");
    }

    /** "on-demand", "snap-start" or "provisioned-concurrency". */
    public static String initializationType() {
        return System.getenv("AWS_LAMBDA_INITIALIZATION_TYPE");
    }
}
