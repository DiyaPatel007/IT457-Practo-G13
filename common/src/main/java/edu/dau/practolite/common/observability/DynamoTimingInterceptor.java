package edu.dau.practolite.common.observability;

import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;

/**
 * AWS SDK hook that measures every DynamoDB call, including SDK retries, and adds the time
 * to {@link RequestTiming}. This gives the "dynamo_ms" field used to split database time
 * from Java code time in Experiment 2.
 */
public class DynamoTimingInterceptor implements ExecutionInterceptor {

    private static final ExecutionAttribute<Long> START = new ExecutionAttribute<>("practoStartNanos");

    @Override
    public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes attributes) {
        attributes.putAttribute(START, System.nanoTime());
    }

    @Override
    public void afterExecution(Context.AfterExecution context, ExecutionAttributes attributes) {
        record(attributes);
    }

    @Override
    public void onExecutionFailure(Context.FailedExecution context, ExecutionAttributes attributes) {
        record(attributes);
    }

    private static void record(ExecutionAttributes attributes) {
        Long start = attributes.getAttribute(START);
        if (start != null) {
            RequestTiming.addDynamoCall(System.nanoTime() - start);
        }
    }
}
