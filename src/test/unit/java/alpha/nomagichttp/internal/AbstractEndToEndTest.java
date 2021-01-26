package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.testutil.ClientOperations;
import alpha.nomagichttp.testutil.Logging;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

import static alpha.nomagichttp.HttpServer.Config.DEFAULT;
import static alpha.nomagichttp.handler.RequestHandlers.noop;
import static alpha.nomagichttp.route.Routes.route;
import static java.lang.System.Logger.Level.ALL;
import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Will setup a {@code server()} and a {@code client()} configured with the
 * server's port.<p>
 * 
 * The server has only one "/" route with a NOOP handler. Each test must manage
 * its own route(s) and handler(s) using the server's route registry (or
 * provided convenience method {@code addHandler()}).<p>
 * 
 * It's arguably a good baseline to assume that all HTTP exchanges completes
 * normally. And so, this class will assert after each test method that the
 * default exception handler was never called with an exception. This check can
 * be skipped using {@code doNotAssertNormalFinish()}.
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
    
    private static final Collection<Throwable> errors = new ConcurrentLinkedQueue<>();
    private boolean assertNormalFinish = true;
    
    @BeforeAll
    private static void start() throws IOException {
        Logging.setLevel(SimpleEndToEndTest.class, ALL);
        
        Supplier<ErrorHandler> collect = () -> (t, r, h) -> {
            errors.add(t);
            return ErrorHandler.DEFAULT.apply(t, r, h);
        };
        
        server = HttpServer.with(DEFAULT, singleton(route("/", noop())), collect).start();
        client = new ClientOperations(server.getLocalAddress().getPort());
    }
    
    @AfterEach
    private void assertNormalFinish() {
        if (!assertNormalFinish) {
            errors.clear();
            return;
        }
        
        try {
            assertThat(errors).isEmpty();
        } finally {
            errors.clear();
        }
    }
    
    @AfterAll
    private static void stop() throws IOException {
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
    
    public static void addHandler(String route, RequestHandler handler) {
        server().getRouteRegistry().add(route(route, handler));
    }
    
    public void doNotAssertNormalFinish() {
        assertNormalFinish = false;
    }
}
