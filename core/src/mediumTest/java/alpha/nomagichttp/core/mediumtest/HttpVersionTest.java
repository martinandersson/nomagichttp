package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.message.HttpVersionTooNewException;
import alpha.nomagichttp.message.HttpVersionTooOldException;
import alpha.nomagichttp.testutil.functional.AbstractRealTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;

import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.testutil.TestConstants.CRLF;
import static org.assertj.core.api.Assertions.assertThat;

/// Tests of HTTP version.
final class HttpVersionTest extends AbstractRealTest
{
    // Some newer versions are currently not supported
    @ParameterizedTest
    @CsvSource({"2,true", "3,true", "999,false"})
    void tooNew(String version, boolean hasLiteral)
            throws IOException, InterruptedException
    {
        server();
        String rsp = client().writeReadTextUntilNewlines(
            "GET / HTTP/" + version                   + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 505 HTTP Version Not Supported" + CRLF +
            "Connection: close"                       + CRLF +
            "Content-Length: 0"                       + CRLF + CRLF);
        var throwable = assertThat(pollServerException())
            .isExactlyInstanceOf(HttpVersionTooNewException.class)
            .hasNoSuppressedExceptions();
        if (hasLiteral) {
            throwable.hasMessage(null)
                     .hasNoCause();
        } else {
            throwable.hasMessage("java.lang.IllegalArgumentException: 999:")
                     .hasNoSuppressedExceptions()
                     .cause()
                         .isExactlyInstanceOf(IllegalArgumentException.class)
                         .hasMessage("999:")
                         .hasNoSuppressedExceptions()
                         .hasNoCause();
        }
    }
    
    @Nested
    class TooOld {
        // By default, server rejects clients older than HTTP/1.0
        @ParameterizedTest
        @CsvSource({"-1.23,false", "0.5,false", "0.8,false", "0.9,true"})
        void lessThan1_0(String version, boolean hasLiteral)
                throws IOException, InterruptedException
        {
            server();
            String rsp = client().writeReadTextUntilNewlines(
                "GET / HTTP/" + version         + CRLF + CRLF);
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 426 Upgrade Required" + CRLF +
                "Upgrade: HTTP/1.1"             + CRLF +
                "Connection: upgrade, close"    + CRLF +
                "Content-Length: 0"             + CRLF + CRLF);
            var throwable = assertThat(pollServerException())
                .isExactlyInstanceOf(HttpVersionTooOldException.class)
                .hasNoSuppressedExceptions();
            if (hasLiteral) {
                throwable.hasMessage(null)
                         .hasNoSuppressedExceptions()
                         .hasNoCause();
            } else {
                var v = version.replace(".", ":");
                throwable.hasMessage("java.lang.IllegalArgumentException: " + v)
                         .cause()
                         .isExactlyInstanceOf(IllegalArgumentException.class)
                         .hasMessage(v)
                         .hasNoSuppressedExceptions()
                         .hasNoCause();
            }
        }
        
        // Server may be configured to reject old clients
        @Test
        void eq1_0()
                throws IOException, InterruptedException
        {
            usingConfiguration()
                .minHttpVersion(HTTP_1_1);
            server();
            String rsp = client().writeReadTextUntilNewlines(
                "GET /not-found HTTP/1.0"       + CRLF + CRLF);
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 426 Upgrade Required" + CRLF +
                "Upgrade: HTTP/1.1"             + CRLF +
                "Connection: upgrade, close"    + CRLF +
                "Content-Length: 0"             + CRLF+ CRLF);
            assertThat(pollServerException())
                .isExactlyInstanceOf(HttpVersionTooOldException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage(null);
        }
    }
}
