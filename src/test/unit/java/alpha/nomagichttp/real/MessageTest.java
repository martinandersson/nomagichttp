package alpha.nomagichttp.real;

import org.junit.jupiter.api.Test;

import java.io.IOException;

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
}