package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.message.UnsupportedTransferCodingException;
import alpha.nomagichttp.testutil.functional.AbstractRealTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import static alpha.nomagichttp.core.mediumtest.util.TestRequests.get;
import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.ok;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.TestConstants.CRLF;
import static alpha.nomagichttp.testutil.TestFiles.writeTempFile;
import static alpha.nomagichttp.util.ByteBufferIterables.ofFile;
import static alpha.nomagichttp.util.ByteBuffers.asciiBytes;
import static org.assertj.core.api.Assertions.assertThat;

/// Tests of message construction and serialization.
/// 
/// Many of these cases can probably be seen as non-public examples.
/// 
/// @author Martin Andersson (webmaster at martinandersson.com)
final class MessageTest extends AbstractRealTest
{
    @Nested
    class Request {
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
        // TODO: Make client-compatibility tests
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
        
        @Test
        void unsupportedTransferCoding() throws IOException, InterruptedException {
            server();
            String rsp = client().writeReadTextUntilNewlines("""
                GET / HTTP/1.1\r
                Transfer-Encoding: blabla, chunked\r\n\r
                """);
            assertThat(rsp).isEqualTo("""
                HTTP/1.1 501 Not Implemented\r
                Connection: close\r
                Content-Length: 0\r\n\r\n""");
            assertThat(pollServerException())
                .isExactlyInstanceOf(UnsupportedTransferCodingException.class)
                .hasNoCause()
                .hasNoSuppressedExceptions()
                .hasMessage("Unsupported Transfer-Encoding: blabla");
        }
        
        // TODO: Make client-compatibility tests
        @Test
        void bodyToFile() throws IOException, InterruptedException {
            // Destination file
            Path file = Files.createTempDirectory("nomagic")
                    .resolve("some-file.txt");
            
            // Handler saves the file bytes and return byte count as response body
            server().add("/small-file", POST().apply(req ->
                    text(Long.toString(req.body().toFile(file)))));
            
            final String reqHead =
                "POST /small-file HTTP/1.1" + CRLF +
                "Content-Length: 3"         + CRLF + CRLF;
            
            String res1 = client().writeReadTextUntil(reqHead + "Foo", "3");
            
            assertThat(res1).isEqualTo(
                "HTTP/1.1 200 OK"                          + CRLF +
                "Content-Type: text/plain; charset=utf-8"  + CRLF +
                "Content-Length: 1"                        + CRLF + CRLF +
                
                "3");
            assertThat(Files.readString(file)).isEqualTo("Foo");
            
            // By default, existing files are not overwritten
            String res2 = client().writeReadTextUntilNewlines(reqHead + "Bar");
            
            assertThat(res2).isEqualTo(
                "HTTP/1.1 500 Internal Server Error" + CRLF +
                "Content-Length: 0"                  + CRLF + CRLF);
            assertAwaitHandledAndLoggedExc()
                    .isExactlyInstanceOf(FileAlreadyExistsException.class);
            assertThat(Files.readString(file))
                    .isEqualTo("Foo");
        }
    }
    
    @Nested
    class Response {
        // TODO: Make client-compatibility tests
        @Test
        void OkOfFile() throws IOException {
            var file = writeTempFile(asciiBytes("Hello, World!"));
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
}