import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Minimal stand-in for a real server jar in the end-to-end smoke test.
 *
 * Behaves like a server with an installed Helix bridge: reads the wrapper
 * environment and posts heartbeats until it is terminated.
 */
public final class FakeServer {
    private FakeServer() {
    }

    /**
     * Runs the heartbeat loop.
     *
     * @param args unused.
     * @throws Exception on interrupt.
     */
    public static void main(String[] args) throws Exception {
        String serviceId = System.getenv("HELIX_SERVICE_ID");
        String controlUrl = System.getenv("HELIX_CONTROL_URL");
        String token = System.getenv("HELIX_CONTROL_TOKEN");
        System.out.println("[fake-server] started as " + serviceId + " → " + controlUrl);
        HttpClient client = HttpClient.newHttpClient();
        String body = "{\"serviceId\":\"" + serviceId + "\",\"onlinePlayers\":0,\"maxPlayers\":20}";
        while (true) {
            try {
                HttpRequest request = HttpRequest.newBuilder(
                        URI.create(controlUrl + "/api/v1/internal/heartbeat"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
                int status = client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
                System.out.println("[fake-server] heartbeat → " + status);
            } catch (Exception e) {
                System.out.println("[fake-server] heartbeat failed: " + e.getMessage());
            }
            Thread.sleep(1000);
        }
    }
}
