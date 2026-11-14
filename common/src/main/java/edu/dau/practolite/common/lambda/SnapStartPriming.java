package edu.dau.practolite.common.lambda;

import com.amazonaws.serverless.proxy.model.AwsProxyResponse;
import com.amazonaws.serverless.proxy.model.HttpApiV2ProxyRequest;
import com.amazonaws.serverless.proxy.spring.SpringBootLambdaContainerHandler;
import edu.dau.practolite.common.observability.ColdStart;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.crac.Context;
import org.crac.Core;
import org.crac.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional SnapStart "priming" (Experiment 1, variant V3).
 *
 * <p>With SnapStart, Lambda runs INIT once when a version is published and saves a snapshot of the
 * memory. Code that runs for the first time is still slow after a restore (classes not loaded, JIT
 * not warmed, no HTTPS connection to DynamoDB). Priming sends one fake request through the whole
 * Spring stack <em>before</em> the snapshot (the CRaC {@code beforeCheckpoint} hook), so that work
 * is already done inside the snapshot.
 *
 * <p>Enabled only when the environment variable PRIMING_ENABLED=true. The hook only runs when
 * SnapStart takes a snapshot, so it has no effect when SnapStart is off.
 */
public final class SnapStartPriming implements Resource {

    private static final Logger LOG = LoggerFactory.getLogger(SnapStartPriming.class);

    /** CRaC keeps only weak references to resources, so a strong reference must be held here. */
    private static SnapStartPriming instance;

    static final String PRIME_EVENT = """
            {"version":"2.0","routeKey":"GET /internal/prime","rawPath":"/internal/prime",
             "rawQueryString":"","headers":{"host":"priming.local","accept":"application/json"},
             "requestContext":{"http":{"method":"GET","path":"/internal/prime","protocol":"HTTP/1.1",
               "sourceIp":"127.0.0.1","userAgent":"snapstart-priming"},
               "requestId":"priming","routeKey":"GET /internal/prime","stage":"$default","timeEpoch":0},
             "isBase64Encoded":false}
            """;

    private final SpringBootLambdaContainerHandler<HttpApiV2ProxyRequest, AwsProxyResponse> handler;

    private SnapStartPriming(SpringBootLambdaContainerHandler<HttpApiV2ProxyRequest, AwsProxyResponse> handler) {
        this.handler = handler;
    }

    static synchronized void registerIfEnabled(
            SpringBootLambdaContainerHandler<HttpApiV2ProxyRequest, AwsProxyResponse> handler) {
        if (!"true".equalsIgnoreCase(System.getenv("PRIMING_ENABLED")) || instance != null) {
            return;
        }
        instance = new SnapStartPriming(handler);
        Core.getGlobalContext().register(instance);
        LOG.info("SnapStart priming registered");
    }

    @Override
    public void beforeCheckpoint(Context<? extends Resource> context) {
        ColdStart.beginPriming();
        try {
            for (int i = 0; i < 3; i++) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                handler.proxyStream(new ByteArrayInputStream(PRIME_EVENT.getBytes(StandardCharsets.UTF_8)), out,
                        new PrimingLambdaContext());
            }
            LOG.info("SnapStart priming finished");
        } catch (Exception e) {
            // Priming is an optimisation only; never fail the snapshot because of it.
            LOG.warn("SnapStart priming failed: {}", e.toString());
        } finally {
            ColdStart.resetAfterPriming();
        }
    }

    @Override
    public void afterRestore(Context<? extends Resource> context) {
        // Nothing to do: the SDK re-opens connections when it next needs them.
    }
}
