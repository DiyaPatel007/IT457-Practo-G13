package edu.dau.practolite.common.observability;

import com.amazonaws.serverless.proxy.RequestReader;
import com.amazonaws.serverless.proxy.model.HttpApiV2ProxyRequestContext;
import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.dau.practolite.common.config.AppProperties;
import edu.dau.practolite.common.config.LambdaEnvironment;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Wraps every request (including ones rejected by Spring Security) and writes one JSON line:
 *
 * <pre>{"type":"request_metrics","route":"POST /appointments","status":201,"total_ms":41.2,
 *  "dynamo_ms":18.7,"dynamo_calls":3,"cold":false,...}</pre>
 *
 * <p>The Lambda REPORT line gives platform time; this line splits it into database time and code time.
 */
public class RequestMetricsFilter extends OncePerRequestFilter {

    private static final Logger METRICS = LoggerFactory.getLogger("REQUEST_METRICS");
    private static final Logger LOG = LoggerFactory.getLogger(RequestMetricsFilter.class);

    private final ObjectMapper mapper;
    private final boolean diagnosticHeaders;

    public RequestMetricsFilter(ObjectMapper mapper, AppProperties props) {
        this.mapper = mapper;
        this.diagnosticHeaders = props.diagnostics() != null && props.diagnostics().headers();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        RequestTiming.reset();
        boolean priming = ColdStart.isPriming();
        boolean cold = !priming && ColdStart.consume();
        String lambdaRequestId = lambdaRequestId(request);

        if (diagnosticHeaders) {
            // Set before the chain runs, because the response may be committed afterwards.
            response.setHeader("X-Cold-Start", Boolean.toString(cold));
            if (lambdaRequestId != null) {
                response.setHeader("X-Lambda-Request-Id", lambdaRequestId);
            }
            if (LambdaEnvironment.functionVersion() != null) {
                response.setHeader("X-Function-Version", LambdaEnvironment.functionVersion());
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (!priming) {
                write(request, response, start, cold, lambdaRequestId);
            }
        }
    }

    private void write(HttpServletRequest request, HttpServletResponse response, long start, boolean cold,
                       String lambdaRequestId) {
        try {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("type", "request_metrics");
            line.put("route", route(request));
            line.put("status", response.getStatus());
            line.put("total_ms", round((System.nanoTime() - start) / 1_000_000.0));
            line.put("dynamo_ms", round(RequestTiming.dynamoMillis()));
            line.put("dynamo_calls", RequestTiming.dynamoCalls());
            line.put("cold", cold);
            line.put("init_type", LambdaEnvironment.initializationType());
            line.put("fn", LambdaEnvironment.functionName());
            line.put("fn_version", LambdaEnvironment.functionVersion());
            line.put("memory_mb", LambdaEnvironment.memoryMb());
            line.put("lambda_request_id", lambdaRequestId);
            line.put("api_request_id", apiRequestId(request));
            METRICS.info(mapper.writeValueAsString(line));
        } catch (Exception e) {
            LOG.warn("Could not write request metrics: {}", e.toString());
        }
    }

    /** Uses the matched Spring pattern ("/doctors/{doctorId}") so IDs do not end up in the route name. */
    private static String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String path = pattern != null ? pattern.toString() : "UNMATCHED";
        return request.getMethod() + " " + path;
    }

    private static String lambdaRequestId(HttpServletRequest request) {
        Object ctx = request.getAttribute(RequestReader.LAMBDA_CONTEXT_PROPERTY);
        return ctx instanceof Context c ? c.getAwsRequestId() : null;
    }

    private static String apiRequestId(HttpServletRequest request) {
        Object ctx = request.getAttribute(RequestReader.HTTP_API_CONTEXT_PROPERTY);
        return ctx instanceof HttpApiV2ProxyRequestContext c ? c.getRequestId() : null;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
