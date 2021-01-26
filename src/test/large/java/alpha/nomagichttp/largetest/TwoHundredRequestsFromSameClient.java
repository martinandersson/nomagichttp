package alpha.nomagichttp.largetest;

import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.handler.RequestHandlers;
import alpha.nomagichttp.message.Request.Body;
import alpha.nomagichttp.message.Responses;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.util.concurrent.CompletableFuture.completedStage;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * POSTs one hundred small requests and one hundred big requests using the same
 * client connection. I guess this is the "ultimate proof" that server-side
 * bytebuffer pooling and constant switching of Flow.Subscribers works as
 * expected.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class TwoHundredRequestsFromSameClient extends AbstractSingleClientTest
{
    private static final CompletionStage<String> EMPTY = completedStage("");
    
    @BeforeAll
    static void addHandler() {
        RequestHandler echo = RequestHandlers.POST().apply(req -> req.body()
                .map(Body::toText).orElse(EMPTY)
                .thenApply(Responses::ok));
        
        server().add("/echo", echo);
    }
    
    // Default name would have been msg argument, which is a super huge string!
    // This renders the html report file 50 MB large and extremely slow to open.
    // @DisplayName normally changes the "test name" but has no effect at all
    // for parameterized tests. So we must use the name attribute instead.
    // https://github.com/gradle/gradle/issues/5975
    @ParameterizedTest(name = "small")
    @MethodSource("small_source")
    void small_test(String requestBody) throws IOException, InterruptedException {
        postAndAssertResponse(requestBody);
    }
    
    private static Stream<String> small_source() {
        return requestBodies(100, 0, 10);
    }
    
    @ParameterizedTest(name = "big")
    @MethodSource("big_source")
    void big_test(String requestBody) throws IOException, InterruptedException {
        postAndAssertResponse(requestBody);
    }
    
    private static Stream<String> big_source() {
        final int channelBufferPoolSize = 5 * 16 * 1_024;
        return requestBodies(100, channelBufferPoolSize / 2, channelBufferPoolSize * 10);
    }
    
    private void postAndAssertResponse(String requestBody) throws IOException, InterruptedException {
        HttpResponse<String> res = postAndReceiveText("/echo", requestBody);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).isEqualTo(requestBody);
    }
    
    /**
     * Generate a stream of request bodies.<p>
     * 
     * Size of the stream is specified by {@code n}. Size of each body text will
     * be random between {@code minLen} (inclusive) and {@code maxLen}
     * (inclusive).
     * 
     * @param n stream limit
     * @param minLen minimum body length (inclusive)
     * @param maxLen maximum body length (inclusive)
     * 
     * @return request bodies (just text)
     */
    private static Stream<String> requestBodies(int n, int minLen, int maxLen) {
        Supplier<String> s = () -> {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            return DataUtil.text(r.nextInt(minLen, maxLen + 1));
        };
        
        return Stream.generate(s).limit(n);
    }
}