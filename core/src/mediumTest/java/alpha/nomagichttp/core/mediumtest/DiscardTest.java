package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.message.HeaderParseException;
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
        
        @Test
        void discardTrailers() throws IOException {
            server()
                .add("/discard", POST().apply(req -> {
                    var _ = req.body().toText();
                    return noContent();
                }))
                .add("/echo", POST().apply(req -> {
                    // Still must consume the body before trailers lol
                    var _ = req.body().toText();
                    var trailer = req.trailers().firstValue("My-Trailer").get();
                    return text(trailer);
                }));
            var template = """
                POST $1 HTTP/1.1
                Transfer-Encoding: chunked
                $2
                My-Trailer: $3
                
                3
                abc
                0
                My-Trailer: $4
                
                """;
            try (var _ = client().openConnection()) {
                var req1 = template.replace("$1", "/discard")
                                   .replace("$2", "My-Dummy: dummy")
                                   .replace("$3", "dummy")
                                   .replace("$4", "dummy");
                var rsp1 = client().writeReadTextUntilNewlines(req1);
                logRecorder().assertContainsOnlyOnce(DEBUG,
                        "Discarding request trailers");
                assertThat(rsp1).isEqualTo(
                        "HTTP/1.1 204 No Content\r\n\r\n");
                // Can push a message over the same conn and echo the last trailer
                var req2 = template.replace("$1", "/echo")
                                   .replace("$2", "Connection: close")
                                   .replace("$3", "Don't pick from header")
                                   .replace("$4", "Hello");
                var rsp2 = client().writeReadTextUntilEOS(req2);
                assertThat(rsp2).isEqualTo("""
                    HTTP/1.1 200 OK\r
                    Content-Type: text/plain; charset=utf-8\r
                    Connection: close\r
                    Content-Length: 5\r
                    \r
                    Hello""");
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
        
        @Test
        void badTrailerDuringDiscard() throws IOException {
            server().add("/", POST().apply(req ->
                    text(req.body().toText())));
            var rsp = client().writeReadTextUntilEOS("""
                    POST / HTTP/1.1
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
