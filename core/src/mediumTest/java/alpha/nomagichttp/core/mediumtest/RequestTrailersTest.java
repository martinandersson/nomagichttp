package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.message.HeaderParseException;
import alpha.nomagichttp.testutil.functional.AbstractRealTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.text;
import static java.lang.System.Logger.Level.DEBUG;
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
    
    @Test
    void badTrailerDuringDiscard() throws IOException {
        server().add("/", POST().apply(req ->
                text(req.body().toText())));
        var rsp = client().writeReadTextUntilEOS("""
                POST / HTTP/1.1
                Trailer: just to trigger discarding
                Transfer-Encoding: chunked
                
                6
                Hello\s
                0
                Crash Plz: Whitespace in header name!
                
                """);
        assertThat(rsp).isEqualTo("""
                HTTP/1.1 200 OK\r
                Content-Type: text/plain; charset=utf-8\r
                Content-Length: 6\r
                \r
                Hello\s""");
        logRecorder().assertRemove(
                DEBUG, "Error while discarding request trailers, shutting down the input stream.",
                HeaderParseException.class)
            .hasToString("""
                HeaderParseException{prev=(hex:0x68, decimal:104, char:"h"), \
                curr=(hex:0x20, decimal:32, char:" "), pos=N/A, \
                msg=Whitespace in header name or before colon is not accepted.}""")
            .hasNoCause()
            .hasNoSuppressedExceptions();
    }
}
