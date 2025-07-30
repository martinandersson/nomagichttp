package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.event.AbstractByteCountedStats;
import alpha.nomagichttp.event.RequestHeadReceived;
import alpha.nomagichttp.event.ResponseSent;
import alpha.nomagichttp.route.NoRouteFoundException;
import alpha.nomagichttp.testutil.IORunnable;
import alpha.nomagichttp.testutil.functional.AbstractRealTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;

import static alpha.nomagichttp.core.mediumtest.util.TestRequestHandlers.respondIsBodyEmpty;
import static alpha.nomagichttp.core.mediumtest.util.TestRequests.post;
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
import static java.lang.System.nanoTime;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.TimeUnit.SECONDS;
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
    
    /**
     * @see #interimResponseIgnoredForOldClient()
     * @see MessageTest#expect100Continue_onFirstBodyAccess()
     */
    @Nested
    class Expect100Continue {
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
        
        @Test
        void repeatedIgnored() throws IOException {
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
            
            // Logging specified in JavaDoc of ClientChannel.write()
            logRecorder()
                .assertContainsOnlyOnce(
                    // First ignored 100 Continue silently logged
                    DEBUG, "Ignoring repeated 100 (Continue).")
                .assertRemove(
                    // But any more than that and level escalates
                    WARNING, "Ignoring repeated 100 (Continue).");
        }
    }
    
    @Nested
    class Event {
        @Test
        void RequestHeadReceived() throws IOException, InterruptedException {
            engine(RequestHeadReceived.class);
        }
        
        @Test
        void ResponseSent() throws IOException, InterruptedException {
            engine(ResponseSent.class);
        }
        
        private void engine(Class<?> eventType)
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
            
            try (var _ = client().openConnection()) {
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
    
    /**
     * @see Expect100Continue
     */
    @Test
    void interimResponseIgnoredForOldClient()
            throws IOException, InterruptedException
    {
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
