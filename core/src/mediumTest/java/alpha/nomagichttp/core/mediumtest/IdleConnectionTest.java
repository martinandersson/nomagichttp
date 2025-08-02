package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.IdleConnectionException;
import alpha.nomagichttp.testutil.functional.AbstractRealTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static alpha.nomagichttp.testutil.TestConstants.CRLF;
import static alpha.nomagichttp.testutil.functional.Environment.isGitHubActions;
import static alpha.nomagichttp.testutil.functional.Environment.isJitPack;
import static java.lang.System.Logger.Level.DEBUG;
import static java.time.Duration.ofMillis;
import static org.assertj.core.api.Assertions.assertThat;

/// Tests of idling connection.
/// 
/// @author Martin Andersson (webmaster at martinandersson.com)
final class IdleConnectionTest extends AbstractRealTest
{
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
