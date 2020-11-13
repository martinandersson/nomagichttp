package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletionStage;
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
    
    private static String requestWithBody(String path, String body) {
        return "POST " + path + " HTTP/1.1" + CRLF +
               "Accept: text/plain; charset=utf-8" + CRLF +
               "Content-Length: " + body.length() + CRLF + CRLF +
               body;
    }
}
