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

import static alpha.nomagichttp.core.mediumtest.util.TestRequestHandlers.respondIsBodyEmpty;
import static alpha.nomagichttp.core.mediumtest.util.TestRequests.get;
import static alpha.nomagichttp.core.mediumtest.util.TestRequests.post;
import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.accepted;
import static alpha.nomagichttp.message.Responses.badRequest;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.message.Responses.status;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.TestConstants.CRLF;
import static alpha.nomagichttp.testutil.functional.Environment.isGitHubActions;
import static alpha.nomagichttp.testutil.functional.Environment.isJitPack;
import static alpha.nomagichttp.util.ScopedValues.channel;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static java.time.Duration.ofMillis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Detailed tests of request-to-response processing.
/// 
/// For coarse-grained message exchanges, see [MessageTest].
/// 
/// @author Martin Andersson (webmaster at martinandersson.com)
final class HttpExchangeTest extends AbstractRealTest
{
    private static final System.Logger LOG
            = System.getLogger(HttpExchangeTest.class.getPackageName());
    
    @Nested
    class AppCrash {
        /**
         * The channel remains fully open.
         * 
         * @see ClientLifeCycleTest.UnexpectedEndFromClient#receivedPartialHead()
         */
        @Test
        void bodyConsumer() throws IOException, InterruptedException {
            onExceptionAssert(RuntimeException.class, ch ->
                assertThat(ch.areBothStreamsOpen()).isTrue());
            server().add("/", POST().apply(req -> {
                // Read one byte before crash
                req.body().iterator().next().get();
                throw new RuntimeException();
            }));
            var rsp = client().writeReadTextUntilNewlines(post("not empty"));
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 500 Internal Server Error" + CRLF +
                "Content-Length: 0"                  + CRLF + CRLF);
            assertAwaitHandledAndLoggedExc()
                .isExactlyInstanceOf(RuntimeException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions();
            logRecorder().assertAwait(DEBUG,
                "Closing the child because client aborted the exchange.");
        }
        
        @Test
        void exceptionHandler() throws IOException, InterruptedException {
            usingExceptionHandler((_, _, _) -> {
                throw new RuntimeException("second");
            });
            server().add("/", GET().apply(_ -> {
                throw new RuntimeException("first");
            }));
            
            String rsp = client().writeReadTextUntilEOS(
                "GET / HTTP/1.1" + CRLF + CRLF);
            // No response
            assertThat(rsp)
                  .isEmpty();
            // But the exceptions were logged
            logRecorder().assertAwaitRemoveThrown()
                  .isExactlyInstanceOf(RuntimeException.class)
                  .hasMessage("first")
                  .hasNoCause()
                  .hasSuppressedException(new RuntimeException("second"));
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
    class ConnectionClose {
        @Test
        void remainingBytesLarge() throws IOException, InterruptedException {
            server().add("/", GET().apply(_ -> {
                var rsp = noContent();
                // We're testing the server's native connection management
                // without close-commands from the app.
                assertFalse(rsp.headers().hasConnectionClose());
                return rsp;
            }));
            try (var _ = client().openConnection()) {
                var rsp = client().writeReadTextUntilEOS("""
                    GET / HTTP/1.1
                    Content-Length: 666
                    
                    ...""");
                assertThat(rsp).isEqualTo("""
                    HTTP/1.1 204 No Content\r
                    Connection: close\r
                    \r
                    """);
                logRecorder().assertContainsOnlyOnce(DEBUG, """
                    Setting "Connection: close" because a satanic volume \
                    of request data is remaining.""");
                assertAwaitClosingChild();
            }
        }
        
        @Test
        void remainingBytesUnknown() throws IOException, InterruptedException {
            server().add("/", GET().apply(_ -> {
                var rsp = status(499, "Naughty Request");
                assertFalse(rsp.headers().hasConnectionClose());
                return rsp;
            }));
            try (var _ = client().openConnection()) {
                // "Expect: 100-continue" makes to difference for the test case
                var rsp = client().writeReadTextUntilEOS("""
                    GET / HTTP/1.1
                    Expect: 100-continue
                    Transfer-Encoding: chunked
                    
                    ...""");
                assertThat(rsp).isEqualTo("""
                    HTTP/1.1 499 Naughty Request\r
                    Connection: close\r
                    Content-Length: 0\r
                    \r
                    """);
                logRecorder().assertContainsOnlyOnce(DEBUG, """
                    Setting "Connection: close" because unknown length \
                    of request data is remaining.""");
                assertAwaitClosingChild();
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
    
    @Test
    void maxErrorResponses() throws IOException, InterruptedException {
        server().add("/", GET().apply(_ -> badRequest().toBuilder()
                // This header would have caused the server to close the connection,
                // but we want to run many "failed" responses
                .removeHeaderValue("Connection", "close").build()));
        
        IORunnable sendBadRequest = () -> {
            String rsp = client().writeReadTextUntilNewlines(get());
            assertThat(rsp).startsWith("HTTP/1.1 400 Bad Request");
        };
        
        final int max = server().getConfig().maxErrorResponses();
        LOG.log(INFO, () -> "Configured max: " + max);
        
        try (var conn = client().openConnection()) {
            for (int i = max; i > 1; --i) {
                if (LOG.isLoggable(INFO)) {
                    LOG.log(INFO, "Running #" + i);
                }
                sendBadRequest.run();
                assertTrue(conn.isOpen());
            }
            LOG.log(INFO, "Running last.");
            sendBadRequest.run();
            
            logRecorder().assertAwait(
                DEBUG, "Max number of error responses reached, closing channel.");
            assertTrue(client().serverClosedOutput());
            assertTrue(client().serverClosedInput());
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
    
    @Test
    void writingTwoFinalResponses() throws IOException, InterruptedException {
        server().add("/", GET().apply(_ -> {
            channel().write(noContent());
            return text("this won't work");
        }));
        String rsp = client().writeReadTextUntilNewlines(
            "GET / HTTP/1.1"          + CRLF + CRLF);
        // The first one succeeded
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 204 No Content" + CRLF + CRLF);
        // The second one caused some problems
        // TODO: See below, is repeated in next test case
        logRecorder().assertAwaitRemove(
                ERROR, """
                    Response bytes already sent, \
                    can not handle this exception (closing child).""",
                IllegalArgumentException.class)
            .hasMessage("""
                Request processing chain \
                both wrote and returned a final response.""")
            .hasNoCause()
            .hasNoSuppressedExceptions();
    }
}
