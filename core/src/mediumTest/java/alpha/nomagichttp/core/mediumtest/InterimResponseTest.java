package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.handler.ResponseRejectedException;
import alpha.nomagichttp.testutil.functional.AbstractRealTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.accepted;
import static alpha.nomagichttp.message.Responses.continue_;
import static alpha.nomagichttp.message.Responses.processing;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.TestConstants.CRLF;
import static alpha.nomagichttp.util.ScopedValues.channel;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;
import static org.assertj.core.api.Assertions.assertThat;

///  Tests of 1XX interim responses.
/// 
/// @author Martin Andersson (webmaster at martinandersson.com)
final class InterimResponseTest extends AbstractRealTest
{
    @Nested
    class Expect100Continue {
        // TODO: Make compatibility tests.
        //       Lots of so called HTTP clients will likely not be able to receive
        //       multiple responses, just ignore them.
        @Test
        void onFirstBodyAccess() throws IOException {
            server().add("/", POST().apply(req ->
                text(req.body().toText())));
            
            String req = "POST / HTTP/1.1" + CRLF +
                "Expect: 100-continue"     + CRLF +
                "Content-Length: 2"        + CRLF +
                "Content-Type: text/plain" + CRLF + CRLF +
                
                "Hi";
            
            String rsp = client().writeReadTextUntil(req, "Hi");
            
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 100 Continue"                   + CRLF + CRLF +
                
                "HTTP/1.1 200 OK"                         + CRLF +
                "Content-Type: text/plain; charset=utf-8" + CRLF +
                "Content-Length: 2"                       + CRLF + CRLF +
                
                "Hi");
        }
        
        @Test
        void immediatelyByConfig() throws IOException {
            usingConfiguration()
                .immediatelyContinueExpect100(true);
            server().add("/",
                // Request body doesn't matter
                GET().apply(_ -> text("end")));
            String rsp = client().writeReadTextUntil(
                "GET / HTTP/1.1"                          + CRLF + 
                "Expect: 100-continue"                    + CRLF + CRLF, "end");
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 100 Continue"                   + CRLF + CRLF +
                
                "HTTP/1.1 200 OK"                         + CRLF +
                "Content-Type: text/plain; charset=utf-8" + CRLF +
                "Content-Length: 3"                       + CRLF + CRLF +
                
                "end");
        }
    }
    
    @Nested
    class Ignored {
        @Test
        void repeated() throws IOException {
            server().add("/", GET().apply(_ -> {
                // In response to a GET request without Expect header nor body
                // (application gets what application wants)
                var ch = channel();
                ch.write(continue_());
                ch.write(continue_());
                ch.write(continue_());
                return accepted();
            }));
            
            String req = "GET / HTTP/1.1" + CRLF + CRLF,
                   rsp = client().writeReadTextUntil(
                             req, "Content-Length: 0" + CRLF + CRLF);
            
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 100 Continue"  + CRLF + CRLF +
                
                "HTTP/1.1 202 Accepted"  + CRLF +
                "Content-Length: 0"      + CRLF + CRLF);
            
            logRecorder()
                .assertContainsOnlyOnce(
                    // First ignored 100 Continue silently logged
                    DEBUG, "Ignoring repeated 100 (Continue).")
                .assertRemove(
                    // But any more than that and level escalates
                    WARNING, "Ignoring repeated 100 (Continue).");
        }
        
        @Test
        void http_1_0() throws IOException, InterruptedException {
            server().add("/", GET().apply(_ -> {
                channel().write(processing()); // <-- rejected
                return text("Done!");
            }));
            // ... because "HTTP/1.0"
            String rsp = client().writeReadTextUntil(
                "GET / HTTP/1.0"                          + CRLF + CRLF, "Done!");
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 200 OK"                         + CRLF +
                "Content-Type: text/plain; charset=utf-8" + CRLF +
                "Connection: close"                       + CRLF +
                "Content-Length: 5"                       + CRLF + CRLF +
                
                "Done!");
            logRecorder().assertAwait(DEBUG,
                "Ignoring 1XX (Informational) response for HTTP/1.0 client.");
        }
    }
    
    @Test
    void rejected() throws IOException {
        usingConfiguration()
            .discardRejectedInformational(false);
        server().add("/", GET().apply(_ -> {
            channel().write(processing());
            return null; }));
        String rsp = client().writeReadTextUntilNewlines(
            "GET / HTTP/1.0" + CRLF + CRLF);
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 426 Upgrade Required\r
            Upgrade: HTTP/1.1\r
            Connection: upgrade, close\r
            Content-Length: 0\r\n\r\n""");
        assertThat(pollServerExceptionNow())
            .isExactlyInstanceOf(ResponseRejectedException.class)
            .hasMessage("HTTP/1.0 client does not accept 1XX (Informational) responses.")
            .hasNoCause()
            .hasNoSuppressedExceptions();
        logRecorder()
            .assertNoProblem()
            .assertRemove(DEBUG, """
                Setting "Connection: close" because HTTP/1.0 does not \
                support a persistent connection.""");
    }
}
