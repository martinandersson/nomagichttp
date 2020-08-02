package alpha.nomagichttp.largetest;

import alpha.nomagichttp.Server;
import alpha.nomagichttp.handler.Handler;
import alpha.nomagichttp.handler.Handlers;
import alpha.nomagichttp.message.Responses;
import org.junit.jupiter.api.BeforeAll;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.NetworkChannel;

import static alpha.nomagichttp.route.Routes.route;
import static java.net.http.HttpRequest.BodyPublishers;
import static java.net.http.HttpResponse.BodyHandlers;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Starts a server on system-picked port using an echo handler that responds the
 * text-based request body. An {@link HttpClient} can be operated using
 * protected methods in this class.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
abstract class AbstractSingleClientTest
{
    private static HttpClient CLIENT;
    private static HttpRequest.Builder TEMPLATE;
    
    @BeforeAll
    static void setup() throws IOException {
        Handler echo = Handlers.POST().apply(req ->
                req.body().toText().thenApply(Responses::ok));
        
        NetworkChannel listener = Server.with(route("/", echo)).start();
        
        CLIENT = HttpClient.newHttpClient();
        
        int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();
        TEMPLATE = HttpRequest.newBuilder()
                .setHeader("Content-Type", "text/plain; charset=utf-8")
                .uri(URI.create("http://localhost:" + port));
    }
    
    protected static HttpResponse<String> post(String body) throws IOException, InterruptedException {
        HttpRequest req = TEMPLATE.POST(BodyPublishers.ofString(body, UTF_8)).build();
        return CLIENT.send(req, BodyHandlers.ofString(UTF_8));
    }
    
    protected static String text(int length) {
        byte[] source = lettersRange('!', '~');
        byte[] target = new byte[length];
        
        for (int i = 0, j = 0; i < length; ++i) {
            target[i] = source[j];
            // Re-cycle source
            j = j < source.length - 1 ? j + 1 : 0;
        }
        
        return new String(target, UTF_8);
    }
    
    private static byte[] lettersRange(char beginIncl, char endIncl) {
        final byte[] bytes = new byte[endIncl - beginIncl + 1];
        
        for (int i = beginIncl; i <= endIncl; ++i) {
            bytes[i - beginIncl] = (byte) i;
        }
        
        return bytes;
    }
}