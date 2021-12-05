package alpha.nomagichttp.message;

import alpha.nomagichttp.util.Headers;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultContentHeadersTest
{
    @Test
    void contentLength_happyPath() {
        var testee = of("Content-Length", "123");
        assertThat(testee.contentLength()).hasValue(123);
    }
    
    @Test
    void contentLength_BadHeaderException_repeated() {
        var testee = of(
            "Content-Length", "1",
            "Content-Length", "2");
        assertThatThrownBy(testee::contentLength)
            .isExactlyInstanceOf(BadHeaderException.class)
            .hasMessage("Multiple Content-Length values in request.")
            .hasNoSuppressedExceptions()
            .hasNoCause();
    }
    
    @Test
    void contentLength_BadHeaderException_NaN() {
        var testee = of(
            "Content-Length", "",
            "Content-Length", "NaN");
        assertThatThrownBy(testee::contentLength)
            .isExactlyInstanceOf(BadHeaderException.class)
            .hasMessage("Can not parse Content-Length (\"NaN\") into a long.")
            .hasNoSuppressedExceptions()
            .getCause()
                .isExactlyInstanceOf(NumberFormatException.class)
                .hasMessage("For input string: \"NaN\"");
    }
    
    @Test
    void transferEncoding() {
        var testee = of(
            "Transfer-Encoding", "bla,,  ,",
            "Transfer-Encoding", "bla, bla");
        assertThat(testee.transferEncoding())
            .containsExactly("bla", "bla", "bla");
    }
    
    private static DefaultContentHeaders of(String... headers) {
        return new DefaultContentHeaders(Headers.of(headers));
    }
}