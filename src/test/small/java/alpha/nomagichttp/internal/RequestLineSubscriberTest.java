package alpha.nomagichttp.internal;

import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.message.RawRequest;
import alpha.nomagichttp.message.RequestLineParseException;
import alpha.nomagichttp.testutil.ByteBuffers;
import org.assertj.core.api.AbstractListAssert;
import org.assertj.core.api.ObjectAssert;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletionStage;

import static alpha.nomagichttp.testutil.Assertions.assertFailed;
import static alpha.nomagichttp.testutil.Assertions.assertSucceeded;
import static alpha.nomagichttp.testutil.TestPublishers.map;
import static alpha.nomagichttp.util.Publishers.just;
import static org.mockito.Mockito.mock;

/**
 * Small tests for {@link RequestLineSubscriber}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class RequestLineSubscriberTest
{
    @Test
    void happyPath() {
        var rl = "GET /hello.txt HTTP/1.1\r\n";
        assertResult(execute(rl))
            .containsExactly("GET", "/hello.txt", "HTTP/1.1", rl.length());
    }
    
    @Test
    void anyWhitespaceBetweenTokensIsDelimiter() {
        var rl = "GET\t/hello.txt    HTTP/1.1\r\n";
        assertResult(execute(rl))
            .containsExactly("GET", "/hello.txt", "HTTP/1.1", rl.length());
    }
    
    @Test
    void method_leadingWhitespaceIgnored() {
        var rl = "\r\n \t \r\n GET /hello.txt HTTP/1.1\r\n";
        assertResult(execute(rl))
            .containsExactly("GET", "/hello.txt", "HTTP/1.1", rl.length());
    }
    
    @Test
    void target_leadingWhitespaceIgnored() {
        var rl = "GET\r \t/hello.txt HTTP/1.1\n";
        assertResult(execute(rl))
            .containsExactly("GET", "/hello.txt", "HTTP/1.1", rl.length());
    }
    
    @Test
    void target_leadingWhitespaceLineFeedIsIllegal() {
        assertFailed(execute("GET \n/hello...."))
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
        var rl = "GET /hello.txt \tHTTP/1.1\n";
        assertResult(execute(rl))
            .containsExactly("GET", "/hello.txt", "HTTP/1.1", rl.length());
    }
    
    @Test
    void version_leadingWhitespaceLineFeedIsIllegal() {
        assertFailed(execute("GET /hello.txt \nHTTP...."))
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
        assertFailed(execute("GET\r/hello.txt\rBoom!"))
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
        assertFailed(execute("GET /hello.txt HT TP/1...."))
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
    
    private CompletionStage<RawRequest.Line> execute(String... items) {
        var rls = new RequestLineSubscriber(9_999, mock(ClientChannel.class));
        var up = map(just(items), ByteBuffers::toByteBufferPooled);
        up.subscribe(rls);
        return rls.toCompletionStage();
    }
    
    private AbstractListAssert<?, List<?>, Object, ObjectAssert<Object>>
        assertResult(CompletionStage<RawRequest.Line> actual) {
            return assertSucceeded(actual).extracting(
                "method", "target", "httpVersion", "parseLength");
    }
}