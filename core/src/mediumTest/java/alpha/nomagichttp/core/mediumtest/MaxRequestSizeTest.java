package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.message.MaxRequestBodyBufferSizeException;
import alpha.nomagichttp.message.MaxRequestHeadSizeException;
import alpha.nomagichttp.testutil.functional.AbstractRealTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static alpha.nomagichttp.handler.RequestHandler.POST;
import static org.assertj.core.api.Assertions.assertThat;

///  Tests of exceeding configured size-tolerance.
/// 
/// @author Martin Andersson (webmaster at martinandersson.com)
final class MaxRequestSizeTest extends AbstractRealTest
{
    @Test
    void head() throws IOException, InterruptedException {
        usingConfiguration()
            .maxRequestHeadSize(1);
        server();
        String rsp = client().writeReadTextUntilNewlines(
            "AB");
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 413 Entity Too Large\r
            Connection: close\r
            Content-Length: 0\r\n\r\n""");
        assertAwaitHandledAndLoggedExc()
            .isExactlyInstanceOf(MaxRequestHeadSizeException.class)
            .hasMessage("Configured max tolerance is 1 bytes.")
            .hasNoCause()
            .hasNoSuppressedExceptions();
    }
    
    /// A known length pre-allocates the entire buffer.
    @Test
    void bodyBuffer_implBytesFast()
            throws IOException, InterruptedException {
        runExchange("""
            POST / HTTP/1.1\r
            Content-Length: 2\r
            \r
            AB
            """);
    }
    
    /// An unknown length necessitates the use of a dynamically-sized buffer.
    @Test
    void bodyBuffer_implBytesSlow()
            throws IOException, InterruptedException {
        runExchange("""
            POST / HTTP/1.1
            Transfer-Encoding: chunked
            
            2
            AB
            0
            
            """);
    }
    
    private void runExchange(String request)
            throws IOException, InterruptedException {
        usingConfiguration()
            .maxRequestBodyBufferSize(1);
        server()
            .add("/", POST().apply(req -> {
                // Here implementation-switch happens (and each one fails)
                req.body().bytes();
                return null;
            }));
        String rsp = client().writeReadTextUntilNewlines(request);
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 413 Entity Too Large\r
            Connection: close\r
            Content-Length: 0\r\n\r\n""");
        assertAwaitHandledAndLoggedExc()
            .isExactlyInstanceOf(MaxRequestBodyBufferSizeException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("Configured max tolerance is 1 bytes.");
    }
}
