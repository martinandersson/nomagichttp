package alpha.nomagichttp.internal;

import alpha.nomagichttp.handler.Handler;
import alpha.nomagichttp.message.Responses;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static alpha.nomagichttp.handler.Handlers.GET;
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
    /**
     * Performs two requests in a row.
     */
    @Test
    void exchange_restart() throws IOException, InterruptedException {
        // Echo request body as-is
        Handler echo = POST().apply(req ->
                req.body().get().toText().thenApply(Responses::ok));
        
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
    
    @Test
    void empty_body() throws IOException, InterruptedException {
        Handler h = GET().apply(req -> Responses.ok(
                  String.valueOf(req.body().isEmpty())
                ).asCompletedStage());
        
        addHandler("/empty-body", h);
        
        String req = "GET /empty-body HTTP/1.1" + CRLF + CRLF,
               res = client().writeRead(req, "true");
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 200 OK" + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 4" + CRLF + CRLF +
            
            "true");
    }
    
    // TODO: Autodiscard request body test. Handler should be able to respond without consuming body.
    //       Handler should be able to echo half a body, byte by byte, and full body, byte by byte
    
    // TODO: echo body LARGE! Like super large. 100MB or something. Must brake all buffer capacities, that's the point.
    //       Should go to "large" test set.
}
