package alpha.nomagichttp.mediumtest;

import alpha.nomagichttp.testutil.AbstractRealTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.TestClient.CRLF;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Medium tests for before-actions.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class BeforeActionTest extends AbstractRealTest
{
    @Test
    void triple() throws IOException {
        server().before("/:A/*", (r, ch, chain) -> {
                    // Set first segment as "msg" attribute
                    r.attributes().set("msg",
                            r.target().pathParam("A"));
                    chain.proceed();
                })
                .before("/:B/:C", (r, ch, chain) -> {
                    // Concatenate with the second segment
                    r.attributes().<String>asMapAny().merge("msg",
                            r.target().pathParam("C"), String::concat);
                    chain.proceed();
                })
                .before("/hello/world", (r, ch, chain) -> {
                    // Write response (see, no need for a request handler lol)
                    ch.write(text(r.attributes().getAny("msg")));
                    chain.abort();
                });
        String rsp = client().writeReadTextUntilEOS(
            "GET /hello/world HTTP/1.1"               + CRLF +
            "Connection: close"                       + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Length: 10"                      + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Connection: close"                       + CRLF + CRLF +
            
            "helloworld");
        assertThatNoWarningOrErrorIsLogged();
    }
    
    @Test
    void proceedToRequestHandler() throws IOException {
        server()
            .before("/", (r, ch, chain) -> {
                r.attributes().set("msg", "hello");
                chain.proceed(); })
            .add("/", GET().apply(r ->
                text(r.attributes().getAny("msg")).completedStage()));
        String rsp = client().writeReadTextUntilEOS(
            "GET / HTTP/1.1"                          + CRLF +
            "Connection: close"                       + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Length: 5"                       + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Connection: close"                       + CRLF + CRLF +
            
            "hello");
    }
    
    @Test
    void crash() throws IOException, InterruptedException {
        server().before("/*", (r, ch, chain) -> {
            throw new RuntimeException("Oops!");
        });
        String rsp = client().writeReadTextUntilNewlines(
            "GET / HTTP/1.1"                          + CRLF + CRLF);
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 500 Internal Server Error"      + CRLF +
            "Content-Length: 0"                       + CRLF + CRLF);
        assertThatServerErrorObservedAndLogged()
            .isExactlyInstanceOf(RuntimeException.class)
            .hasMessage("Oops!")
            .hasNoCause()
            .hasNoSuppressedExceptions();
    }
}