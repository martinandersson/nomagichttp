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

import static alpha.nomagichttp.core.mediumtest.util.TestRequests.get;
import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.badRequest;
import static alpha.nomagichttp.message.Responses.noContent;
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
