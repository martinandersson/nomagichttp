package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.testutil.ClientOperations;
import alpha.nomagichttp.testutil.Logging;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;

import static java.lang.System.Logger.Level.ALL;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Will setup a {@code server()} and a {@code client()} configured with the
 * server's port; scoped to each test.<p>
 * 
 * The server has no routes added, but, has an error handler added which simply
 * collects all exceptions caught into a {@code List} and then delegates the
 * error handling to the default error handler.<p>
 * 
 * By default, after-each will assert that no errors were caught. If errors are
 * expected, call {@code assertErrorWasThrown()}. How many errors and what types
 * may then be inspected further by the test method.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see SimpleEndToEndTest
 * @see DetailedEndToEndTest
 */
abstract class AbstractEndToEndTest
{
    private static HttpServer server;
    private static ClientOperations client;
    
    private final Collection<Throwable> errors = new ConcurrentLinkedQueue<>();
    private boolean assertNoErrors = true;
    
    @BeforeEach
    void start() throws IOException {
        Logging.setLevel(SimpleEndToEndTest.class, ALL);
        
        ErrorHandler collect = (t, r, h) -> {
            errors.add(t);
            throw t;
        };
        
        server = HttpServer.create(collect).start();
        client = new ClientOperations(server);
    }
    
    @AfterEach
    void assertNoErrors() {
        if (assertNoErrors) {
            assertThat(errors).isEmpty();
        } else {
            assertThat(errors).isNotEmpty();
        }
    }
    
    @AfterEach
    void stop() throws IOException {
        if (server != null) {
            server.stop();
        }
    }
    
    public static HttpServer server() {
        return server;
    }
    
    public static ClientOperations client() {
        return client;
    }
    
    public final void assertServerErrorWasThrown() {
        assertNoErrors = false;
    }
}
