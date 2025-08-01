package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.testutil.functional.AbstractRealTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static alpha.nomagichttp.core.mediumtest.util.TestRequestHandlers.respondIsBodyEmpty;
import static alpha.nomagichttp.core.mediumtest.util.TestRequests.get;
import static alpha.nomagichttp.core.mediumtest.util.TestRequests.post;
import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.message.Responses.ok;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.TestConstants.CRLF;
import static alpha.nomagichttp.testutil.TestFiles.writeTempFile;
import static alpha.nomagichttp.util.ByteBufferIterables.ofFile;
import static alpha.nomagichttp.util.ByteBuffers.asciiBytes;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coarse+fine-grained HTTP exchanges.<p>
 * 
 * Tests here perform classical "GET ..." requests and then expect "HTTP/1.1
 * 200 ..." responses. The purpose is to ensure the NoMagicHTTP library can in
 * practice be used by different HTTP clients.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// TODO: Run tests using different clients
final class MessageTest extends AbstractRealTest
{
    // "Accept: text/plain; charset=utf-8; q=0.9, text/plain; charset=iso-8859-1"
    // ISO 8859 wins, coz implicit q = 1
    @Test
    void charsetPreferenceThroughQ() throws IOException {
        server().add("/", GET().apply(req ->
            text("hello", req)));
        
        // Default is UTF-8
        // (important to keep this here as we need to make sure the next test
        //  pass for the right reasons)
        var rsp1 = client().writeReadTextUntil(
            "GET / HTTP/1.1"                          + CRLF + CRLF, "hello");
        assertThat(rsp1).isEqualTo(
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 5"                       + CRLF + CRLF +
            
            "hello");
        
        // Responses.text(String, Request) uses charset from request
        var rsp2 = client().writeReadTextUntil(
            "GET / HTTP/1.1"                          + CRLF +
            "Accept: text/plain; charset=utf-8; q=0.9, " +
                    "text/plain; charset=iso-8859-1"  + CRLF + CRLF, "hello");
        assertThat(rsp2).isEqualTo(
            "HTTP/1.1 200 OK"                              + CRLF +
            "Content-Type: text/plain; charset=iso-8859-1" + CRLF +
            "Content-Length: 5"                            + CRLF + CRLF +
            
            "hello");
    }
    
    /**
     * Can make an HTTP/1.0 request (receives HTTP/1.1 response).<p>
     * 
     * See {@link HttpVersionTest} for cases related to unsupported versions.
     */
    // TODO: Any client that can't do HTTP/1.0 can simply be ignored
    @Test
    void http_1_0() throws IOException {
        server().add("/", GET().apply(req ->
            text("Received " + req.httpVersion())));
        
        String resp = client().writeReadTextUntil(
            "GET / HTTP/1.0" + CRLF + CRLF, "Received HTTP/1.0");
        
        assertThat(resp).isEqualTo(
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Connection: close"                       + CRLF +
            "Content-Length: 17"                      + CRLF + CRLF +
            
            "Received HTTP/1.0");
    }
    
    // TODO: If this can't run using different clients, just do GET instead of POST
    @Test
    void requestBodyEmpty() throws IOException {
        server().add("/",
            respondIsBodyEmpty());
        String res = client().writeReadTextUntil(post(""), "true");
        assertThat(res).isEqualTo(
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 4"                       + CRLF + CRLF +
            
            "true");
    }
    
    @Test
    void responseOfFile() throws IOException {
        var file = writeTempFile(asciiBytes("Hello, World!"));;
        server().add(
            "/", GET().apply(_ -> ok(ofFile(file))));
        var rsp = client().writeReadTextUntil(get(), "!");
        assertThat(rsp).isEqualTo("""
            HTTP/1.1 200 OK\r
            Content-Type: application/octet-stream\r
            Content-Length: 13\r
            \r
            Hello, World!""");
    }
}