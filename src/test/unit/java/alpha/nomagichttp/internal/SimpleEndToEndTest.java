package alpha.nomagichttp.internal;

import alpha.nomagichttp.handler.Handler;
import alpha.nomagichttp.message.ResponseBuilder;
import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.route.RouteBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static alpha.nomagichttp.handler.Handlers.GET;
import static alpha.nomagichttp.handler.Handlers.POST;
import static alpha.nomagichttp.internal.ClientOperations.CRLF;
import static alpha.nomagichttp.message.Responses.ok;
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
class SimpleEndToEndTest extends AbstractEndToEndTest
{
    @Test
    void helloworld_console() throws IOException, InterruptedException {
        Handler handler = GET().run(() ->
                System.out.println("Hello, World!"));
        
        addHandler("/hello-console", handler);
        
        String req = "GET /hello-console HTTP/1.1" + CRLF + CRLF + CRLF;
        String res = client().writeRead(req);
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 202 Accepted" + CRLF +
            "Content-Length: 0" + CRLF + CRLF);
    }
    
    @Test
    void helloworld_response() throws IOException, InterruptedException {
        Handler handler = GET().supply(() ->
                ok("Hello World!").asCompletedStage());
        
        addHandler("/hello-response", handler);
        
        String req =
            "GET /hello-response HTTP/1.1" + CRLF +
            "Accept: text/plain; charset=utf-8" + CRLF + CRLF;
        
        String res = client().writeRead(req, "World!");
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 200 OK" + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 12" + CRLF + CRLF +
            
            "Hello World!");
    }
    
    @Test
    void greet_pathparam() throws IOException, InterruptedException {
        Handler echo = GET().apply(request -> {
            String name = request.paramFromPath("name").get();
            String text = "Hello " + name + "!";
            return ok(text).asCompletedStage();
        });
        
        Route route = new RouteBuilder("/greet-param").param("name")
                .handler(echo)
                .build();
        
        server().getRouteRegistry().add(route);
        
        String req =
            "GET /greet-param/John HTTP/1.1" + CRLF +
            "Accept: text/plain; charset=utf-8" + CRLF + CRLF;
        
        String res = client().writeRead(req, "John!");
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 200 OK" + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 11" + CRLF + CRLF +
            
            "Hello John!");
    }
    
    // TODO: greet_queryparameter() (but this needs to be implemented first lol)
    
    @Test
    void greet_requestbody() throws IOException, InterruptedException {
        Handler echo = POST().apply(req ->
                req.body().get().toText().thenApply(name -> ok("Hello " + name + "!")));
        
        addHandler("/greet-body", echo);
        
        String req =
            "POST /greet-body HTTP/1.1" + CRLF +
            "Accept: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 4" + CRLF + CRLF +
            
            "John";
        
        String res = client().writeRead(req, "John!");
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 200 OK" + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 11" + CRLF +
            CRLF +
            "Hello John!");
    }
    
    @Test
    void echo_headers() throws IOException, InterruptedException {
        Handler echo = GET().apply(req -> {
            ResponseBuilder b = ResponseBuilder.ok();
            req.headers().map().forEach(b::header);
            return b.noBody().asCompletedStage();
        });
        
        addHandler("/echo-headers", echo);
        
        String req =
            "GET /echo-headers HTTP/1.1" + CRLF +
            "Accept: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 0" + CRLF + CRLF;
        
        String res = client().writeRead(req);
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 200 OK" + CRLF +
            "Accept: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 0" + CRLF +
            "Content-Length: 0" + CRLF + CRLF);
    }
    
    @Test
    void post_small_file() throws IOException, InterruptedException {
        // 1. Save to new file
        // ---
        Path file = Files.createTempDirectory("nomagic")
                .resolve("some-file.txt");
        
        Handler saver = POST().apply(req ->
                req.body().get().toFile(file)
                          .thenApply(n -> Long.toString(n))
                          .thenApply(Responses::ok));
        
        addHandler("/small-file", saver);
        
        final String reqHead =
            "POST /small-file HTTP/1.1" + CRLF +
            "Content-Length: 3" + CRLF + CRLF;
        
        client().writeRead(reqHead + "Foo", "3");
        assertThat(Files.readString(file)).isEqualTo("Foo");
        
        // 2. By default, existing files are overwritten
        // ---
        client().writeRead(reqHead + "Bar", "3");
        assertThat(Files.readString(file)).isEqualTo("Bar");
    }
}
