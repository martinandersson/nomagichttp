package alpha.nomagichttp.largetest;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class BigTextRequest extends AbstractSingleClientTest
{
    @Test
    void test() throws IOException, InterruptedException {
        // ChannelBytePublisher has 5 of these in a pool, we use 20
        final int size = 20 * 16 * 1_024;
        String msg = randomText(size);
        HttpResponse<String> res = post(msg);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).isEqualTo(msg);
    }
}