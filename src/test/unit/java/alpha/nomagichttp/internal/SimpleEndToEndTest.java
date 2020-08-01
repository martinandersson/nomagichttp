package alpha.nomagichttp.internal;

import alpha.nomagichttp.handler.Handler;
import alpha.nomagichttp.message.ResponseBuilder;
import alpha.nomagichttp.message.Responses;
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
 * "Simple" end-to-end server tests.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class SimpleEndToEndTest extends AbstractEndToEndTest
{
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
        String res = writeReadText(req);
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 202 Accepted" + CRLF +
            "Content-Length: 0" + CRLF + CRLF);
    }
    
    @Test
    void helloworld_response() throws IOException, InterruptedException {
        Handler handler = GET().supply(() ->
                ok("Hello World!").asCompletedStage());
        
        server().getRouteRegistry().add(route("/hello-response", handler));
        
        String req =
            "GET /hello-response HTTP/1.1" + CRLF +
            "Accept: text/plain; charset=utf-8" + CRLF + CRLF;
        
        String res = writeReadText(req, "World!");
        
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
        
        String res = writeReadText(req, "John!");
        
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
                req.body().toText().thenApply(name -> ok("Hello " + name + "!")));
        
        server().getRouteRegistry().add(route("/greet-body", echo));
        
        String req =
            "POST /greet-body HTTP/1.1" + CRLF +
            "Accept: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 4" + CRLF + CRLF +
            
            "John";
        
        String res = writeReadText(req, "John!");
        
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
        
        server().getRouteRegistry().add(route("/echo-headers", echo));
        
        String req =
            "GET /echo-headers HTTP/1.1" + CRLF +
            "Accept: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 0" + CRLF + CRLF;
        
        String res = writeReadText(req);
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 200 OK" + CRLF +
            "Accept: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 0" + CRLF +
            "Content-Length: 0" + CRLF + CRLF);
    }
    
    /** Performs two requests in a row .*/
    @Test
    void exchange_restart() throws IOException, InterruptedException {
        // Echo request body as-is
        Handler echo = POST().apply(req ->
                req.body().toText().thenApply(Responses::ok));
        
        server().getRouteRegistry().add(route("/restart", echo));
        
        final String reqHead =
            "POST /restart HTTP/1.1" + CRLF +
            "Accept: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 3" + CRLF + CRLF;
        
        final String resHead =
            "HTTP/1.1 200 OK" + CRLF +
            "Content-Type: text/plain; charset=utf-8" + CRLF +
            "Content-Length: 3" + CRLF + CRLF;
        
        openConnection();
        
        String res1 = writeReadText(reqHead + "ABC", "ABC");
        assertThat(res1).isEqualTo(resHead + "ABC");
        
        String res2 = writeReadText(reqHead + "DEF", "DEF");
        assertThat(res2).isEqualTo(resHead + "DEF");
    }
    
    // TODO: Autodiscard request body test. Handler should be able to respond with no body.
    
    // TODO: echo body LARGE! Like super large. 100MB or something. Must brake all buffer capacities, that's the point.
    //       Should go to "large" test set.
    
}
