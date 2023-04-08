package alpha.nomagichttp.message;

import alpha.nomagichttp.util.Headers;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class DefaultContentHeadersTest
{
    @Test
    void caseIsRetained_butQueryingIsNotCaseSensitive() {
        var testee = of("name", "VALUE");
        var copy = new LinkedHashMap<String, List<String>>();
        testee.forEach(copy::put);
        assertThat(copy)
                .containsExactly(entry("name", List.of("VALUE")));
        assertThat(testee.contains("NAME", "value"))
                .isTrue();
        assertThat(testee.allValues("nAmE"))
                .containsOnly("VALUE");
        assertThat(testee.allTokens("NaMe"))
                .containsOnly("VALUE");
    }
    
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
            .cause()
                .isExactlyInstanceOf(NumberFormatException.class)
                .hasMessage("For input string: \"NaN\"");
    }
    
    @Test
    void contentLength_BadHeaderException_negative() {
        var testee = of("Content-Length", "-123");
        assertThatThrownBy(testee::contentLength)
            .isExactlyInstanceOf(BadHeaderException.class)
            .hasMessage("Content-Length is negative (-123)")
            .hasNoSuppressedExceptions()
            .hasNoCause();
    }
    
    @Test
    void transferEncoding_happyPath() {
        var testee = of(
            "Transfer-Encoding", "bla,,  ,",
            "Transfer-Encoding", "bla, chuNKED");
        assertThat(testee.transferEncoding())
            .containsExactly("bla", "bla", "chuNKED");
    }
    
    @Test
    void transferEncoding_chunkedNotLast() {
        var testee = of("Transfer-Encoding", "chunked, blabla");
        assertThatThrownBy(testee::transferEncoding)
            .isExactlyInstanceOf(BadHeaderException.class)
            .hasMessage("Last Transfer-Encoding token (\"blabla\") is not \"chunked\".")
            .hasNoSuppressedExceptions()
            .hasNoCause();
    }
    
    @Test
    void toStringTest() {
        var testee = of(
            "K1", "a", "K1", "b",
            "K2", "c");
        assertThat(testee.toString())
            .isEqualTo("{K1=[a, b], K2=[c]}");
    }
    
    private static DefaultContentHeaders of(String... headers) {
        return new DefaultContentHeaders(Headers.of(headers), false);
    }
}