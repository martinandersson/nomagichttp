package alpha.nomagichttp.message;

import org.junit.jupiter.api.Test;

import java.util.List;

import static alpha.nomagichttp.testutil.Headers.contentHeaders;
import static alpha.nomagichttp.testutil.Headers.linkedHashMap;
import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class DefaultContentHeadersTest
{
    @Test
    void caseIsRetained_butQueryingIsNotCaseSensitive() {
        var testee = contentHeaders("name", "VALUE");
        assertThat(linkedHashMap(testee))
                .containsOnly(entry("name", List.of("VALUE")));
        assertThat(testee.contains("NAME", "value"))
                .isTrue();
        assertThat(testee.allValues("nAmE"))
                .containsOnly("VALUE");
        assertThat(testee.allTokens("NaMe"))
                .containsOnly("VALUE");
    }
    
    @Test
    void contentLength_happyPath() {
        var testee = contentHeaders("Content-Length", "123");
        assertThat(testee.contentLength()).hasValue(123);
    }
    
    @Test
    void contentLength_BadHeaderException_repeated() {
        var testee = contentHeaders(
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
        var testee = contentHeaders(
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
        var testee = contentHeaders("Content-Length", "-123");
        assertThatThrownBy(testee::contentLength)
            .isExactlyInstanceOf(BadHeaderException.class)
            .hasMessage("Content-Length is negative (-123)")
            .hasNoSuppressedExceptions()
            .hasNoCause();
    }
    
    @Test
    void transferEncoding_happyPath() {
        var testee = contentHeaders(
            "Transfer-Encoding", "bla,,  ,",
            "Transfer-Encoding", "bla, chuNKED");
        assertThat(testee.transferEncoding())
            .containsExactly("bla", "bla", "chuNKED");
    }
    
    @Test
    void transferEncoding_chunkedNotLast() {
        var testee = contentHeaders("Transfer-Encoding", "chunked, blabla");
        assertThatThrownBy(testee::transferEncoding)
            .isExactlyInstanceOf(BadHeaderException.class)
            .hasMessage("Last Transfer-Encoding token (\"blabla\") is not \"chunked\".")
            .hasNoSuppressedExceptions()
            .hasNoCause();
    }
    
    @Test
    void toStringTest() {
        var testee = contentHeaders(
            "K1", "a", "K1", "b",
            "K2", "c");
        assertThat(testee.toString())
            .isEqualTo("{K1=[a, b], K2=[c]}");
    }
}