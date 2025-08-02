package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.testutil.IORunnable;
import alpha.nomagichttp.testutil.functional.AbstractRealTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static alpha.nomagichttp.core.mediumtest.util.TestRequestHandlers.respondIsBodyEmpty;
import static alpha.nomagichttp.core.mediumtest.util.TestRequests.post;
import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.accepted;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.message.Responses.status;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.TestConstants.CRLF;
import static java.lang.System.Logger.Level.DEBUG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;

///  Tests of request body discarding.
/// 
/// @author Martin Andersson (webmaster at martinandersson.com)
final class DiscardTest extends AbstractRealTest
{
    @Nested
    class ConnectionReused {
        @Test
        void normal() throws IOException {
            // Echo request body
            server().add("/", POST().apply(req ->
                text(req.body().toText())));
            
            final var resHead =
                "HTTP/1.1 200 OK"                         + CRLF +
                "Content-Type: text/plain; charset=utf-8" + CRLF +
                "Content-Length: 3"                       + CRLF + CRLF;
            
            try (var _ = client().openConnection()) {
                var res1 = client().writeReadTextUntil(post("ABC"), "ABC");
                assertThat(res1).isEqualTo(resHead + "ABC");
                
                var res2 = client().writeReadTextUntil(post("DEF"), "DEF");
                assertThat(res2).isEqualTo(resHead + "DEF");
            }
        }
        
        @Test
        void discardWhole() throws IOException {
            server().add("/",
                    // Does not consume the body
                    respondIsBodyEmpty());
            
            IORunnable exchange = () -> {
                String req = post("x".repeat(10)),
                       res = client().writeReadTextUntil(req, "false");
                
                assertThat(res).isEqualTo(
                    "HTTP/1.1 200 OK"                         + CRLF +
                    "Content-Type: text/plain; charset=utf-8" + CRLF +
                    "Content-Length: 5"                       + CRLF + CRLF +
                    
                    "false");
            };
            
            try (var _ = client().openConnection()) {
                exchange.run();
                // Body auto-discarded. This is using the same connection:
                exchange.run();
            }
        }
        
        @Test
        void discardHalf() throws IOException {
            final int length = 100,
                      midway = length / 2;
            
            server().add("/", POST().apply(req -> {
                // Read only half of the body
                int n = 0;
                var it = req.body().iterator();
                while (it.hasNext() && n < midway) {
                    var buf = it.next();
                    while (buf.hasRemaining()) {
                        buf.get();
                        ++n;
                    }
                }
                return accepted();
            }));
            
            IORunnable exchange = () -> {
                String req = post("x".repeat(length)),
                       res = client().writeReadTextUntilNewlines(req);
                
                assertThat(res).isEqualTo(
                    "HTTP/1.1 202 Accepted" + CRLF +
                    "Content-Length: 0"     + CRLF + CRLF);
            };
            
            try (var _ = client().openConnection()) {
                exchange.run();
                exchange.run();
            }
        }
    }
    
    @Nested
    class ConnectionClosed {
        @Test
        void remainingBytesLarge() throws IOException, InterruptedException {
            server().add("/", GET().apply(_ -> {
                var rsp = noContent();
                // We're testing the server's native connection management
                // without close-commands from the app.
                assertFalse(rsp.headers().hasConnectionClose());
                return rsp;
            }));
            try (var _ = client().openConnection()) {
                var rsp = client().writeReadTextUntilEOS("""
                    GET / HTTP/1.1
                    Content-Length: 666
                    
                    ...""");
                assertThat(rsp).isEqualTo("""
                    HTTP/1.1 204 No Content\r
                    Connection: close\r
                    \r
                    """);
                logRecorder().assertContainsOnlyOnce(DEBUG, """
                    Setting "Connection: close" because a satanic volume \
                    of request data is remaining.""");
                assertAwaitClosingChild();
            }
        }
        
        @Test
        void remainingBytesUnknown() throws IOException, InterruptedException {
            server().add("/", GET().apply(_ -> {
                var rsp = status(499, "Naughty Request");
                assertFalse(rsp.headers().hasConnectionClose());
                return rsp;
            }));
            try (var _ = client().openConnection()) {
                // "Expect: 100-continue" makes to difference for the test case
                var rsp = client().writeReadTextUntilEOS("""
                    GET / HTTP/1.1
                    Expect: 100-continue
                    Transfer-Encoding: chunked
                    
                    ...""");
                assertThat(rsp).isEqualTo("""
                    HTTP/1.1 499 Naughty Request\r
                    Connection: close\r
                    Content-Length: 0\r
                    \r
                    """);
                logRecorder().assertContainsOnlyOnce(DEBUG, """
                    Setting "Connection: close" because unknown length \
                    of request data is remaining.""");
                assertAwaitClosingChild();
            }
        }
    }
}
