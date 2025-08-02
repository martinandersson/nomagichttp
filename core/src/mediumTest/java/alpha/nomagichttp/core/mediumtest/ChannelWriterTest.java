package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.testutil.IORunnable;
import alpha.nomagichttp.testutil.functional.AbstractRealTest;
import alpha.nomagichttp.util.Throwing;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static alpha.nomagichttp.core.mediumtest.util.TestRequests.get;
import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.message.Responses.badRequest;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.TestConstants.CRLF;
import static alpha.nomagichttp.util.ScopedValues.channel;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

///  Tests of writing responses.
final class ChannelWriterTest extends AbstractRealTest
{
    private static final System.Logger LOG
            = System.getLogger(ChannelWriterTest.class.getPackageName());
    
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
    class WritingTwoFinalResponses {
        @Test
        void explicit() throws IOException, InterruptedException {
            engine(_ -> {
                channel().write(noContent());
                channel().write(text("blah"));
                return null;
            }, IllegalStateException.class,
               "Already wrote a final response");
        }
        
        @Test
        void implicit() throws IOException, InterruptedException {
            engine(_ -> {
                channel().write(noContent());
                return text("blah");
            }, IllegalArgumentException.class,
               "Request processing chain both wrote and returned a final response.");
        }
        
        private void engine(
                Throwing.Function<Request, Response, ? extends Exception> handler,
                Class<? extends Throwable> thr, String msg)
                throws IOException, InterruptedException {
            server().add("/",
                GET().apply(handler));
            String rsp = client().writeReadTextUntilEOS(
                "GET / HTTP/1.1" + CRLF + CRLF);
            // The first one succeeded
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 204 No Content" + CRLF + CRLF);
            // The second one caused some problems
            logRecorder().assertAwaitRemove(
                    ERROR, """
                        Response bytes already sent, \
                        can not handle this exception (closing child).""",
                    thr)
                .hasMessage(msg)
                .hasNoCause()
                .hasNoSuppressedExceptions();
        }
    }
}
