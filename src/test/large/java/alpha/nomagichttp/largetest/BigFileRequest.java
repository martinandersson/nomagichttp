package alpha.nomagichttp.largetest;

import alpha.nomagichttp.testutil.AbstractRealTest;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.nio.channels.Channel;
import java.nio.file.Files;
import java.nio.file.Path;

import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.message.Responses.ok;
import static alpha.nomagichttp.testutil.TestClient.CRLF;
import static alpha.nomagichttp.util.BetterBodyPublishers.ofFile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * POST a big file (50 MB) to server, verify disk contents, respond same file.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
@TestMethodOrder(OrderAnnotation.class)
class BigFileRequest extends AbstractRealTest
{
    private static Path file;
    private static byte[] contents;
    private static boolean saved;
    
    @Test
    @Order(1)
    void post() throws IOException {
        file = Files.createTempDirectory("nomagic").resolve("50MB");
        
        server().add("/file", POST().apply(req ->
                req.body().toFile(file).thenApply(len ->
                        noContent().toBuilder()
                                   .header("Received", Long.toString(len))
                                   .build())));
        
        int len = 50 * 1_000_000;
        contents = DataUtil.bytes(len);
        
        final String rsp;
        Channel conn = client().openConnection();
        try (conn) {
            rsp = client()
                      .write(
                          "POST /file HTTP/1.1"     + CRLF +
                          "Content-Length: " + len  + CRLF +
                          "Connection: close"       + CRLF + CRLF)
                      .write(
                          contents)
                      .shutdownOutput()
                      .readTextUntilEOS();
        }
        
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 204 No Content" + CRLF +
            "Received: " + len        + CRLF +
            "Connection: close"       + CRLF + CRLF);
        assertThat(Files.readAllBytes(file)).isEqualTo(contents);
        saved = true;
    }
    
    @Test
    @Order(2)
    void get() throws IOException {
        assumeTrue(saved);
        server().add("/file", GET().respond(ok(ofFile(file))));
        
        final byte[] body;
        Channel conn = client().openConnection();
        try (conn) {
            assertThat(client().writeReadTextUntilNewlines(
                "GET /file HTTP/1.1"                     + CRLF +
                "Connection: close"                      + CRLF + CRLF))
                    .isEqualTo(
                "HTTP/1.1 200 OK"                        + CRLF +
                "Content-Type: application/octet-stream" + CRLF +
                "Content-Length: " + contents.length     + CRLF +
                "Connection: close"                      + CRLF + CRLF);
            body = client().readBytesUntilEOS();
        }
        assertThat(body).isEqualTo(contents);
    }
}
