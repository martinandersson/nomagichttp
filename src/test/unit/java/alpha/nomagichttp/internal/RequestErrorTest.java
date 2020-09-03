package alpha.nomagichttp.internal;

import alpha.nomagichttp.ExceptionHandler;
import alpha.nomagichttp.Server;
import alpha.nomagichttp.ServerConfig;
import alpha.nomagichttp.message.ResponseBuilder;
import alpha.nomagichttp.route.NoRouteFoundException;
import alpha.nomagichttp.test.Logging;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static alpha.nomagichttp.handler.Handlers.noop;
import static alpha.nomagichttp.internal.ClientOperations.CRLF;
import static alpha.nomagichttp.route.Routes.route;
import static java.lang.System.Logger.Level.ALL;
import static java.util.Set.of;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests of a server with exception handlers.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class RequestErrorTest
{
    Server server;
    
    @BeforeAll
    static void setLogging() {
        Logging.setLevel(RequestErrorTest.class, ALL);
    }
    
    @AfterEach
    void stopServer() throws IOException {
        if (server != null) {
            server.stop();
        }
    }
    
    @Test
    void not_found_default() throws IOException, InterruptedException {
        server = Server.with(route("/", noop())).start();
        ClientOperations client = new ClientOperations(server.getPort());
        
        String req = "GET /404 HTTP/1.1" + CRLF + CRLF + CRLF,
               res = client.writeRead(req);
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 404 Not Found" + CRLF +
            "Content-Length: 0" + CRLF + CRLF);
    }
    
    @Test
    void not_found_custom() throws IOException, InterruptedException {
        ExceptionHandler custom = (exc, req, rou, han) -> {
            if (exc instanceof NoRouteFoundException) {
                return new ResponseBuilder()
                        .httpVersion("HTTP/1.1")
                        .statusCode(123)
                        .reasonPhrase("Custom Not Found!")
                        .mustCloseAfterWrite()
                        .noBody()
                        .asCompletedStage();
            }
            throw exc;
        };
        
        server = Server.with(ServerConfig.DEFAULT, of(route("/", noop())), () -> custom).start();
        ClientOperations client = new ClientOperations(server.getPort());
        
        String req = "GET /404 HTTP/1.1" + CRLF + CRLF + CRLF,
               res = client.writeRead(req);
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 123 Custom Not Found!" + CRLF +
            "Content-Length: 0" + CRLF + CRLF);
    }
}