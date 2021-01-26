package alpha.nomagichttp.largetest;

import alpha.nomagichttp.handler.RequestHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static alpha.nomagichttp.handler.RequestHandlers.POST;
import static alpha.nomagichttp.message.Response.Builder.ok;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * POSTs a big file (50 MB) to server and verifies all the disk contents.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class BigFileRequest extends AbstractSingleClientTest
{
    private static Path FILE;
    
    @BeforeAll
    static void beforeAll() throws IOException {
        FILE = Files.createTempDirectory("nomagic")
                .resolve("50MB.bin");
        
        RequestHandler saver = POST().apply(req -> req.body().get().toFile(FILE)
                .thenApply(n -> ok().header("Received", Long.toString(n)).build()));
        
        server().add("/file", saver);
    }
    
    @Test
    void test() throws IOException, InterruptedException {
        final int len = 50 * 1_000_000;
        
        byte[] expected = DataUtil.bytes(len);
        
        HttpResponse<Void> res = postBytes("/file", expected);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("Received")).contains(Long.toString(len));
        assertThat(Files.readAllBytes(FILE)).isEqualTo(expected);
    }
}
