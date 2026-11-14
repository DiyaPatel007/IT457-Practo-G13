package edu.dau.practolite.common.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.dau.practolite.common.config.AppProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

    /** Highest precedence: runs before Spring Security, so 401 and 403 responses are measured too. */
    @Bean
    public FilterRegistrationBean<RequestMetricsFilter> requestMetricsFilter(ObjectMapper mapper, AppProperties props) {
        FilterRegistrationBean<RequestMetricsFilter> reg =
                new FilterRegistrationBean<>(new RequestMetricsFilter(mapper, props));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.addUrlPatterns("/*");
        return reg;
    }
}
