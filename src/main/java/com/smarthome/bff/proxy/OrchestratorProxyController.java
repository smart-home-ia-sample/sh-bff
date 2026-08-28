package com.smarthome.bff.proxy;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Forwards {@code POST /api/agui/run} to the Python orchestrator (SSE). A
 * hand-rolled streaming proxy rather than a gateway library: the orchestrator
 * runs on uvicorn, whose HTTP parser is strict, so we control exactly which
 * request line and headers go across, and copy the response body through
 * unbuffered so events arrive as they are produced. ({@code /api/home-status}
 * is now served locally from the MQTT read-model — see HomeStatusController.)
 *
 * The JWT filter guards {@code /api/**} ahead of this controller, so an
 * unauthenticated caller is rejected before any upstream call is made.
 */
@RestController
public class OrchestratorProxyController {

    /** Hop-by-hop headers plus the ones the JDK client must own itself.
     * `Authorization` is deliberately kept — the user's JWT is forwarded to the
     * orchestrator and travels the whole agent → MCP → BFF chain. */
    private static final Set<String> DROP_REQUEST_HEADERS = Set.of(
            "host", "connection", "content-length", "transfer-encoding",
            "accept-encoding", "upgrade", "keep-alive");

    private static final Set<String> DROP_RESPONSE_HEADERS = Set.of(
            "connection", "content-length", "transfer-encoding", "keep-alive");

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String base;

    public OrchestratorProxyController(@Value("${bff.orchestrator-url}") String orchestratorUrl) {
        this.base = orchestratorUrl.endsWith("/")
                ? orchestratorUrl.substring(0, orchestratorUrl.length() - 1)
                : orchestratorUrl;
    }

    @PostMapping("/api/agui/run")
    public ResponseEntity<StreamingResponseBody> aguiRun(HttpServletRequest request,
                                                        @RequestBody(required = false) byte[] body) throws Exception {
        return forward("POST", "/agui/run", request, body == null ? new byte[0] : body);
    }

    private ResponseEntity<StreamingResponseBody> forward(String method, String path,
                                                         HttpServletRequest request, byte[] body) throws Exception {
        String query = request.getQueryString();
        URI target = URI.create(base + path + (query != null ? "?" + query : ""));

        HttpRequest.Builder upstream = HttpRequest.newBuilder(target).timeout(Duration.ofMinutes(30));
        for (String name : Collections.list(request.getHeaderNames())) {
            if (DROP_REQUEST_HEADERS.contains(name.toLowerCase())) {
                continue;
            }
            for (String value : Collections.list(request.getHeaders(name))) {
                upstream.header(name, value);
            }
        }
        if ("POST".equals(method)) {
            String contentType = request.getContentType();
            upstream.header("Content-Type", contentType != null ? contentType : "application/json");
            upstream.POST(HttpRequest.BodyPublishers.ofByteArray(body));
        } else {
            upstream.GET();
        }

        HttpResponse<InputStream> response = client.send(upstream.build(), HttpResponse.BodyHandlers.ofInputStream());

        HttpHeaders headers = new HttpHeaders();
        response.headers().map().forEach((name, values) -> {
            if (!DROP_RESPONSE_HEADERS.contains(name.toLowerCase())) {
                headers.addAll(name, List.copyOf(values));
            }
        });

        StreamingResponseBody stream = out -> {
            try (InputStream in = response.body()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    out.flush();
                }
            }
        };

        return new ResponseEntity<>(stream, headers, response.statusCode());
    }
}
