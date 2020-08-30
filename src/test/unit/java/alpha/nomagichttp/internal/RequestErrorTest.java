package alpha.nomagichttp.internal;

import alpha.nomagichttp.Server;
import alpha.nomagichttp.test.Logging;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static alpha.nomagichttp.handler.Handlers.noop;
import static alpha.nomagichttp.internal.ClientOperations.CRLF;
import static alpha.nomagichttp.route.Routes.route;
import static java.lang.System.Logger.Level.ALL;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests of a server with exception handlers.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class RequestErrorTest
{
    static Server server;
    static ClientOperations client;
    
    @BeforeAll
    static void start() throws IOException {
        Logging.setLevel(RequestErrorTest.class, ALL);
        server = Server.with(route("/", noop())).start();
        client = new ClientOperations(server.getPort());
    }
    
    @AfterAll
    static void stop() throws IOException {
        if (server != null) {
            server.stop();
        }
    }
    
    @Test
    void not_found_default() throws IOException, InterruptedException {
        String req = "GET /404 HTTP/1.1" + CRLF + CRLF + CRLF,
               res = client.writeReadText(req);
        
        assertThat(res).isEqualTo(
            "HTTP/1.1 404 Not Found" + CRLF +
            "Content-Length: 0" + CRLF + CRLF);
    }
    
    // TODO: Implement
    //@Test
    void not_found_custom() {
        
    }
}