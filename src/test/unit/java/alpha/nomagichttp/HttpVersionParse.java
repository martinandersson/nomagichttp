package alpha.nomagichttp;

import org.junit.jupiter.api.Test;

import static alpha.nomagichttp.HttpConstants.Version.HTTP_0_9;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_0;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_2;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_3;
import static alpha.nomagichttp.HttpConstants.Version.parse;
import static java.util.Arrays.stream;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Small tests of {@link HttpConstants.Version#parse(String)}.
 *
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class HttpVersionParse
{
    @Test
    void happy_path() {
        Object[][] cases = {
            {"HTTP/0.9", HTTP_0_9},
            {"HTTP/1.0", HTTP_1_0},
            {"HTTP/1.1", HTTP_1_1},
            {"HTTP/2",   HTTP_2   },
            {"HTTP/3",   HTTP_3   } };
        
        stream(cases).forEach(v -> {
            assertThat(parse((String) v[0])).isSameAs(v[1]);
            assertThat(v[1].toString()).isEqualTo(v[0]);
        });
    }
}