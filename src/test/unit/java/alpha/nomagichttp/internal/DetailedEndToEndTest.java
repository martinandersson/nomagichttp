package alpha.nomagichttp.internal;

import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.handler.RequestHandlers;
import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Consumer;
import java.util.function.Function;

import static alpha.nomagichttp.handler.RequestHandlers.POST;
import static alpha.nomagichttp.testutil.ClientOperations.CRLF;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Detailed end-to-end tests that target specific details of the API.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see SimpleEndToEndTest
 */
class DetailedEndToEndTest extends AbstractEndToEndTest
{
    private static final String
            IS_BODY_EMPTY = "/is-body-empty",
            ECHO_BODY     = "/echo-body";
    
    @BeforeAll
    static void installHandlers() {
        Function<Request, CompletionStage<Response>>
                isBodyEmpty = req -> Responses.text(String.valueOf(req.body().isEmpty())).completedStage(),
                echoBody    = req -> req.body().toText().thenApply(Responses::text);
        
        server().add(IS_BODY_EMPTY, POST().apply(isBodyEmpty));
        server().add(ECHO_BODY,     POST().apply(echoBody));
    }
    
    @Test
    void empty_request_body() throws IOException {
        String res = client().writeRead(requestWithBody(IS_BODY_EMPTY, ""), "true");
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 200 OK" + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 4" + CRLF + CRLF +
            
            "true");
    }
    
    @Test
    void connection_reuse() throws IOException {
        final String resHead =
            "HTTP/1.1 200 OK" + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 3" + CRLF + CRLF;
        
        try (Channel ch = client().openConnection()) {
            String res1 = client().writeRead(requestWithBody(ECHO_BODY, "ABC"), "ABC");
            assertThat(res1).isEqualTo(resHead + "ABC");
            
            String res2 = client().writeRead(requestWithBody(ECHO_BODY, "DEF"), "DEF");
            assertThat(res2).isEqualTo(resHead + "DEF");
        }
    }
    
    @Test
    void request_body_discard_all() throws IOException {
        try (Channel ch = client().openConnection()) {
            String req = requestWithBody(IS_BODY_EMPTY, "x".repeat(10)),
                   res = client().writeRead(req, "false");
            
            assertThat(res).isEqualTo(
                "HTTP/1.1 200 OK" + CRLF +
                "Content-Type: text/plain; charset=utf-8" + CRLF +
                "Content-Length: 5" + CRLF + CRLF +
                
                "false");
            
            // The "/is-body-empty" endpoint didn't read the body contents, i.e. auto-discarded.
            // If done correctly, we should be be able to send a new request using the same connection:
            empty_request_body();
        }
    }
    
    @Test
    void request_body_discard_half() throws IOException {
        // Previous test was pretty small, so why not roll with a large body
        final int length = 100_000,
                  midway = length / 2;
        
        RequestHandler discardMidway = RequestHandlers.POST().accept((req) ->
            req.body().subscribe(
                new AfterByteTargetStop(midway, Flow.Subscription::cancel)));
        
        server().add("/discard-midway", discardMidway);
        
        try (Channel ch = client().openConnection()) {
            String req = requestWithBody("/discard-midway", "x".repeat(length)),
                   res = client().writeRead(req);
            
            assertThat(res).isEqualTo(
                "HTTP/1.1 202 Accepted" + CRLF +
                "Content-Length: 0" + CRLF + CRLF);
            
            empty_request_body();
        }
    }
    
    @Test
    void request_body_subscriber_crash() throws IOException {
        doNotAssertNormalFinish();
        
        RequestHandler crashAfterOneByte = RequestHandlers.POST().accept(req ->
            req.body().subscribe(
                new AfterByteTargetStop(1, subscriptionIgnored -> {
                    throw new RuntimeException("Oops."); })));
        
        server().add("/body-subscriber-crash", crashAfterOneByte);
        
        try (Channel ch = client().openConnection()) {
            String req = requestWithBody("/body-subscriber-crash", "Hello"),
                   res = client().writeRead(req);
            
            assertThat(res).isEqualTo(
                "HTTP/1.1 500 Internal Server Error" + CRLF +
                "Content-Length: 0" + CRLF + CRLF);
            
            assertThat(client().drain()).isEmpty();
            assertThat(ch.isOpen()).isFalse();
            
            /*
             * What just happened?
             * 
             * 1) On the server side, OnErrorCloseReadStream caught the error
             * and closed the input stream. Not the output stream and not the
             * child channel.
             * 
             * 2) Error propagates and eventually hits the default exception
             * handler, which produces Responses.internalServerError() which
             * specifies "mustCloseAfterWrite()".
             * 
             * 2) HttpExchange notices the request to close and calls
             * ChannelOperations.orderlyClose() which before closing the child
             * first shuts down the input- and output streams.
             * 
             * 3) This then gives our client an EOS to which we react by closing
             * the channel on our side. See ClientOperations.writeRead().
             * 
             * TODO: When we have a ConnectionLifeCycleTest, make reference.
             */
        }
    }
    
    private static String requestWithBody(String requestTarget, String body) {
        return "POST " + requestTarget + " HTTP/1.1" + CRLF +
               "Accept: text/plain; charset=utf-8" + CRLF +
               "Content-Length: " + body.length() + CRLF + CRLF +
               body;
    }
    
    private static class AfterByteTargetStop implements Flow.Subscriber<PooledByteBufferHolder>
    {
        private final long byteTarget;
        private final Consumer<Flow.Subscription> how;
        private Flow.Subscription subscription;
        private long read;
        
        AfterByteTargetStop(long byteTarget, Consumer<Flow.Subscription> how) {
            this.byteTarget = byteTarget;
            this.how = how;
        }
        
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            (this.subscription = subscription).request(Long.MAX_VALUE);
        }
        
        @Override
        public void onNext(PooledByteBufferHolder item) {
            assert read < byteTarget;
            ByteBuffer b = item.get();
            while (b.hasRemaining()) {
                b.get();
                if (++read == byteTarget) {
                    how.accept(subscription);
                    break;
                }
            }
            
            item.release();
        }
        
        @Override
        public void onError(Throwable throwable) {
            // Empty
        }
        
        @Override
        public void onComplete() {
            // Empty
        }
    }
}
