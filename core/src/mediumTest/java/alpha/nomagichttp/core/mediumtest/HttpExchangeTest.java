package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.IdleConnectionException;
import alpha.nomagichttp.message.MaxRequestBodyBufferSizeException;
import alpha.nomagichttp.message.MaxRequestHeadSizeException;
import alpha.nomagichttp.message.UnsupportedTransferCodingException;
import alpha.nomagichttp.testutil.IORunnable;
import alpha.nomagichttp.testutil.functional.AbstractRealTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Serial;

import static alpha.nomagichttp.core.mediumtest.util.TestRequestHandlers.respondIsBodyEmpty;
import static alpha.nomagichttp.core.mediumtest.util.TestRequests.post;
import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.accepted;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.TestConstants.CRLF;
import static alpha.nomagichttp.testutil.functional.Environment.isGitHubActions;
import static alpha.nomagichttp.testutil.functional.Environment.isJitPack;
import static java.lang.System.Logger.Level.DEBUG;
import static java.time.Duration.ofMillis;
import static org.assertj.core.api.Assertions.assertThat;

/// Weird tests that don't really fit anywhere else.
/// 
/// @author Martin Andersson (webmaster at martinandersson.com)
final class HttpExchangeTest extends AbstractRealTest
{
    @Nested
    class AppCrash {
        private static final class OopsException extends RuntimeException {
            @Serial
            private static final long serialVersionUID = 1L;
            OopsException() { }
            OopsException(String msg) { super(msg); }
        }
        
        @Test
        void exceptionHandler() throws IOException, InterruptedException {
            usingExceptionHandler((_, _, _) -> {
                throw new OopsException("second");
            });
            server().add("/", GET().apply(_ -> {
                throw new OopsException("first");
            }));
            
            String rsp = client().writeReadTextUntilEOS(
                "GET / HTTP/1.1" + CRLF + CRLF);
            // No response
            assertThat(rsp)
                  .isEmpty();
            // But the exceptions were logged
            logRecorder().assertAwaitRemoveThrown()
                  .isExactlyInstanceOf(OopsException.class)
                  .hasMessage("first")
                  .hasNoCause()
                  .hasSuppressedException(new OopsException("second"));
        }
        
        /**
         * The channel remains fully open.
         * 
         * @see ClientLifeCycleTest.UnexpectedEndFromClient#serverReceivedPartialHead()
         */
        @Test
        void bodyConsumer() throws IOException, InterruptedException {
            onExceptionAssert(OopsException.class, ch ->
                assertThat(ch.areBothStreamsOpen()).isTrue());
            server().add("/", POST().apply(req -> {
                // Read one byte before crash
                req.body().iterator().next().get();
                throw new OopsException();
            }));
            var rsp = client().writeReadTextUntilNewlines(post("not empty"));
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 500 Internal Server Error" + CRLF +
                "Content-Length: 0"                  + CRLF + CRLF);
            assertAwaitHandledAndLoggedExc()
                .isExactlyInstanceOf(OopsException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions();
            logRecorder().assertAwait(DEBUG,
                "Closing the child because client aborted the exchange.");
        }
    }
    
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
    class IdleConnection {
        @Test
        void duringHead() throws IOException, InterruptedException {
            // On the author's machine, ofMillis(1) was never any problem.
            // But for GitHub's environment, such a short duration may
            // occasionally also time out the write operation (i.e. background
            // thread closes the write stream, and we get no response or a
            // corrupt one)!
            // TODO: Fix brittle and nondeterministic test
            usingConfiguration().timeoutIdleConnection(
                (isGitHubActions() || isJitPack()) ? ofMillis(10) : ofMillis(1));
            server();
            try (var _ = client().openConnection()) {
                // Never send anything and expect a response
                String rsp = client().readTextUntilNewlines();
                assertThat(rsp).isEqualTo(
                    "HTTP/1.1 408 Request Timeout" + CRLF +
                    "Connection: close"            + CRLF +
                    "Content-Length: 0"            + CRLF + CRLF);
                assertThat(pollServerException())
                    .isExactlyInstanceOf(IdleConnectionException.class)
                    .hasMessage(null)
                    .hasNoCause()
                    .hasNoSuppressedExceptions();
                // Timeout triggered by scheduler
                logRecorder().assertContainsOnlyOnce(
                    DEBUG, "Idle connection; shutting down read stream");
            }
        }
        
        // TODO: Add duringResponse()
        //       Can't configure a short timeout only for the write operation,
        //       so skipping this for now.
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
    
    @Test
    void unsupportedTransferCoding() throws IOException, InterruptedException {
        server();
        String rsp = client().writeReadTextUntilNewlines("""
            GET / HTTP/1.1\r
            Transfer-Encoding: blabla, chunked\r\n\r
            """);
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 501 Not Implemented\r
            Connection: close\r
            Content-Length: 0\r\n\r\n""");
        assertThat(pollServerException())
            .isExactlyInstanceOf(UnsupportedTransferCodingException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("Unsupported Transfer-Encoding: blabla");
    }
}
