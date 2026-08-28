package com.smarthome.bff.proxy;

import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Drives the hand-rolled streaming proxy against a throwaway local HTTP server. */
class OrchestratorProxyControllerTest {

    private HttpServer upstream;
    private int port;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> seenAuth = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();

    @BeforeEach
    void startUpstream() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/agui/run", exchange -> {
            seenAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] req = exchange.getRequestBody().readAllBytes();
            seenBody.set(new String(req));
            byte[] payload = ("data: " + new String(req) + "\n\n").getBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(status.get(), payload.length);
            try (var os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        upstream.start();
        port = upstream.getAddress().getPort();
    }

    @AfterEach
    void stopUpstream() {
        upstream.stop(0);
    }

    private HttpServletRequest request() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getQueryString()).thenReturn(null);
        when(req.getHeaderNames())
                .thenReturn(Collections.enumeration(List.of("Authorization", "Host", "X-Custom")));
        when(req.getHeaders("Authorization"))
                .thenReturn(Collections.enumeration(List.of("Bearer t0ken")));
        when(req.getHeaders("X-Custom")).thenReturn(Collections.enumeration(List.of("v1")));
        when(req.getContentType()).thenReturn("application/json");
        return req;
    }

    private String drain(ResponseEntity<StreamingResponseBody> res) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        res.getBody().writeTo(out);
        return out.toString();
    }

    @Test
    void forwardsTheRequestAndStreamsTheResponseBackUnbuffered() throws Exception {
        var controller = new OrchestratorProxyController("http://localhost:" + port);

        ResponseEntity<StreamingResponseBody> res = controller.aguiRun(request(), "hello".getBytes());

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getFirst("Content-Type")).isEqualTo("text/event-stream");
        assertThat(res.getHeaders().getFirst("Content-Length")).isNull(); // hop-by-hop, dropped
        assertThat(drain(res)).isEqualTo("data: hello\n\n");

        assertThat(seenAuth.get()).isEqualTo("Bearer t0ken"); // JWT forwarded to the orchestrator
        assertThat(seenBody.get()).isEqualTo("hello");
    }

    @Test
    void propagatesANon2xxUpstreamStatus() throws Exception {
        status.set(502);
        var controller = new OrchestratorProxyController("http://localhost:" + port);

        ResponseEntity<StreamingResponseBody> res = controller.aguiRun(request(), "x".getBytes());

        assertThat(res.getStatusCode().value()).isEqualTo(502);
        assertThat(drain(res)).isEqualTo("data: x\n\n");
    }

    @Test
    void aTrailingSlashOnTheBaseUrlIsTrimmed() throws Exception {
        var controller = new OrchestratorProxyController("http://localhost:" + port + "/");

        ResponseEntity<StreamingResponseBody> res = controller.aguiRun(request(), "".getBytes());

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(drain(res)).isEqualTo("data: \n\n");
    }

    @Test
    void aNullBodyIsForwardedAsEmpty() throws Exception {
        var controller = new OrchestratorProxyController("http://localhost:" + port);

        ResponseEntity<StreamingResponseBody> res = controller.aguiRun(request(), null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(seenBody.get()).isEmpty();
    }
}
