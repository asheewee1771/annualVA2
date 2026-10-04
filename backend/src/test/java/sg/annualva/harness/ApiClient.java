package sg.annualva.harness;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A browser-like HTTP client for Seam 1: keeps cookies, fetches the CSRF token when asked, and
 * parses JSON. Tests assert only on what it returns.
 */
public final class ApiClient {

    public record Response(int status, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            var values = headers.get(name.toLowerCase());
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    private final String baseUrl;
    private String csrfHeader;
    private String csrfToken;

    public ApiClient(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    public Response get(String path) {
        return get(path, Map.of());
    }

    public Response get(String path, Map<String, String> headers) {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET();
        headers.forEach(request::header);
        return send(request.build());
    }

    public Response postJson(String path, String json) {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (csrfToken != null) {
            request.header(csrfHeader, csrfToken);
        }
        return send(request.build());
    }

    /** Bootstraps the synchronizer token the way the SPA does. */
    public ApiClient withCsrf() {
        JsonNode csrf = json(get("/api/csrf"));
        this.csrfHeader = csrf.get("headerName").asString();
        this.csrfToken = csrf.get("token").asString();
        return this;
    }

    public static JsonNode json(Response response) {
        return JSON.readTree(response.body());
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), lowerCased(response.headers().map()), response.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, List<String>> lowerCased(Map<String, List<String>> headers) {
        var result = new HashMap<String, List<String>>();
        headers.forEach((name, values) -> result.put(name.toLowerCase(), values));
        return result;
    }
}
