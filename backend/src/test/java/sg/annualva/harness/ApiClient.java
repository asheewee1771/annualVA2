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
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A browser-like HTTP client for Seam 1: keeps cookies, never follows redirects on its own,
 * fetches the CSRF token when asked, and parses JSON. Tests assert only on what it returns.
 */
public final class ApiClient {

    public record Response(int status, Map<String, List<String>> headers, String body) {
        public @Nullable String header(String name) {
            var values = headers.get(name.toLowerCase());
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    /** A response whose body is bytes, such as a file download; the text {@link Response} would corrupt it. */
    public record Download(int status, Map<String, List<String>> headers, byte[] body) {
        public @Nullable String header(String name) {
            var values = headers.get(name.toLowerCase());
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final CookieManager cookies = new CookieManager();
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
    private final String baseUrl;
    private @Nullable String csrfHeader;
    private @Nullable String csrfToken;

    public ApiClient(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    public Response get(String path) {
        return get(path, Map.of());
    }

    public Response get(String path, Map<String, String> headers) {
        return getUrl(baseUrl + path, headers);
    }

    public Download download(String path) {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return new Download(response.statusCode(), lowerCased(response.headers().map()), response.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Follows a redirect the application or the issuer handed out: an absolute URL on any host. */
    public Response getUrl(String absoluteUrl) {
        return getUrl(absoluteUrl, Map.of());
    }

    private Response getUrl(String absoluteUrl, Map<String, String> headers) {
        var request = HttpRequest.newBuilder(URI.create(absoluteUrl)).GET();
        headers.forEach(request::header);
        return send(request.build());
    }

    public Response postJson(String path, String json) {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (csrfToken != null && csrfHeader != null) {
            request.header(csrfHeader, csrfToken);
        }
        return send(request.build());
    }

    /** A bodiless POST, the way the SPA signs out. */
    public Response post(String path) {
        return postJson(path, "");
    }

    /** Bootstraps the synchronizer token the way the SPA does. */
    public ApiClient withCsrf() {
        JsonNode csrf = json(get("/api/csrf"));
        this.csrfHeader = csrf.get("headerName").asString();
        this.csrfToken = csrf.get("token").asString();
        return this;
    }

    /** The value of a cookie the application set, or null. */
    public @Nullable String cookie(String name) {
        return cookies.getCookieStore().get(URI.create(baseUrl)).stream()
                .filter(cookie -> cookie.getName().equals(name))
                .map(java.net.HttpCookie::getValue)
                .findFirst()
                .orElse(null);
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
