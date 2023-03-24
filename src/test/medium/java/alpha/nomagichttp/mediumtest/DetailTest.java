package alpha.nomagichttp.mediumtest;

import alpha.nomagichttp.event.AbstractByteCountedStats;
import alpha.nomagichttp.event.RequestHeadReceived;
import alpha.nomagichttp.event.ResponseSent;
import alpha.nomagichttp.message.ByteBufferIterable;
import alpha.nomagichttp.message.ByteBufferIterator;
import alpha.nomagichttp.route.NoRouteFoundException;
import alpha.nomagichttp.testutil.AbstractRealTest;
import alpha.nomagichttp.testutil.IORunnable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.channels.Channel;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.ToLongBiFunction;

import static alpha.nomagichttp.HttpConstants.HeaderName.CONTENT_LENGTH;
import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.accepted;
import static alpha.nomagichttp.message.Responses.continue_;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.message.Responses.ok;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.LogRecords.rec;
import static alpha.nomagichttp.testutil.TestClient.CRLF;
import static alpha.nomagichttp.testutil.TestRequestHandlers.respondIsBodyEmpty;
import static alpha.nomagichttp.testutil.TestRequests.get;
import static alpha.nomagichttp.testutil.TestRequests.post;
import static alpha.nomagichttp.util.ScopedValues.channel;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.time.Duration.ZERO;
import static java.time.Duration.between;
import static java.time.Instant.now;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests concerning details of the server.<p>
 * 
 * Not so much "GET ..." and then expect "HTTP/1.1 200 ..." - the "casual"
 * exchange. Rather; perhaps make semi-weird calls and expect a particular
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
class DetailTest extends AbstractRealTest
{
    @Test
    void connection_reuse_standard() throws IOException {
        // Echo request body
        server().add("/", POST().apply(req ->
            text(req.body().toText())));
        
        final var resHead =
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 3"                       + CRLF + CRLF;
        
        Channel ch = client().openConnection();
        try (ch) {
            var res1 = client().writeReadTextUntil(post("ABC"), "ABC");
            assertThat(res1).isEqualTo(resHead + "ABC");
            
            var res2 = client().writeReadTextUntil(post("DEF"), "DEF");
            assertThat(res2).isEqualTo(resHead + "DEF");
        }
    }
    
    @Test
    void connection_reuse_chunking() throws IOException {
        server()
            .add("/ignore-body-and-trailers",
                POST().apply(req -> noContent()))
            .add("/echo-trailer",
                POST().apply(req -> {
                    var ignore = req.body().toText();
                    var val = req.trailers().delegate().firstValue("X-Trailer").get();
                    return text(val);
                }));
        
        var template = """
            POST /$1 HTTP/1.1
            Transfer-Encoding: chunked
            $2
            
            3
            abc
            0
            X-Trailer: $3
            
            """;
        
        Channel ch = client().openConnection();
        try (ch) {
            // Discard both the body and trailers
            var req1 = template.replace("$1", "ignore-body-and-trailers")
                               .replace("$2", "X-Extra: lots of fun tiz is")
                               .replace("$3", "blabla we don't care");
            var rsp1 = client().writeReadTextUntilNewlines(req1);
            assertThat(rsp1).isEqualTo(
                    "HTTP/1.1 204 No Content\r\n\r\n");
            
            // Can push a message over the same conn and echo the last trailer
            var req2 = template.replace("$1", "echo-trailer")
                               .replace("$2", "Connection: close")
                               .replace("$3", "I care!");
            var rsp2 = client().writeReadTextUntilEOS(req2);
            assertThat(rsp2).isEqualTo("""
                HTTP/1.1 200 OK\r
                Content-Length: 7\r
                Content-Type: text/plain; charset=utf-8\r
                Connection: close\r
                \r
                I care!""");
        }
    }
    
    @Test
    void request_body_discard_all() throws IOException {
        server().add("/", respondIsBodyEmpty());
        
        IORunnable exchange = () -> {
            String req = post("x".repeat(10)),
                   res = client().writeReadTextUntil(req, "false");
            
            assertThat(res).isEqualTo(
                "HTTP/1.1 200 OK"                         + CRLF +
                "Content-Length: 5"                       + CRLF +
                "Content-Type: text/plain; charset=utf-8" + CRLF + CRLF +
                
                "false");
        };
        
        Channel ch = client().openConnection();
        try (ch) {
            exchange.run();
            
            // The endpoint didn't read the body contents, i.e. auto-discarded.
            // If done correctly, we should be able to send a new request using the same connection:
            exchange.run();
        }
    }
    
