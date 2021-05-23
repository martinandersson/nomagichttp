package alpha.nomagichttp.real;

import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.Responses;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.processing;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.TestClient.CRLF;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Simple" end-to-end tests that mimics all the examples provided in {@link
 * alpha.nomagichttp.examples} and other similar high-level use-scenarios of the
 * library.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see DetailedEndToEndTest
 */
class SimpleEndToEndTest extends AbstractRealTest
{
    @Test
    void helloworld() throws IOException {
        server().add("/hello-response", GET().respond(text("Hello World!")));
        
        String req =
            "GET /hello-response HTTP/1.1"      + CRLF +
            "Accept: text/plain; charset=utf-8" + CRLF + CRLF;
        
        String res = client().writeRead(req, "World!");
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 12"                      + CRLF + CRLF +
            
            "Hello World!");
    }
    
    @Test
    void greet_param() throws IOException {
        server().add("/hello/:name", GET().apply(req -> {
            String name = req.parameters().path("name");
            String text = "Hello " + name + "!";
            return text(text).completedStage();
        }));
        
        server().add("/hello", GET().apply(req -> {
            String name = req.parameters().queryFirst("name").get();
            String text = "Hello " + name + "!";
            return text(text).completedStage();
        }));
        
        String req1 =
            "GET /hello/John HTTP/1.1"          + CRLF +
            "Accept: text/plain; charset=utf-8" + CRLF + CRLF;
        
        String req2 =
            "GET /hello?name=John HTTP/1.1"     + CRLF +
            "Accept: text/plain; charset=utf-8" + CRLF + CRLF;
        
        String res1 = client().writeRead(req1, "John!");
        assertThat(res1).isEqualTo(
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 11"                      + CRLF + CRLF +
            
            "Hello John!");
        
        String res2 = client().writeRead(req2, "John!");
        assertThat(res2).isEqualTo(res1);
    }
    
    @Test
    void greet_requestbody() throws IOException {
        RequestHandler echo = POST().apply(req ->
                req.body().toText().thenApply(name ->
                        text("Hello " + name + "!")));
        
        server().add("/greet-body", echo);
        
        String req =
            "POST /greet-body HTTP/1.1"               + CRLF +
            "Accept: text/plain; charset=utf-8"       + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 4"                       + CRLF + CRLF +
            
            "John";
        
        String res = client().writeRead(req, "John!");
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 11"                      + CRLF + CRLF +
            
            "Hello John!");
    }
    
    @Test
    void echo_headers() throws IOException {
        RequestHandler echo = GET().apply(req ->
                Responses.noContent()
                         .toBuilder()
                         .addHeaders(req.headers())
                         .build()
                         .completedStage());
        
        server().add("/echo-headers", echo);
        
        String req =
            "GET /echo-headers HTTP/1.1"        + CRLF +
            "Accept: text/plain; charset=utf-8" + CRLF + CRLF;
        
        String res = client().writeRead(req);
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 204 No Content"           + CRLF +
            "Accept: text/plain; charset=utf-8" + CRLF + CRLF);
    }
    
    @Test
    void post_small_file() throws IOException, InterruptedException {
        // 1. Save to new file
        // ---
        Path file = Files.createTempDirectory("nomagic")
                .resolve("some-file.txt");
        
        RequestHandler saver = POST().apply(req ->
                req.body().toFile(file)
                          .thenApply(n -> Long.toString(n))
                          .thenApply(Responses::text));
        
        server().add("/small-file", saver);
        
        final String reqHead =
            "POST /small-file HTTP/1.1" + CRLF +
            "Content-Length: 3"         + CRLF + CRLF;
        
        String res1 = client().writeRead(reqHead + "Foo", "3");
        
        assertThat(res1).isEqualTo(
            "HTTP/1.1 200 OK"                          + CRLF +
            "Content-Type: text/plain; charset=utf-8"  + CRLF +
            "Content-Length: 1"                        + CRLF + CRLF +
            
            "3");
        assertThat(Files.readString(file)).isEqualTo("Foo");
        
        
        // 2. By default, existing files are not overwritten
        // ---
        String res2 = client().writeRead(reqHead + "Bar");
        
        assertThat(res2).isEqualTo(
            "HTTP/1.1 500 Internal Server Error" + CRLF +
            "Content-Length: 0"                  + CRLF + CRLF);
        assertThat(pollServerError()).isExactlyInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readString(file)).isEqualTo("Foo");
    }
    
    @Test
    public void multiple_responses() throws IOException {
        server().add("/", GET().accept((req, ch) -> {
            ch.write(processing());
            ch.write(processing());
            ch.write(text("Done!"));
        }));
        
        String rsp = client().writeRead("GET / HTTP/1.1" + CRLF + CRLF, "Done!");
        
        assertThat(rsp).isEqualTo(
            "HTTP/1.1 102 Processing"                 + CRLF + CRLF +
            
            "HTTP/1.1 102 Processing"                 + CRLF + CRLF +
            
            "HTTP/1.1 200 OK"                         + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 5"                       + CRLF + CRLF +
            
            "Done!");
    }
}
