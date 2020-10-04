package alpha.nomagichttp.internal;

import alpha.nomagichttp.Server;
import alpha.nomagichttp.test.Logging;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.io.IOException;

import static alpha.nomagichttp.handler.Handlers.noop;
import static alpha.nomagichttp.route.Routes.route;
import static java.lang.System.Logger.Level.ALL;

/**
 * Will setup a {@code server()} and a {@code client()} configured with the
 * server's port.<p>
 * 
 * The server has only one "/" route with a NOOP handler. Each test must manage
 * its own route(s) and handler(s) using the server's route registry.<p>
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see SimpleEndToEndTest
 */
abstract class AbstractEndToEndTest
{
    private static Server server;
    private static ClientOperations client;
    
    @BeforeAll
    static void start() throws IOException {
        Logging.setLevel(SimpleEndToEndTest.class, ALL);
        server = Server.with(route("/", noop())).start();
        client = new ClientOperations(server.getPort());
    }
    
    @AfterAll
    static void stop() throws IOException {
        if (client != null) {
            client.closeConnection();
        }
        
        if (server != null) {
            server.stop();
        }
    }
    
    public static Server server() {
        return server;
    }
    
    public static ClientOperations client() {
        return client;
    }
}
