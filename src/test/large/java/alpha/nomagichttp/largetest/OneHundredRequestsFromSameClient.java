package alpha.nomagichttp.largetest;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class OneHundredRequestsFromSameClient extends AbstractSingleClientTest
{
    // N = repetitions, LEN = request body length (char count)
    private static final int N       = 100,
                             LEN_MIN =   0,
                             LEN_MAX =  10;
    
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
            return text(r.nextInt(LEN_MIN, LEN_MAX + 1));
        };
        
        return Stream.generate(s).limit(N);
    }
}