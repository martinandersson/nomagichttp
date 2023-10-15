package alpha.nomagichttp.handler;

import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.testutil.LogRecorder;
import org.junit.jupiter.api.Test;

import java.io.Serial;

import static java.lang.System.Logger.Level.WARNING;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests of {@link ExceptionHandler#BASE}.<p>
 * 
 * This class is thin on test cases, as the base handler does not contain much
 * logic, and most error cases are tested in medium-sized tests.
 */
final class ExceptionHandlerTest {
    @Test
    void weirdResponseFromExceptionClass() {
        class DumbException extends Exception implements HasResponse {
            @Serial private static final long serialVersionUID = 1L;
            @Override public Response getResponse() {
                return Responses.status(123, "Interim!");
            }
        }
        var logs = LogRecorder.startRecording();
        try {
            var actual = ExceptionHandler.BASE.apply(new DumbException(), null, null);
            assertSame(actual, Responses.teapot());
            logs.assertContainsOnlyOnce(WARNING, """
                    For being an advisory fallback response, \
                    the status code 123 makes no sense.""");
        } finally {
            logs.stopRecording();
        }
    }
}
