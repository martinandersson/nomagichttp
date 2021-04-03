package alpha.nomagichttp.largetest;

import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.Responses;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static alpha.nomagichttp.handler.RequestHandlers.POST;
import static alpha.nomagichttp.message.Responses.noContent;
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
                    .resolve("50MB");
        
        RequestHandler fileSaver = POST().apply(req ->
                req.body()
                   .toFile(FILE)
                   .thenApply(n ->
                       Responses.noContent()
                                .toBuilder()
                                .header("Received", Long.toString(n))
                                .build()));
        
        server().add("/file", fileSaver);
    }
    
    @Test
    void test() throws IOException, InterruptedException {
        final int len = 50 * 1_000_000;
        
        byte[] expected = DataUtil.bytes(len);
        
        HttpResponse<Void> res = postBytes("/file", expected);
        assertThat(res.statusCode()).isEqualTo(204);
        assertThat(res.headers().firstValue("Received")).contains(Long.toString(len));
        assertThat(Files.readAllBytes(FILE)).isEqualTo(expected);
    }
}
