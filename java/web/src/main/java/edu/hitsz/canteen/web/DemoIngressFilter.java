package edu.hitsz.canteen.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Requires a trusted HTTPS proxy and caps public demonstration writes. */
@Component
@Profile("demo")
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
final class DemoIngressFilter extends OncePerRequestFilter {
    private final byte[] proxySecret;
    private final AtomicLong windowStart = new AtomicLong(System.nanoTime());
    private final AtomicInteger writes = new AtomicInteger();

    DemoIngressFilter(@Value("${CANTEEN_DEMO_PROXY_SECRET:}") String secret) {
        if (secret.length() < 32) throw new IllegalStateException("演示代理密钥须由运行时 secret 提供，至少32个字符");
        proxySecret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var supplied = Collections.list(request.getHeaders("X-Canteen-Proxy-Secret"));
        if (supplied.size() != 1 || !MessageDigest.isEqual(proxySecret,
                supplied.get(0).getBytes(StandardCharsets.UTF_8))) {
            reject(response,HttpServletResponse.SC_FORBIDDEN,"仅允许经受信 HTTPS 代理访问");
            return;
        }
        if (!request.isSecure()) {
            var protocols = Collections.list(request.getHeaders("X-Forwarded-Proto"));
            if (protocols.size() != 1 || !"https".equalsIgnoreCase(protocols.get(0))) {
                reject(response,HttpServletResponse.SC_FORBIDDEN,"演示站仅接受 HTTPS 访问");
                return;
            }
        }
        String method = request.getMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method) && !"OPTIONS".equals(method)
                && countWrite() > 120) {
            reject(response,429,"演示站写入过于频繁，请稍后重试");
            return;
        }
        chain.doFilter(request,response);
    }

    private synchronized int countWrite() {
        long now = System.nanoTime();
        if (now - windowStart.get() >= 60_000_000_000L) {
            windowStart.set(now);
            writes.set(0);
        }
        return writes.incrementAndGet();
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
