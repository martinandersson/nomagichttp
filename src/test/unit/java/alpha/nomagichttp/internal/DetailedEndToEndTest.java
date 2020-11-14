package alpha.nomagichttp.internal;

import alpha.nomagichttp.handler.Handler;
import alpha.nomagichttp.handler.Handlers;
import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Function;

import static alpha.nomagichttp.handler.Handlers.POST;
import static alpha.nomagichttp.internal.ClientOperations.CRLF;
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
                isBodyEmpty = req -> Responses.ok(String.valueOf(req.body().isEmpty())).asCompletedStage(),
                echoBody    = req -> req.body().get().toText().thenApply(Responses::ok);
        
        addHandler(IS_BODY_EMPTY, POST().apply(isBodyEmpty));
        addHandler(ECHO_BODY,     POST().apply(echoBody));
    }
    
    @Test
    void empty_request_body() throws IOException, InterruptedException {
        String res = client().writeRead(requestWithBody(IS_BODY_EMPTY, ""), "true");
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 200 OK" + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 4" + CRLF + CRLF +
            
            "true");
    }
    
    @Test
    void connection_reuse() throws IOException, InterruptedException {
        final String resHead =
            "HTTP/1.1 200 OK" + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 3" + CRLF + CRLF;
        
        client().openConnection();
        
        try {
            String res1 = client().writeRead(requestWithBody(ECHO_BODY, "ABC"), "ABC");
            assertThat(res1).isEqualTo(resHead + "ABC");
            
            String res2 = client().writeRead(requestWithBody(ECHO_BODY, "DEF"), "DEF");
            assertThat(res2).isEqualTo(resHead + "DEF");
        } finally {
            client().closeConnection();
        }
    }
    
    @Test
    void discard_request_body_full() throws IOException, InterruptedException {
        client().openConnection();
        
        try {
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
        } finally {
            client().closeConnection();
        }
    }
    
    @Test
    void discard_request_body_half() throws IOException, InterruptedException {
        // Previous test was pretty small, so why not roll with a large body
        final int length = 100_000,
                  midway = length / 2;
        
        Handler discardMidway = Handlers.POST().accept((req) ->
            req.body().get().subscribe(new CancelAfter(midway)));
        
        addHandler("/discard-midway", discardMidway);
        client().openConnection();
        
        try {
            String req = requestWithBody("/discard-midway", "x".repeat(length)),
                   res = client().writeRead(req);
            
            assertThat(res).isEqualTo(
                "HTTP/1.1 202 Accepted" + CRLF +
                "Content-Length: 0" + CRLF + CRLF);
            
            empty_request_body();
        } finally {
            client().closeConnection();
        }
    }
    
    private static String requestWithBody(String path, String body) {
        return "POST " + path + " HTTP/1.1" + CRLF +
               "Accept: text/plain; charset=utf-8" + CRLF +
               "Content-Length: " + body.length() + CRLF + CRLF +
               body;
    }
    
    private static class CancelAfter implements Flow.Subscriber<PooledByteBufferHolder>
    {
        private Flow.Subscription subscription;
        private long read;
        private final long target;
        
        CancelAfter(long byteCount) {
            target = byteCount;
        }
        
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            (this.subscription = subscription).request(Long.MAX_VALUE);
        }
        
        @Override
        public void onNext(PooledByteBufferHolder item) {
            assert read < target;
            while (item.get().hasRemaining()) {
                item.get().get();
                if (++read == target) {
                   subscription.cancel();
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
