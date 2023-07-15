package alpha.nomagichttp;

import alpha.nomagichttp.message.HttpVersionParseException;
import org.junit.jupiter.api.Test;

import static alpha.nomagichttp.HttpConstants.Version.HTTP_0_9;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_0;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_2;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_3;
import static alpha.nomagichttp.HttpConstants.Version.parse;
import static java.util.Arrays.stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Small tests of {@link HttpConstants.Version#parse(String)}.
 *
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class HttpVersionParseTest
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
    
    @Test
    void exc_noSlash() {
        assertThatThrownBy(() -> parse(""))
                .isExactlyInstanceOf(HttpVersionParseException.class)
                .hasNoCause()
                .hasMessage("No forward slash.")
                .extracting("requestFieldValue")
                .isEqualTo("");
    }
    
    @Test
    void exc_noHTTP() {
        assertThatThrownBy(() -> parse("hTtP/"))
                .isExactlyInstanceOf(HttpVersionParseException.class)
                .hasNoCause()
                .hasMessage("HTTP-name \"hTtP\" is not \"HTTP\".")
                .extracting("requestFieldValue")
                .isEqualTo("hTtP/");
    }
    
    @Test
    void exc_parseMajorFail() {
        assertThatThrownBy(() -> parse("HTTP/x"))
                .isExactlyInstanceOf(HttpVersionParseException.class)
                .hasCauseExactlyInstanceOf(NumberFormatException.class)
                .extracting("requestFieldValue")
                .isEqualTo("HTTP/x");
    }
    
    @Test
    void exc_parseMinorFail() {
        assertThatThrownBy(() -> parse("HTTP/1.x"))
                .isExactlyInstanceOf(HttpVersionParseException.class)
                .hasCauseExactlyInstanceOf(NumberFormatException.class)
                .extracting("requestFieldValue")
                .isEqualTo("HTTP/1.x");
    }
    
    @Test
    void exc_minorNotSupported_major0() {
        assertThatThrownBy(() -> parse("HTTP/0.8"))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasNoCause()
                .hasMessage("0:8");
    }
    
    @Test
    void exc_minorNotSupported_major1() {
        assertThatThrownBy(() -> parse("HTTP/1.3"))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasNoCause()
                .hasMessage("1:3");
    }
    
    @Test
    void exc_majorNotSupported() {
        assertThatThrownBy(() -> parse("HTTP/99999999"))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasNoCause()
                .hasMessage("99999999:");
    }
    
    @Test
    void exc_minorRequired() {
        assertThatThrownBy(() -> parse("HTTP/1"))
                .isExactlyInstanceOf(HttpVersionParseException.class)
                .hasNoCause()
                .hasMessage("No minor version provided when one was expected.")
                .extracting("requestFieldValue")
                .isEqualTo("HTTP/1");
    }
    
    @Test
    void exc_minorUnexpected_dot() {
        assertThatThrownBy(() -> parse("HTTP/2."))
                .isExactlyInstanceOf(HttpVersionParseException.class)
                .hasNoCause()
                .hasMessage("Minor version provided when none was expected.")
                .extracting("requestFieldValue")
                .isEqualTo("HTTP/2.");
    }
    
    @Test
    void exc_minorUnexpected_number() {
        assertThatThrownBy(() -> parse("HTTP/2.1"))
                .isExactlyInstanceOf(HttpVersionParseException.class)
                .hasNoCause()
                .hasMessage("Minor version provided when none was expected.")
                .extracting("requestFieldValue")
                .isEqualTo("HTTP/2.1");
    }
    
    @Test
    void isLessThan() {
        HttpConstants.Version[] v = HttpConstants.Version.values();
        for (int i = 0; i < v.length - 1; ++i) {
            assertTrue(v[i].isLessThan(v[i + 1])); // HTTP 0.9 < 1.0
            assertFalse(v[i].isLessThan(v[i]));    // !(HTTP 0.9 < HTTP 0.9)
        }
    }
}