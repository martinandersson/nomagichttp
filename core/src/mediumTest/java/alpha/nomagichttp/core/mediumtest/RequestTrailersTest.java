package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.testutil.functional.AbstractRealTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.text;
import static org.assertj.core.api.Assertions.assertThat;

///  Tests of request trailers.
/// 
/// @author Martin Andersson (webmaster at martinandersson.com)
final class RequestTrailersTest extends AbstractRealTest
{
    /*
     * The happy version of
     * {@link ErrorTest.Special#requestTrailersDiscarded_exceptionNotHandled()}.
     */
    // TODO: Make client-compatibility tests
    @Test
    void example() throws IOException {
        // Echo body and append trailer value
        server().add("/", POST().apply(req ->
                text(req.body().toText() +
                     req.trailers().firstValue("Append-This").get())));
        
        var rsp = client().writeReadTextUntilEOS("""
            POST / HTTP/1.1
            Transfer-Encoding: chunked
            Connection: close
            
            6
            Hello\s
            0
            Append-This: World!
            
            """);
        
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 200 OK\r
            Content-Type: text/plain; charset=utf-8\r
            Connection: close\r
            Content-Length: 12\r
            \r
            Hello World!""");
    }
}
