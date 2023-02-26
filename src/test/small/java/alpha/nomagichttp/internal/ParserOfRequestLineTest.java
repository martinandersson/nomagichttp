package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.RawRequest;
import alpha.nomagichttp.message.RequestLineParseException;
import org.assertj.core.api.AbstractListAssert;
import org.assertj.core.api.ObjectAssert;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static alpha.nomagichttp.testutil.TestByteBufferIterables.just;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Small tests for {@link ParserOfRequestLine}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ParserOfRequestLineTest
{
    @Test
    void happyPath() {
        var input = "GET /hello.txt HTTP/1.1\r\n";
        assertThat(parse(input)).contains(
                "GET", "/hello.txt", "HTTP/1.1", input.length());
    }
    
    @Test
    void anyWhitespaceBetweenTokensIsDelimiter() {
        var input = "GET\t/hello.txt    HTTP/1.1\r\n";
        assertThat(parse(input)).contains(
                "GET", "/hello.txt", "HTTP/1.1", input.length());
    }
    
    @Test
    void method_leadingWhitespaceIgnored() {
        var input = "\r\n \t \r\n GET /hello.txt HTTP/1.1\r\n";
        assertThat(parse(input)).contains(
                "GET", "/hello.txt", "HTTP/1.1", input.length());
    }
    
    @Test
    void target_leadingWhitespaceIgnored() {
        var input = "GET\r \t/hello.txt HTTP/1.1\n";
        assertThat(parse(input)).contains(
                "GET", "/hello.txt", "HTTP/1.1", input.length());
    }
    
    @Test
    void target_leadingWhitespaceLineFeedIsIllegal() {
        assertThatThrownBy(() -> parse("GET \n/hello...."))
            .isExactlyInstanceOf(RequestLineParseException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasToString("""
                RequestLineParseException{\
                prev=(hex:0x20, decimal:32, char:" "), \
                curr=(hex:0xA, decimal:10, char:"\\n"), \
                pos=4, \
                msg=Unexpected LF.}""");
    }
    
    @Test
    void version_leadingWhitespaceIgnored() {
        var input = "GET /hello.txt \tHTTP/1.1\n";
        assertThat(parse(input)).contains(
                "GET", "/hello.txt", "HTTP/1.1", input.length());
    }
    
    @Test
    void version_leadingWhitespaceLineFeedIsIllegal() {
        assertThatThrownBy(() -> parse("GET /hello.txt \nHTTP...."))
            .isExactlyInstanceOf(RequestLineParseException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasToString("""
                RequestLineParseException{\
                prev=(hex:0x20, decimal:32, char:" "), \
                curr=(hex:0xA, decimal:10, char:"\\n"), \
                pos=15, \
                msg=Empty HTTP-version.}""");
    }
    
    // CR serves as a delimiter ("any whitespace") between method and
    // request-target. But for the HTTP version token, which is waiting on
    // a newline to be his delimiter, then it is required that if CR is
    // provided, it must be followed by LF.
    // TODO: Giving CR different semantics is inconsistent. Research.
    @Test
    void version_illegalLineBreak() {
        assertThatThrownBy(() -> parse("GET\r/hello.txt\rBoom!"))
            .isExactlyInstanceOf(RequestLineParseException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasToString("""
                RequestLineParseException{\
                prev=(hex:0xD, decimal:13, char:"\\r"), \
                curr=(hex:0x42, decimal:66, char:"B"), \
                pos=15, \
                msg=CR followed by something other than LF.}""");
    }
    
    @Test
    void version_illegalWhitespaceInToken() {
        assertThatThrownBy(() -> parse("GET /hello.txt HT TP/1...."))
            .isExactlyInstanceOf(RequestLineParseException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasToString("""
                RequestLineParseException{\
                prev=(hex:0x54, decimal:84, char:"T"), \
                curr=(hex:0x20, decimal:32, char:" "), \
                pos=17, \
                msg=Whitespace in HTTP-version not accepted.}""");
    }
    
    private RawRequest.Line parse(String... items) {
        try {
            return new ParserOfRequestLine(just(items), 9_999).parse();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
    
    private static AbstractListAssert<?, List<?>, Object, ObjectAssert<Object>>
        assertThat(RawRequest.Line actual) {
            return org.assertj.core.api.Assertions.assertThat(actual)
                    .extracting("method", "target", "httpVersion", "length");
    }
}