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
        final String expected = text(size);
        System.out.println("Expected size: " + expected.length());
        System.out.println("First ten: " + expected.substring(0, 10));
        System.out.println("Last ten:  " + expected.substring(expected.length() - 10));
        System.out.println();
        
        HttpResponse<String> res = post(expected);
        assertThat(res.statusCode()).isEqualTo(200);
        
        final String actual = res.body();
        System.out.println("Actual size: " + actual.length());
        System.out.println("First ten: " + actual.substring(0, 10));
        System.out.println("Last ten:  " + actual.substring(expected.length() - 10));
        
        assertThat(actual).isEqualTo(expected);
    }
}