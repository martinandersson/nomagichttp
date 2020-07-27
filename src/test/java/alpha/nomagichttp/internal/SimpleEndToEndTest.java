package alpha.nomagichttp.internal;

import alpha.nomagichttp.handler.Handler;
import alpha.nomagichttp.message.ResponseBuilder;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.route.RouteBuilder;
import alpha.nomagichttp.test.Logging;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static alpha.nomagichttp.handler.Handlers.GET;
import static alpha.nomagichttp.handler.Handlers.POST;
import static alpha.nomagichttp.message.Responses.ok;
import static alpha.nomagichttp.route.Routes.route;
import static java.lang.System.Logger.Level.ALL;
import static org.assertj.core.api.Assertions.assertThat;


/**
 * "Simple" end-to-end server tests, more specifically, the unit-test version of
 * examples provided in {@code alpha.nomagichttp.examples}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class SimpleEndToEndTest extends AbstractEndToEndTest
{
    private static final String CRLF = "\r\n";
    
    @BeforeAll
    static void logEverything() {
        Logging.setLevel(SimpleEndToEndTest.class, ALL);
    }
    
    @Test
    void helloworld_console() throws IOException, InterruptedException {
        Handler handler = GET().run(() ->
                System.out.println("Hello, World!"));
        
        server().getRouteRegistry().add(route("/hello-console", handler));
        
        String req = "GET /hello-console HTTP/1.1" + CRLF + CRLF + CRLF;
        String res = writeReadText(req, CRLF + CRLF);
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 202 Accepted" + CRLF +
            "Content-Length: 0" + CRLF + CRLF);
    }
    
    @Test
    void helloworld_response() throws IOException, InterruptedException {
        Handler handler = GET().supply(() ->
                ok("Hello World!").asCompletedStage());
        
        Route route = new RouteBuilder("/hello-response")
                .handler(handler).build();
        
        server().getRouteRegistry().add(route);
        
        String req =
            "GET /hello-response HTTP/1.1" + CRLF +
            "Accept: text/plain; charset=utf-8" + CRLF + CRLF;
        
        String resp = writeReadText(req, "World!");
        
        assertThat(resp).isEqualTo(
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
        
        Route route = new RouteBuilder("/greet-param").param("name").handler(echo).build();
        server().getRouteRegistry().add(route);
        
        String request =
            "GET /greet-param/John HTTP/1.1" + CRLF +
            "Accept: text/plain; charset=utf-8" + CRLF + CRLF;
        
        String resp = writeReadText(request, "John!");
        
        assertThat(resp).isEqualTo(
            "HTTP/1.1 200 OK" + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 11" + CRLF + CRLF +
            
            "Hello John!");
    }
    
    // TODO: greet_queryparameter() (but this needs to be implemented first lol)
    
    @Test
    void greet_requestbody() throws IOException, InterruptedException {
        Handler echo = POST().apply(request ->
                request.body().toText().thenApply(name -> ok("Hello " + name + "!")));
        
        Route route = new RouteBuilder("/greet-body").handler(echo).build();
        server().getRouteRegistry().add(route);
        
        String request =
            "POST /greet-body HTTP/1.1" + CRLF +
            "Accept: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 4" + CRLF + CRLF +
            
            "John";
        
        String resp = writeReadText(request, "John!");
        
        assertThat(resp).isEqualTo(
            "HTTP/1.1 200 OK" + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 11" + CRLF +
            CRLF +
            "Hello John!");
    }
    
    @Test
    void echo_server() throws IOException, InterruptedException {
        Handler echo = POST().apply(request -> {
            ResponseBuilder builder = new ResponseBuilder()
                    .httpVersion(request.httpVersion())
                    .statusCode(200)
                    .reasonPhrase("OK");
            
            request.headers().map().forEach(builder::header);
            
            return builder.body(request.body().asPublisher())
                    .asCompletedStage();
        });
        
        Route route = new RouteBuilder("/echo").handler(echo).build();
        server().getRouteRegistry().add(route);
        
        String request =
            "POST /echo HTTP/99+" + CRLF +
            "Accept: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 14" + CRLF + CRLF +
            
            "Some body text";
        
        String resp = writeReadText(request, "body text");
        
        assertThat(resp).isEqualTo(
            "HTTP/99+ 200 OK" + CRLF +
            "Accept: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 14" + CRLF + CRLF +
            
            "Some body text");
    }
    
    // TODO: echo body LARGE! Like super large. 100MB or something. Must brake all buffer capacities, that's the point.
    //       Should go to "large" test set.
}
