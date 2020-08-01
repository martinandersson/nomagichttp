package alpha.nomagichttp.largetest;

import alpha.nomagichttp.Server;
import alpha.nomagichttp.handler.Handler;
import alpha.nomagichttp.handler.Handlers;
import alpha.nomagichttp.message.Responses;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.NetworkChannel;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static alpha.nomagichttp.route.Routes.route;
import static java.net.http.HttpRequest.BodyPublishers;
import static java.net.http.HttpResponse.BodyHandlers;
import static org.assertj.core.api.Assertions.assertThat;

class OneHundredRequestsFromSameClient
{
    // N = repetitions, LEN = request body length (char count)
    private static final int N       = 100,
                             LEN_MIN =   0,
                             LEN_MAX =  10;
    
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
                .uri(URI.create("http://localhost:" + port));
    }
    
    @ParameterizedTest
    @MethodSource("messages")
    void test(String msg) throws IOException, InterruptedException {
        HttpResponse<String> res = post(msg);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).isEqualTo(msg);
    }
    
    private static Stream<String> messages() {
        Supplier<String> s = () -> {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            int[] chars = r.ints(r.nextInt(LEN_MIN, LEN_MAX + 1), '!', '~').toArray();
            return new String(chars, 0, chars.length);
        };
        
        return Stream.generate(s).limit(N);
    }
    
    private static HttpResponse<String> post(String body) throws IOException, InterruptedException {
        HttpRequest req = TEMPLATE.POST(BodyPublishers.ofString(body)).build();
        return CLIENT.send(req, BodyHandlers.ofString());
    }
}