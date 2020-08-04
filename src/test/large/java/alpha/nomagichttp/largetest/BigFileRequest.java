package alpha.nomagichttp.largetest;

import alpha.nomagichttp.handler.Handler;
import alpha.nomagichttp.message.Responses;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static alpha.nomagichttp.handler.Handlers.POST;
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
        
        Handler saver = POST().apply(req -> req.body().toFile(FILE)
                .thenApply(n -> Long.toString(n))
                .thenApply(Responses::ok));
        
        addHandler("/file", saver);
    }
    
    @Test
    void test() throws IOException, InterruptedException {
        byte[] expected = DataUtil.bytes(1_000_000);
        
        HttpResponse<Void> res = postBytes("/file", expected);
        assertThat(res.statusCode()).isEqualTo(200);
        
        assertThat(Files.readAllBytes(FILE)).isEqualTo(expected);
    }
}
