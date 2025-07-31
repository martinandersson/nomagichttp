package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.message.MaxRequestBodyBufferSizeException;
import alpha.nomagichttp.message.MaxRequestHeadSizeException;
import alpha.nomagichttp.testutil.IORunnable;
import alpha.nomagichttp.testutil.functional.AbstractRealTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static alpha.nomagichttp.core.mediumtest.util.TestRequestHandlers.respondIsBodyEmpty;
import static alpha.nomagichttp.core.mediumtest.util.TestRequests.post;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.accepted;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.TestConstants.CRLF;
import static org.assertj.core.api.Assertions.assertThat;

/// Weird tests that don't really fit anywhere else.
/// 
/// @author Martin Andersson (webmaster at martinandersson.com)
final class HttpExchangeTest extends AbstractRealTest
{
    @Nested
    class ConnectionReuse {
        @Test
        void normal() throws IOException {
            // Echo request body
            server().add("/", POST().apply(req ->
                text(req.body().toText())));
            
            final var resHead =
                "HTTP/1.1 200 OK"                         + CRLF +
                "Content-Type: text/plain; charset=utf-8" + CRLF +
                "Content-Length: 3"                       + CRLF + CRLF;
            
            try (var _ = client().openConnection()) {
                var res1 = client().writeReadTextUntil(post("ABC"), "ABC");
                assertThat(res1).isEqualTo(resHead + "ABC");
                
                var res2 = client().writeReadTextUntil(post("DEF"), "DEF");
                assertThat(res2).isEqualTo(resHead + "DEF");
            }
        }
        
        @Test
        void discardWhole() throws IOException {
            server().add("/",
                    // Does not consume the body
                    respondIsBodyEmpty());
            
            IORunnable exchange = () -> {
                String req = post("x".repeat(10)),
                       res = client().writeReadTextUntil(req, "false");
                
                assertThat(res).isEqualTo(
                    "HTTP/1.1 200 OK"                         + CRLF +
                    "Content-Type: text/plain; charset=utf-8" + CRLF +
                    "Content-Length: 5"                       + CRLF + CRLF +
                    
                    "false");
            };
            
            try (var _ = client().openConnection()) {
                exchange.run();
                // Body auto-discarded. This is using the same connection:
                exchange.run();
            }
        }
        
        @Test
        void discardHalf() throws IOException {
            final int length = 100,
                      midway = length / 2;
            
            server().add("/", POST().apply(req -> {
                // Read only half of the body
                int n = 0;
                var it = req.body().iterator();
                while (it.hasNext() && n < midway) {
                    var buf = it.next();
                    while (buf.hasRemaining()) {
                        buf.get();
                        ++n;
                    }
                }
                return accepted();
            }));
            
            IORunnable exchange = () -> {
                String req = post("x".repeat(length)),
                       res = client().writeReadTextUntilNewlines(req);
                
                assertThat(res).isEqualTo(
                    "HTTP/1.1 202 Accepted" + CRLF +
                    "Content-Length: 0"     + CRLF + CRLF);
            };
            
            try (var _ = client().openConnection()) {
                exchange.run();
                exchange.run();
            }
        }
    }
    
    @Nested
    class MaxRequestSize {
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
}
