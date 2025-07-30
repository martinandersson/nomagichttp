package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.event.AbstractByteCountedStats;
import alpha.nomagichttp.event.RequestHeadReceived;
import alpha.nomagichttp.event.ResponseSent;
import alpha.nomagichttp.route.NoRouteFoundException;
import alpha.nomagichttp.testutil.functional.AbstractRealTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;

import static alpha.nomagichttp.testutil.TestConstants.CRLF;
import static java.lang.System.nanoTime;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

///  Tests of server-emitted events.
final class EventTest extends AbstractRealTest
{
    @Test
    void requestHeadReceived() throws IOException, InterruptedException {
        engine(RequestHeadReceived.class);
    }
    
    @Test
    void responseSent() throws IOException, InterruptedException {
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
