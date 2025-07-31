package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.message.BadRequestException;
import alpha.nomagichttp.message.HeaderParseException;
import alpha.nomagichttp.message.IllegalRequestBodyException;
import alpha.nomagichttp.message.IllegalResponseBodyException;
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
import static alpha.nomagichttp.handler.RequestHandler.POST;
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

/**
 * Tests concerning server errors.<p>
 * 
 * Tests in this class usually provoke exceptions, run asserts on exceptions
 * delivered to the exception handler, and of course, assert the response.<p>
 * 
 * {@link BadRequestException}, {@link IllegalRequestBodyException}, and
 * {@link IllegalResponseBodyException} are tested by
 * {@link MessageFramingTest}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ErrorTest extends AbstractRealTest
{
    private static final System.Logger LOG
            = System.getLogger(ErrorTest.class.getPackageName());
    
    private static final
        Throwing.Function<Request, Response, Exception> NOP = _ -> null;
    
    // TODO: fileResponse_blockedByWriteLock
    // Link in JavaDoc MessageTest fileResponse_okay
    
    @Nested
    class Special {
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
        
        /**
         * The error version of
         * {@link ExampleTest.NonPublicExamples#requestTrailers()}.
         */
        @Test
        void requestTrailersDiscarded_exceptionNotHandled() throws IOException {
            server().add("/", POST().apply(req ->
                    text(req.body().toText())));
            var rsp = client().writeReadTextUntilEOS("""
                    POST / HTTP/1.1
                    Trailer: just to trigger discarding
                    Transfer-Encoding: chunked
                    
                    6
                    Hello\s
                    0
                    Crash Plz: Whitespace in header name!
                    
                    """);
            assertThat(rsp).isEqualTo("""
                    HTTP/1.1 200 OK\r
                    Content-Type: text/plain; charset=utf-8\r
                    Content-Length: 6\r
                    \r
                    Hello\s""");
            logRecorder().assertRemove(
                    DEBUG, "Error while discarding request trailers, shutting down the input stream.",
                    HeaderParseException.class)
                .hasToString("""
                    HeaderParseException{prev=(hex:0x68, decimal:104, char:"h"), \
                    curr=(hex:0x20, decimal:32, char:" "), pos=N/A, \
                    msg=Whitespace in header name or before colon is not accepted.}""")
                .hasNoCause()
                .hasNoSuppressedExceptions();
        }
    }
}