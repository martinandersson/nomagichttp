package alpha.nomagichttp.largetest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Stream;

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
    @ParameterizedTest
    @MethodSource("small_messages")
    void small(String msg) throws IOException, InterruptedException {
        HttpResponse<String> res = post(msg);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).isEqualTo(msg);
    }
    
    private static Stream<String> small_messages() {
        return messages(100, 0, 10);
    }
    
    // Default name would have been msg argument, which is a super huge string!
    // This renders the html report file 50 MB large and extremely slow to open.
    // @DisplayName should take care of that, but doesn't work:
    // https://github.com/gradle/gradle/issues/5975
    // TODO: Keep track of updates and remove comment when resolved.
    @DisplayName("big")
    @ParameterizedTest
    @MethodSource("big_messages")
    void big(String msg) throws IOException, InterruptedException {
        HttpResponse<String> res = post(msg);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).isEqualTo(msg);
    }
    
    private static Stream<String> big_messages() {
        final int channelBufferPoolSize = 5 * 16 * 1_024;
        return messages(100, channelBufferPoolSize / 2, channelBufferPoolSize * 10);
    }
    
    /**
     * Generate a stream of text messages.<p>
     * 
     * Size of the stream is specified by {@code n}. Size of each message will
     * be random between {@code minLen} (inclusive) and {@code maxLen}
     * (inclusive).
     * 
     * @param n stream limit
     * @param minLen minimum text length (inclusive)
     * @param maxLen maximum text length (inclusive)
     * 
     * @return text messages
     */
    private static Stream<String> messages(int n, int minLen, int maxLen) {
        Supplier<String> s = () -> {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            return text(r.nextInt(minLen, maxLen + 1));
        };
        
        return Stream.generate(s).limit(n);
    }
}