package alpha.nomagichttp.handler;

import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.testutil.LogRecorder;
import org.junit.jupiter.api.Test;

import static java.lang.System.Logger.Level.WARNING;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests of {@link ErrorHandler#BASE}.<p>
 * 
 * This class is thin on test cases, as the base handler does not contain much
 * logic, and most "error cases" are tested in medium-sized tests.
 */
public final class ErrorHandlerTest {
    @Test
    void weirdResponseFromExceptionClass() {
        class DumbException extends Exception implements WithResponse {
            public Response getResponse() {
                return Responses.status(123, "Interim!");
            }
        }
        var logs = LogRecorder.startRecording();
        try {
            var actual = ErrorHandler.BASE.apply(new DumbException(), null, null);
            assertSame(actual, Responses.teapot());
            logs.assertContainsOnlyOnce(WARNING, """
                    For being an advisory fallback response, \
                    the status code 123 makes no sense.""");
        } finally {
            logs.stopRecording();
        }
    }
}
