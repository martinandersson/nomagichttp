package alpha.nomagichttp.mediumtest;

import alpha.nomagichttp.message.Responses;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.TestClient.CRLF;
import static alpha.nomagichttp.testutil.TestRequests.post;
import static alpha.nomagichttp.testutil.TestRoutes.respondIsBodyEmpty;
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
        String res = client().writeReadTextUntil(post(""), "true");
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
        
        String resp = client().writeReadTextUntil(
            "GET / HTTP/1.0" + CRLF + CRLF, "Received HTTP/1.0");
        
        assertThat(resp).isEqualTo(
            "HTTP/1.0 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 17"                      + CRLF +
            "Connection: close"                       + CRLF + CRLF +
            
            "Received HTTP/1.0");
    }
    
    // TODO: Some sucky clients will likely not be able to receive multiple responses,
    //       just ignore them.
    // Note: There's a least one other expect100Continue-test in DetailTest
    @Test
    void expect100Continue_onFirstBodyAccess() throws IOException {
        server().add("/", POST().apply(req ->
            req.body().toText().thenApply(Responses::text)));
        
        String req = "POST / HTTP/1.1"          + CRLF +
            "Expect: 100-continue"     + CRLF +
            "Content-Length: 2"        + CRLF +
            "Content-Type: text/plain" + CRLF + CRLF +
            
            "Hi";
        
        String rsp = client().writeReadTextUntil(req, "Hi");
        
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 100 Continue"                   + CRLF + CRLF +
            
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 2"                       + CRLF + CRLF +
            
            "Hi");
    }
}