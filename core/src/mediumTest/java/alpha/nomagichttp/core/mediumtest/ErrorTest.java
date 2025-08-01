package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.message.BadRequestException;
import alpha.nomagichttp.message.HeaderParseException;
import alpha.nomagichttp.message.IllegalRequestBodyException;
import alpha.nomagichttp.message.IllegalResponseBodyException;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.testutil.functional.AbstractRealTest;
import alpha.nomagichttp.util.Throwing;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.text;
import static java.lang.System.Logger.Level.DEBUG;
import static org.assertj.core.api.Assertions.assertThat;

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