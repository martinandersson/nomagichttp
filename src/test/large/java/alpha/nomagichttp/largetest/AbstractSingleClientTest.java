package alpha.nomagichttp.largetest;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.handler.Handlers;
import org.junit.jupiter.api.BeforeAll;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static alpha.nomagichttp.route.Routes.route;
import static java.net.http.HttpRequest.BodyPublishers;
import static java.net.http.HttpResponse.BodyHandlers;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Starts a server on a system-picked port. An {@link HttpClient} connecting to
 * the server can be operated using protected methods in this class.<p>
 * 
 * A NOOP handler will be added to route "/". Test-specific handlers can be
 * added using {@code addHandler()}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
abstract class AbstractSingleClientTest
{
    private static HttpServer SERVER;
    private static String ROOT;
    private static HttpClient CLIENT;
    
    @BeforeAll
    static void setup() throws IOException {
        SERVER = HttpServer.with(route("/", Handlers.noop()));
        SERVER.start();
        
        ROOT = "http://localhost:" + SERVER.getPort();
        CLIENT = HttpClient.newHttpClient();
    }
    
    protected static void addHandler(String route, RequestHandler handler) {
        SERVER.getRouteRegistry().add(route(route, handler));
    }
    
    protected static HttpResponse<Void> postBytes(String route, byte[] bytes) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(ROOT + route))
                .POST(BodyPublishers.ofByteArray(bytes))
                .build();
        
        return CLIENT.send(req, BodyHandlers.discarding());
    }
    
    protected static HttpResponse<String> postAndReceiveText(String route, String utf8Body) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .setHeader("Content-Type", "text/plain; charset=utf-8")
                .uri(URI.create(ROOT + route))
                .POST(BodyPublishers.ofString(utf8Body, UTF_8))
                .build();
        
        return CLIENT.send(req, BodyHandlers.ofString(UTF_8));
    }
}