package example.micronaut;

import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.http.filter.ServerFilterPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micronaut.http.util.HttpHeadersUtil;
import static io.micronaut.http.annotation.Filter.MATCH_ALL_PATTERN;

@ServerFilter(MATCH_ALL_PATTERN)
class LoggingHeadersFilter implements Ordered {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingHeadersFilter.class);

    @RequestFilter
    void filterRequest(HttpRequest<?> request) {
        LOG.trace("Request {} {}", request.getMethod(), request.getPath());
        HttpHeadersUtil.trace(LOG, request.getHeaders());
    }

    @Override
    public int getOrder() {
        return ServerFilterPhase.FIRST.order();
    }
}