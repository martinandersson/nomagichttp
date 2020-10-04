package alpha.nomagichttp.internal;

import alpha.nomagichttp.handler.Handler;
import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import static alpha.nomagichttp.handler.Handlers.GET;
import static alpha.nomagichttp.handler.Handlers.POST;
import static alpha.nomagichttp.internal.ClientOperations.CRLF;
import static java.lang.String.join;
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
    /**
     * Performs two requests in a row.
     */
    @Test
    void exchange_restart() throws IOException, InterruptedException {
        // Echo request body as-is
        Handler echo = POST().apply(req ->
                req.body().toText().thenApply(Responses::ok));
        
        addHandler("/restart", echo);
        
        final String reqHead =
            "POST /restart HTTP/1.1" + CRLF +
            "Accept: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 3" + CRLF + CRLF;
        
        final String resHead =
            "HTTP/1.1 200 OK" + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 3" + CRLF + CRLF;
        
        client().openConnection();
        
        try {
            String res1 = client().writeRead(reqHead + "ABC", "ABC");
            assertThat(res1).isEqualTo(resHead + "ABC");
            
            String res2 = client().writeRead(reqHead + "DEF", "DEF");
            assertThat(res2).isEqualTo(resHead + "DEF");
        } finally {
            client().closeConnection();
        }
    }
    
    /**
     * Handler subscribes to an empty message body which immediately completes.
     */
    @Test
    void subscribe_to_empty_body() throws IOException, InterruptedException {
        Handler h = GET().apply(req -> {
            final CompletableFuture<Response> res = new CompletableFuture<>();
            final List<String> signals = new ArrayList<>();
            
            req.body().subscribe(new Flow.Subscriber<>() {
                public void onSubscribe(Flow.Subscription subscription) {
                    signals.add("onSubscribe"); }
                
                public void onNext(PooledByteBufferHolder item) {
                    signals.add("onNext");
                    item.release(); }
                
                public void onError(Throwable throwable) {
                    signals.add("onError"); }
                
                public void onComplete() {
                    signals.add("onComplete");
                    res.complete(Responses.ok(join(" ", signals)));
                }
            });
            
            return res;
        });
        
        addHandler("/empty-body", h);
        
        String req = "GET /empty-body HTTP/1.1" + CRLF + CRLF,
               res = client().writeRead(req, "onComplete");
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 200 OK" + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 22" + CRLF + CRLF +
            
            "onSubscribe onComplete");
    }
    
    // TODO: Autodiscard request body test. Handler should be able to respond with no body.
    
    // TODO: echo body LARGE! Like super large. 100MB or something. Must brake all buffer capacities, that's the point.
    //       Should go to "large" test set.
}
