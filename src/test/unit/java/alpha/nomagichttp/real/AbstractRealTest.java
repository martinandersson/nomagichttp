package alpha.nomagichttp.real;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.testutil.Logging;
import alpha.nomagichttp.testutil.TestClient;
import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static alpha.nomagichttp.Config.DEFAULT;
import static alpha.nomagichttp.testutil.Logging.toJUL;
import static java.lang.System.Logger.Level.ALL;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Will setup a {@link #server()} (on first access) and a {@link #client()} (on
 * first access), the latter configured with the server's port. Both scoped to
 * each test.<p>
 * 
 * The server has no routes added and so most test cases will probably have to
 * add those manually.<p>
 * 
 * This class registers en error handler which collects all server exceptions
 * into a {@code BlockingDeque} and then delegates the error handling to the
 * default error handler (by re-throw).<p>
 * 
 * By default, after-each will assert that no errors were delivered to the error
 * handler. If errors are expected, then the test must consume all errors from
 * the deque using {@link #pollServerError()}.<p>
 * 
 * Log recording will be activated for each test. The recorder can be retrieved
 * using {@link #logRecorder()}. Records can be retrieved at any time using
 * {@link #stopLogRecording()}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
abstract class AbstractRealTest
{
    final Logger LOG =  Logger.getLogger(getClass().getPackageName());
    
    private Logging.Recorder key;
    private HttpServer server;
    private Config config;
    private ErrorHandler custom;
    private BlockingDeque<Throwable> errors;
    private int port;
    private TestClient client;
    
    @BeforeEach
    void beforeEach(TestInfo test) {
        Logging.setLevel(ALL);
        LOG.log(INFO, "Executing " + toString(test));
        key = Logging.startRecording();
    }
    
    @AfterEach
    void afterEach(TestInfo test) {
        try {
            if (server != null) {
                // stop() instead of stopNow() because..
                //   1) Asynchronous logging may spill into a subsequent new test
                //      and consequently and wrongfully fail that test if it run
                //      assertions on the log.
                //   2) It boosts our confidence significantly if we know the
                //      server will manage to cleanly reach a full stop after
                //      each test - i.e. no open/leaked children!
                assertThat(server.stop())
                        .succeedsWithin(1, SECONDS)
                        .isNull();
                assertThat(errors).isEmpty();
            }
        } finally {
            stopLogRecording();
        }
        LOG.log(INFO, "Finished " + toString(test));
    }
    
    /**
     * Tailor the server's configuration.
     * 
     * @param config of server
     */
    protected final void usingConfig(Config config) {
        requireServerNotStarted();
        this.config = config;
    }
    
    /**
     * Short-cut for
     * <pre>{@code
     *  Config modified = current.toBuilder().
     *     <all set calls goes here>
     *     .build();
     *  usingConfig(modified.build());
     * }</pre>
     * 
     * Or, what it would look like on call-site:
     * <pre>
     *  usingConfiguration()
     *      .thisConfig(newVal)
     *      .thatConfig(newVal);
     * </pre>
     * ...and that's it.
     * 
     * @return a proxy intercepting the setter calls
     */
    protected final Config.Builder usingConfiguration() {
        InvocationHandler handler = (proxy, method, args) -> {
            Config.Builder b = config == null ?
                    DEFAULT.toBuilder() : config.toBuilder();
            if (method.getName().equals("build")) {
                throw new UnsupportedOperationException(
                        "Don't call build() explicitly. " +
                        "Config will be built and used for each new value set.");
            }
            b = (Config.Builder) method.invoke(b, args);
            usingConfig(b.build());
            return proxy;
        };
        return (Config.Builder) Proxy.newProxyInstance(Config.Builder.class.getClassLoader(),
                new Class<?>[] { Config.Builder.class },
                handler);
    }
    
    /**
     * Set a custom error handler to use.
     * 
     * @param handler error handler
     */
    protected final void usingErrorHandler(ErrorHandler handler) {
        requireServerNotStarted();
        this.custom = handler;
    }
    
    /**
     * Returns the server instance.
     * 
     * @return the server instance
     */
    protected final HttpServer server() throws IOException {
        if (server == null) {
            errors = new LinkedBlockingDeque<>();
            ErrorHandler collect = (t, r, c, h) -> {
                errors.add(t);
                throw t;
            };
            Config arg1 = config != null ? config : DEFAULT;
            ErrorHandler[] arg2 = custom != null ?
                    new ErrorHandler[]{custom, collect} :
                    new ErrorHandler[]{collect};
            server = HttpServer.create(arg1, arg2).start();
            port = server.getLocalAddress().getPort();
        }
        return server;
    }
    
    /**
     * Returns the cached server port.<p>
     * 
     * This method is useful for testing communication on the port even after
     * the server has stopped (at which point the port can no longer be
     * retrieved from the server).
     * 
     * @return the cached server port
     */
    protected final int serverPort() {
        requireServerStartedOnce();
        return port;
    }
    
    /**
     * Returns the client instance.
     * 
     * @return the client instance
     */
    protected final TestClient client() throws IOException {
        if (client == null) {
            client = new TestClient(server());
        }
        return client;
    }
    
    /**
     * Poll an error caught by the error handler, waiting at most 3 seconds.
     * 
     * @return an error, or {@code null} if none is available
     * 
     * @throws InterruptedException if interrupted while waiting
     */
    protected final Throwable pollServerError() throws InterruptedException {
        requireServerStartedOnce();
        return errors.poll(3, SECONDS);
    }
    
    /**
     * Returns the test log recorder.
     * 
     * @return the test log recorder
     */
    protected final Logging.Recorder logRecorder() {
        return key;
    }
    
    /**
     * Stop log recording.
     * 
     * @return all logged records
     */
    protected final Stream<LogRecord> stopLogRecording() {
        return Logging.stopRecording(key);
    }
    
    protected final void awaitLog(System.Logger.Level level, String messageStartsWith)
            throws InterruptedException
    {
        assertTrue(logRecorder().await(toJUL(level), messageStartsWith));
    }
    
    protected final Throwable awaitFirstLogError()
            throws InterruptedException
    {
        AtomicReference<Throwable> thr = new AtomicReference<>();
        assertTrue(logRecorder().await(rec -> {
            var t = rec.getThrown();
            if (t != null) {
                thr.set(t);
                return true;
            }
            return false;
        }));
        return thr.get();
    }
    
    /**
     * Stop log recording and assert the records.
     *
     * @param values produced by {@link #rec(System.Logger.Level, String)}
     */
    protected final void assertThatLogContainsOnlyOnce(Tuple... values) {
        assertThat(stopLogRecording())
                .extracting(LogRecord::getLevel, LogRecord::getMessage)
                .containsOnlyOnce(values);
    }
    
    protected static Tuple rec(System.Logger.Level level, String msg) {
        return tuple(toJUL(level), msg);
    }
    
    /**
     * Stop log recording and assert no log record contains a throwable.
     */
    protected final void assertThatNoErrorWasLogged() {
        var logs = stopLogRecording();
        assertThat(logs).extracting(r -> {
            var t = r.getThrown();
            if (t != null) {
                LOG.log(WARNING, () ->
                    "Log record that has a throwable also has this message: " +
                    r.getMessage());
            }
            return t;
        }).containsOnlyNulls();
    }
    
    /**
     * Short-cut for {@link #pollServerError()} and
     * {@link #awaitFirstLogError()} with an extra assert that the error
     * instance observed is the error instance logged.<p>
     * 
     * May be used when test case needs to assert the default error handler was
     * delivered a particular error <i>and</i> logged it (or, someone did).
     * 
     * @return an assert API of sorts
     */
    protected final AbstractThrowableAssert<?, ? extends Throwable>
            assertThatServerErrorObservedAndLogged() throws InterruptedException
    {
        Throwable t = pollServerError();
        assertSame(t, awaitFirstLogError());
        return assertThat(t);
    }
    
    /**
     * Waits for at most 3 seconds on the server log to indicate a child was
     * accepted.
     * 
     * @throws InterruptedException
     *             if the current thread is interrupted while waiting
     */
    protected final void awaitChildAccept() throws InterruptedException {
        requireServerStartedOnce();
        assertTrue(logRecorder().await(FINE, "Accepted child:"));
    }
    
    /**
     * Waits for at most 3 seconds on the server log to indicate a child was
     * closed.
     * 
     * @throws InterruptedException
     *             if the current thread is interrupted while waiting
     */
    protected final void awaitChildClose() throws InterruptedException {
        requireServerStartedOnce();
        assertTrue(logRecorder().await(FINE, "Closed child:"));
    }
    
    private static String toString(TestInfo test) {
        Method m = test.getTestMethod().get();
        return m.getDeclaringClass().getSimpleName() + "." + m.getName() + "()";
    }
    
    private void requireServerNotStarted() {
        if (server != null) {
            throw new IllegalStateException("Server already started.");
        }
    }
    
    private void requireServerStartedOnce() {
        if (server == null) {
            throw new IllegalStateException(
                    "Server never started. Call server() first.");
        }
    }
}