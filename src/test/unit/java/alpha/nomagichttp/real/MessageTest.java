package alpha.nomagichttp.real;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.real.TestRequests.post;
import static alpha.nomagichttp.real.TestRoutes.respondIsBodyEmpty;
import static alpha.nomagichttp.testutil.TestClient.CRLF;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coarse-grained HTTP exchanges suitable to run using many different
 * clients.<p>
 * 
 * Tests here perform classical "GET ..." requests and then expect "HTTP/1.1
 * 200 ..." responses. The purpose is to ensure the NoMagicHTTP library can in
 * practice be used by different HTTP clients.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class MessageTest extends AbstractRealTest
{
    // TODO: Run tests using different clients
    
    // TODO: If this can't run using different clients, just do GET instead of POST
    @Test
    void request_body_empty() throws IOException {
        server().add(respondIsBodyEmpty());
        String res = client().writeRead(post(""), "true");
        assertThat(res).isEqualTo(
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 4"                       + CRLF + CRLF +
            
            "true");
    }
    
    // TODO: Any client that can't do HTTP/1.0 can simply be ignored
    /**
     * Can make a HTTP/1.0 request (and get HTTP/1.0 response).<p>
     * 
     * See {@link ErrorTest} for cases related to unsupported versions.
     */
    @Test
    void http_1_0() throws IOException {
        server().add("/", GET().apply(req ->
                text("Received " + req.httpVersion()).completedStage()));
        
        String resp = client().writeRead(
            "GET / HTTP/1.0" + CRLF + CRLF, "Received HTTP/1.0");
        
        assertThat(resp).isEqualTo(
            "HTTP/1.0 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 17"                      + CRLF +
            "Connection: close"                       + CRLF + CRLF +
            
            "Received HTTP/1.0");
    }
}