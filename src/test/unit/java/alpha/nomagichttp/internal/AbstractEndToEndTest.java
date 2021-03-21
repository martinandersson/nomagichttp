package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.testutil.ClientOperations;
import alpha.nomagichttp.testutil.Logging;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.logging.LogRecord;
import java.util.stream.Stream;

import static java.lang.System.Logger.Level.ALL;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Will setup a {@code server()} and a {@code client()} configured with the
 * server's port; scoped to each test.<p>
 * 
 * The server has no routes added, but, has an error handler added which simply
 * collects all exceptions caught into a {@code BlockingDeque} and then
 * delegates the error handling to the default error handler.<p>
 * 
 * By default, after-each will assert that no errors were delivered to the error
 * handler. If errors are expected, then the test must consume all errors from
 * the deque returned from {@code errors()}.<p>
 * 
 * Log recording will be activated before starting the server. Records can be
 * retrieved at any time using {@code stopLogRecording()}. However, if the
 * records produced from a server stop is significant, the test ought to stop
 * the server first and then stop recording.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see SimpleEndToEndTest
 * @see DetailedEndToEndTest
 */
// TODO: Move to alpha.nomagichttp package
public abstract class AbstractEndToEndTest
{
    private Logging.Recorder key;
    private HttpServer server;
    private ClientOperations client;
    private final BlockingDeque<Throwable> errors = new LinkedBlockingDeque<>();
    
    @BeforeEach
    void start() throws IOException {
        Logging.setLevel(ALL);
        key = Logging.startRecording();
        
        ErrorHandler collect = (t, r, h) -> {
            errors.add(t);
            throw t;
        };
        
        server = HttpServer.create(collect).start();
        client = new ClientOperations(server);
    }
    
    @AfterEach
    void assertNoErrors() {
        assertThat(errors).isEmpty();
    }
    
    @AfterEach
    void stopNow() throws IOException {
        server.stopNow();
        Logging.stopRecording(key);
    }
    
    /**
     * Returns the server instance.
     * 
     * @return the server instance
     */
    public final HttpServer server() {
        return server;
    }
    
    /**
     * Returns the client instance.
     *
     * @return the client instance
     */
    public final ClientOperations client() {
        return client;
    }
    
    /**
     * Stop log recording.
     * 
     * @return all logged records
     */
    public final Stream<LogRecord> stopLogRecording() {
        return Logging.stopRecording(key);
    }
    
    /**
     * Returns all caught errors.
     * 
     * @return all caught errors
     */
    public final BlockingDeque<Throwable> errors() {
        return errors;
    }
    
    /**
     * Same as {@code errors().poll(3, SECONDS)}.
     * 
     * @return same as {@code errors().poll(3, SECONDS)}
     * 
     * @throws InterruptedException if the waiting is interrupted
     */
    public final Throwable pollError() throws InterruptedException {
        return errors().poll(3, SECONDS);
    }
}