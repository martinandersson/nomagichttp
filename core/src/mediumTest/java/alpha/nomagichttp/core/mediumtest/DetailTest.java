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
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.TestConstants.CRLF;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests concerning details of the connection or server.<p>
 * 
 * Not so much "GET ..." and then expect "HTTP/1.1 200 ..." — the casual
 * exchange. Rather, perhaps make semi-weird calls and expect a particular
 * server behavior.<p>
 * 
 * Many tests will likely require a fine-grained control of the client and do
 * lots of assertions on the server's log.<p>
 * 
 * Note: life-cycle details ought to go to {@link ClientLifeCycleTest} or
 * {@link ServerLifeCycleTest}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class DetailTest extends AbstractRealTest
{
    @Nested
    class ConnectionReuse {
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
    }
    
    @Nested
    class RequestBodyDiscard {
        @Test
        void whole() throws IOException {
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
        void half() throws IOException {
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
    
    // "Accept: text/plain; charset=utf-8; q=0.9, text/plain; charset=iso-8859-1"
    // ISO 8859 wins, coz implicit q = 1
    @Test
    void charsetPreferenceThroughQ() throws IOException {
        server().add("/", GET().apply(req ->
            text("hello", req)));
        
        // Default is UTF-8
        // (important to keep this here as we need to make sure the next test
        //  pass for the right reasons)
        var rsp1 = client().writeReadTextUntil(
            "GET / HTTP/1.1"                          + CRLF + CRLF, "hello");
        assertThat(rsp1).isEqualTo(
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 5"                       + CRLF + CRLF +
            
            "hello");
        
        // Responses.text(String, Request) uses charset from request
        var rsp2 = client().writeReadTextUntil(
            "GET / HTTP/1.1"                          + CRLF +
            "Accept: text/plain; charset=utf-8; q=0.9, " +
                    "text/plain; charset=iso-8859-1"  + CRLF + CRLF, "hello");
        assertThat(rsp2).isEqualTo(
            "HTTP/1.1 200 OK"                              + CRLF +
            "Content-Type: text/plain; charset=iso-8859-1" + CRLF +
            "Content-Length: 5"                            + CRLF + CRLF +
            
            "hello");
    }
}
