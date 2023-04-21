package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.RawRequest;
import alpha.nomagichttp.message.RequestLineParseException;
import org.assertj.core.api.AbstractListAssert;
import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.ObjectAssert;
import org.junit.jupiter.api.Test;

import java.util.List;

import static alpha.nomagichttp.testutil.ByteBufferIterables.just;
import static alpha.nomagichttp.util.Blah.throwsNoChecked;
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
        assertParseException("GET \n/hello....")
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
        assertParseException("GET /hello.txt \nHTTP....")
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
        assertParseException("GET\r/hello.txt\rBoom!")
            .hasToString("""
                RequestLineParseException{\
                prev=(hex:0xD, decimal:13, char:"\\r"), \
                curr=(hex:0x42, decimal:66, char:"B"), \
                pos=15, \
                msg=CR followed by something other than LF.}""");
    }
    
    @Test
    void version_illegalWhitespaceInToken() {
        assertParseException("GET /hello.txt HT TP/1....")
            .hasToString("""
                RequestLineParseException{\
                prev=(hex:0x54, decimal:84, char:"T"), \
                curr=(hex:0x20, decimal:32, char:" "), \
                pos=17, \
                msg=Whitespace in HTTP-version not accepted.}""");
    }
    
    private static RawRequest.Line parse(String... items) {
        var parser = new ParserOfRequestLine(just(items), 9_999);
        // Throws no IOException because we are not reading from a channel
        return throwsNoChecked(parser::parse);
    }
    
    private static AbstractListAssert<?, List<?>, Object, ObjectAssert<Object>>
        assertThat(RawRequest.Line actual) {
            return Assertions.assertThat(actual)
                             .extracting(
                                 "method", "target", "httpVersion", "length");
    }
    
    private static AbstractThrowableAssert<?, ? extends Throwable>
        assertParseException(String input) {
            return assertThatThrownBy(() -> parse(input))
                    .isExactlyInstanceOf(RequestLineParseException.class)
                    .hasNoCause()
                    .hasNoSuppressedExceptions();
    }
}