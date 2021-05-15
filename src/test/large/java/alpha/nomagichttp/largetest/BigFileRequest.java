package alpha.nomagichttp.largetest;

import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.util.BetterBodyPublishers;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.noContent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * POST a big file (50 MB) to server, verify disk contents, respond same file.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
@TestMethodOrder(OrderAnnotation.class)
class BigFileRequest extends AbstractSingleClientTest
{
    private static Path file;
    private static byte[] contents;
    private static boolean saved;
    
    @Test
    @Order(1)
    void post() throws IOException, InterruptedException {
        file = Files.createTempDirectory("nomagic").resolve("50MB");
        
        RequestHandler rh = POST().apply(req ->
                req.body()
                   .toFile(file)
                   .thenApply(n ->
                       noContent().toBuilder()
                                  .header("Received", Long.toString(n))
                                  .build()));
        
        server().add("/receive", rh);
        
        int len = 50 * 1_000_000;
        contents = DataUtil.bytes(len);
        HttpResponse<Void> res = postBytes("/receive", contents);
        
        assertThat(res.statusCode()).isEqualTo(204);
        assertThat(res.headers().firstValue("Received")).contains(Long.toString(len));
        assertThat(Files.readAllBytes(file)).isEqualTo(contents);
        saved = true;
    }
    
    @Test
    @Order(2)
    void get() throws IOException, InterruptedException {
        assumeTrue(saved);
        
        Response r = Responses.ok(BetterBodyPublishers.ofFile(file));
        RequestHandler rh = GET().respond(r);
        server().add("/retrieve", rh);
        
        HttpResponse<byte[]> res = getBytes("/retrieve");
        
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("Content-Type")).contains("application/octet-stream");
        assertThat(res.body()).isEqualTo(contents);
    }
}
