package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.testutil.functional.AbstractRealTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.testutil.TestConstants.CRLF;
import static java.lang.System.Logger.Level.DEBUG;
import static org.assertj.core.api.Assertions.assertThat;

///  Tests of crashing application code.
final class AppCrashTest extends AbstractRealTest {
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
        var rsp = client().writeReadTextUntilNewlines("""
                POST / HTTP/1.1
                Content-Length: 1
                
                X""");
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