    @Test
    void request_body_discard_half() throws IOException {
        // Previous test was pretty small, so why not roll with a large body
        final int length = 100_000,
                  midway = length / 2;
        
        server().add("/", POST().apply(req -> {
            // Read only half of the body
            var it = req.body().iterator();
            while (it.hasNext()) {
                var buf = it.next();
                int n = 0;
                while (n++ < midway) {
                    buf.get();
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
        
        Channel ch = client().openConnection();
        try (ch) {
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
            Trailer: This too is discarded
            
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
            "Content-Length: 3"                       + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF + CRLF +
            
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
        logRecorder().assertThatLogContainsOnlyOnce(
                // First ignored 100 Continue silently logged
                rec(DEBUG, "Ignoring repeated 100 (Continue)."),
                // But any more than that and level escalates
                rec(WARNING, "Ignoring repeated 100 (Continue)."));
    }
    
    @Test
    void response_unknownLength_bodyNonEmpty() throws IOException {
        server().add("/", GET().apply(req ->
            text("World").toBuilder()
                         .removeHeader(CONTENT_LENGTH)
                         .build()));
        String rsp = client().writeReadTextUntil(
            get(), "0\r\n\r\n");
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 200 OK\r
            Content-Type: text/plain; charset=utf-8\r
            Transfer-Encoding: chunked\r
            \r
            00000005\r
            World\r
            0\r\n\r
            """);
    }
    
    @Test
    void response_unknownLength_bodyEmpty() throws IOException {
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
            Transfer-Encoding: chunked\r
            Connection: close\r
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
            "Content-Length: 5"                       + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF + CRLF +
            
            "hello");
        
        // Responses.text(String, Request) uses charset from request
        var rsp2 = client().writeReadTextUntil(
            "GET / HTTP/1.1"                          + CRLF +
            "Accept: text/plain; charset=utf-8; q=0.9, " +
                    "text/plain; charset=iso-8859-1"  + CRLF + CRLF, "hello");
        assertThat(rsp2).isEqualTo(
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Length: 5"                       + CRLF +
            "Content-Type: text/plain; charset=iso-8859-1" + CRLF + CRLF +
            
            "hello");
    }
    
    @Test
    void event_RequestHeadReceived() throws IOException, InterruptedException {
        event_engine(RequestHeadReceived.class, (req, rsp) -> req.getBytes(US_ASCII).length);
    }
    
    @Test
    void event_ResponseSent() throws IOException, InterruptedException {
        event_engine(ResponseSent.class, (req, rsp) -> rsp.getBytes(US_ASCII).length);
    }
    
    private void event_engine(Class<?> eventType, ToLongBiFunction<String, String> exchToExpByteCnt)
            throws IOException, InterruptedException
    {
        // Save event locally
        var stats = new ArrayBlockingQueue<AbstractByteCountedStats>(1);
        server().events().on(eventType, (ev, thing, s) ->
                stats.add((AbstractByteCountedStats) s));
        
        final Instant then;
        final String req = "GET / HTTP/1.1" + CRLF;
        final String rsp;
        Channel ch = client().openConnection();
        try (ch) {
            client().write(req);
            then = now();
            rsp = client().writeReadTextUntilNewlines(CRLF);
        }
        
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 404 Not Found" + CRLF +
            "Content-Length: 0"      + CRLF + CRLF);
        
        assertThatServerErrorObservedAndLogged()
                .isExactlyInstanceOf(NoRouteFoundException.class);
        
        var s = stats.poll(1, SECONDS);
        // Can not compute this any earlier, directly after response.
        // No guarantee the event has happened at that point.
        final Duration expDur = between(then, now());
        
        assertThat(s.elapsedDuration()).isGreaterThanOrEqualTo(ZERO);
        assertThat(s.elapsedDuration()).isLessThanOrEqualTo(expDur);
        assertThat(s.byteCount()).isEqualTo(exchToExpByteCnt.applyAsLong(req + CRLF, rsp));
    }
}
