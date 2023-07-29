package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.event.AbstractByteCountedStats;
import alpha.nomagichttp.event.RequestHeadReceived;
import alpha.nomagichttp.event.ResponseSent;
import alpha.nomagichttp.message.ByteBufferIterable;
import alpha.nomagichttp.message.ByteBufferIterator;
import alpha.nomagichttp.route.NoRouteFoundException;
import alpha.nomagichttp.testutil.IORunnable;
import alpha.nomagichttp.testutil.functional.AbstractRealTest;
import alpha.nomagichttp.util.ByteBufferIterables;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

import static alpha.nomagichttp.core.mediumtest.util.TestRequestHandlers.respondIsBodyEmpty;
import static alpha.nomagichttp.core.mediumtest.util.TestRequests.get;
import static alpha.nomagichttp.core.mediumtest.util.TestRequests.post;
import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.accepted;
import static alpha.nomagichttp.message.Responses.continue_;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.message.Responses.ok;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.TestConstants.CRLF;
import static alpha.nomagichttp.util.ByteBuffers.asciiBytes;
import static alpha.nomagichttp.util.ScopedValues.channel;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;
import static java.lang.System.nanoTime;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests concerning details of the server.<p>
 * 
 * Not so much "GET ..." and then expect "HTTP/1.1 200 ..." — the casual
 * exchange. Rather, perhaps make semi-weird calls and expect a particular
 * server behavior. You know, details.<p>
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
    // TODO: Extract ConnectionTest (includes discarding)
    
    @Test
    void connection_reuse_standard() throws IOException {
        // Echo request body
        server().add("/", POST().apply(req ->
            text(req.body().toText())));
        
        final var resHead =
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 3"                       + CRLF + CRLF;
        
        try (var conn = client().openConnection()) {
            var res1 = client().writeReadTextUntil(post("ABC"), "ABC");
            assertThat(res1).isEqualTo(resHead + "ABC");
            
            var res2 = client().writeReadTextUntil(post("DEF"), "DEF");
            assertThat(res2).isEqualTo(resHead + "DEF");
        }
    }
    
    @Test
    void connection_reuse_chunking() throws IOException {
        server()
            .add("/discard-body", POST().apply(req -> {
                var discard = req.body().toText();
                return noContent();
            }))
            .add("/echo-trailer", POST().apply(req -> {
                // Still must consume the body before trailers lol
                var discard = req.body().toText();
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
        try (var conn = client().openConnection()) {
            var req1 = template.replace("$1", "/discard-body")
                               .replace("$2", "My-Dummy: dummy")
                               .replace("$3", "dummy")
                               .replace("$4", "dummy");
            var rsp1 = client().writeReadTextUntilNewlines(req1);
            assertThat(rsp1).isEqualTo(
                    "HTTP/1.1 204 No Content\r\n\r\n");
            // Can push a message over the same conn and echo the last trailer
            var req2 = template.replace("$1", "/echo-trailer")
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
    
    @Test
    void request_body_discard_all() throws IOException {
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
        
        try (var conn = client().openConnection()) {
            exchange.run();
            // Body auto-discarded. This is using the same connection:
            exchange.run();
        }
    }
    
    @Test
    void request_body_discard_half() throws IOException {
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
        
        try (var conn = client().openConnection()) {
            exchange.run();
            exchange.run();
        }
    }
    
    @Test
    void request_body_discard_chunked() throws IOException {
        server().add("/",
            GET().apply(req -> noContent()));
        var rsp = client().writeReadTextUntilEOS("""
            GET / HTTP/1.1
            Transfer-Encoding: chunked
            Connection: close
            
            0
            Blabla: This is also discarded
            
            """);
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 204 No Content\r
            Connection: close\r\n\r\n""");
    }
    
    @Test
    void expect100Continue_immediatelyThroughConfig() throws IOException {
        usingConfiguration()
            .immediatelyContinueExpect100(true);
        server().add("/",
            // Request body doesn't matter
            GET().apply(req -> text("end")));
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
    
    /** Also see {@link MessageTest#expect100Continue_onFirstBodyAccess()} */
    @Test
    void expect100Continue_repeatedIgnored() throws IOException {
        server().add("/", GET().apply(req -> {
            // In response to GET without Expect and no body - app gets what app wants.
            var ch = channel();
            ch.write(continue_());
            ch.write(continue_());
            ch.write(continue_());
            return accepted();
        }));
        
        String req = "GET / HTTP/1.1" + CRLF + CRLF;
        String rsp = client().writeReadTextUntil(req, "Content-Length: 0" + CRLF + CRLF);
        
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 100 Continue"  + CRLF + CRLF +
            
            "HTTP/1.1 202 Accepted"  + CRLF +
            "Content-Length: 0"      + CRLF + CRLF);
        
        // Logging specified in JavaDoc of ClientChannel.write()
        logRecorder()
            .assertContainsOnlyOnce(
                // First ignored 100 Continue silently logged
                DEBUG, "Ignoring repeated 100 (Continue).")
            .assertRemove(
                // But any more than that and level escalates
                WARNING, "Ignoring repeated 100 (Continue).");
    }
    
    @Test
    void response_unknownLength_bodyNonEmpty() throws IOException {
        var empty = ByteBuffer.allocate(0);
        var items = List.of(asciiBytes("World"), empty);
        var body = ByteBufferIterables.ofSupplier(items.iterator()::next);
        server().add("/", GET().apply(req ->
            ok(body)));
        String rsp = client().writeReadTextUntil(
            get(), "0\r\n\r\n");
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 200 OK\r
            Content-Type: application/octet-stream\r
            Transfer-Encoding: chunked\r
            \r
            00000005\r
            World\r
            0\r\n\r
            """);
    }
    
    @Test
    void response_unknownLength_bodyEmpty() throws IOException {
        // TODO: A variant without client setting "Connection: close"
        var empty = new ByteBufferIterable() {
            public ByteBufferIterator iterator() {
                return ByteBufferIterator.Empty.INSTANCE;
            }
            public long length() {
                return -1;
            }
        };
        server().add("/", GET().apply(req ->
            ok(empty)));
        String rsp = client().writeReadTextUntilEOS(
            get("Connection: close"));
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 200 OK\r
            Content-Type: application/octet-stream\r
            Connection: close\r
            Transfer-Encoding: chunked\r
            \r
            0\r\n\r
            """);
    }
    
    // And what about @Test request_unknownLength() ?
    // The request must specify Content-Length, or chunked encoding.
    // Only the server's response may have unknown length terminated by
    // connection close (RFC 7230 §3.3.3 Message Body Length; bullet item 6).
    
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
    
    @Test
    void event_RequestHeadReceived() throws IOException, InterruptedException {
        event_engine(RequestHeadReceived.class);
    }
    
    @Test
    void event_ResponseSent() throws IOException, InterruptedException {
        event_engine(ResponseSent.class);
    }
    
    private void event_engine(Class<?> eventType)
            throws IOException, InterruptedException
    {
        // Save event locally
        var eventSink = new ArrayBlockingQueue<AbstractByteCountedStats>(1);
        server().events().on(eventType, (ev, thing, s) ->
                eventSink.add((AbstractByteCountedStats) s));
        
        final long   beforeReq,
                     beforeRsp,
                     afterRsp;
        final String req = "GET / HTTP/1.1",
                     rsp;
        
        try (var conn = client().openConnection()) {
            beforeReq = nanoTime();
            client().write(req);
            beforeRsp = nanoTime();
            rsp = client().writeReadTextUntilNewlines(CRLF + CRLF);
            afterRsp = nanoTime();
        }
        
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 404 Not Found" + CRLF +
            "Content-Length: 0"      + CRLF + CRLF);
        
        assertAwaitHandledAndLoggedExc()
                .isExactlyInstanceOf(NoRouteFoundException.class);
        
        var stats = eventSink.poll(1, SECONDS);
        assert stats != null;
        
        final long expMaxDur = afterRsp - (
            eventType == RequestHeadReceived.class ? beforeReq : beforeRsp);
        assertThat(stats.elapsedNanos()).isBetween(0L, expMaxDur);
        
        final long expLen = eventType == RequestHeadReceived.class ?
            lengthOf(req + CRLF + CRLF) :lengthOf(rsp);
        assertThat(stats.byteCount()).isEqualTo(expLen);
    }
    
    private static long lengthOf(String message) {
        return message.getBytes(US_ASCII).length;
    }
}
