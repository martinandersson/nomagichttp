package alpha.nomagichttp.real;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.testutil.Logging;
import alpha.nomagichttp.testutil.TestClient;
import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static alpha.nomagichttp.Config.DEFAULT;
import static alpha.nomagichttp.testutil.Logging.toJUL;
import static java.lang.System.Logger.Level.ALL;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static java.util.stream.Collectors.toSet;
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
    // permits null values and keys
    private final Map<Class<? extends Throwable>, List<Consumer<ClientChannel>>> onError = new HashMap<>();
    private int port;
    private TestClient client;
    
    @BeforeAll
    static void beforeAll() {
        Logging.setLevel(ALL);
    }
    
    @BeforeEach
    void beforeEach(TestInfo test) {
        LOG.log(INFO, "Executing " + toString(test));
        key = Logging.startRecording();
    }
    
    @AfterEach
    void afterEach(TestInfo test) {
        try {
            if (server != null) {
                // Not stopNow() and then move on because...
                //    asynchronous/delayed logging from active exchanges may
                //    spill into a subsequent new test and consequently and
                //    wrongfully fail that test if it were to run assertions on
                //    the server log (which many tests do).
                // Await stop() also...
                //    boosts our confidence significantly that children are
                //    never leaked.
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
     * Proxied short-cut equivalent to
     * <pre>{@code
     *  Config modified = current.toBuilder().
     *     <all set calls goes here>
     *     .build();
     *  usingConfig(modified.build());
     * }</pre>
     * 
     * Now, this is all what the call-site has to do:
     * <pre>
     *  usingConfiguration()
     *      .thisConfig(newVal)
     *      .thatConfig(newVal);
     * </pre>
     * ...and the new values are automagically applied.
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
     * Schedule an action to run on error handler observed exceptions.<p>
     * 
     * Useful, for example, to release blocked threads and other low-level hacks
     * employed by test cases.<p>
     * 
     * The action executes within a server-registered error handler. And so, the
     * action should not throw an exception. If it does, then the exception will
     * likely be feed right back to the error handler(s), which is probably not
     * what the test intended. Furthermore, if the action is triggered on {@code
     * Throwable} (i.e. for all exceptions) and always throws the same exception
     * on each invocation, then we'll end up in a loop which only breaks when
     * all error attempts run out.<p>
     * 
     * I.e., do not run <i>asserts</i> in the action. For this reason, use
     * {@link #onErrorAssert(Class, Consumer)} instead.
     * 
     * @param trigger exception type (instance of)
     * @param action to run
     */
    protected final void onErrorRun(Class<? extends Throwable> trigger, Runnable action) {
        requireNonNull(trigger);
        requireNonNull(action);
        requireServerNotStarted();
        onError.compute(trigger, (k, v) -> {
            if (v == null) {
                v = new ArrayList<>();
            }
            v.add(channelIgnored -> action.run());
            return v;
        });
    }
    
    /**
     * Execute assert statements on error handler observed exceptions.<p>
     * 
     * If the action throws a throwable (any type), then the new error is not
     * re-thrown to the server but instead added to the internally held
     * collection of server errors and will consequently fail the test unless
     * polled explicitly.
     * 
     * @param trigger exception type (instance of)
     * @param action to run
     */
    protected final void onErrorAssert(Class<? extends Throwable> trigger, Consumer<ClientChannel> action) {
        requireNonNull(trigger);
        requireNonNull(action);
        requireServerNotStarted();
        onError.compute(trigger, (k, v) -> {
            if (v == null) {
                v = new ArrayList<>();
            }
            v.add(channel -> {
                try {
                    action.accept(channel);
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
            return v;
        });
    }
    
    /**
     * Returns the server instance.
     * 
     * @return the server instance
     */
    protected final HttpServer server() throws IOException {
        if (server == null) {
            errors = new LinkedBlockingDeque<>();
            ErrorHandler collectAndExecute = (t, channel, r, h) -> {
                errors.add(t);
                onError.forEach((k, v) -> {
                    if (k.isInstance(t)) {
                        v.forEach(action -> action.accept(channel));
                    }
                });
                throw t;
            };
            Config arg1 = config != null ? config : DEFAULT;
            ErrorHandler[] arg2 = custom != null ?
                    new ErrorHandler[]{custom, collectAndExecute} :
                    new ErrorHandler[]{collectAndExecute};
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
     * Retrieves and removes the first error caught by the error handler.
     * 
     * @return an error, or {@code null} if none is available
     */
    protected final Throwable pollServerErrorNow() {
        requireServerStartedOnce();
        return errors.pollFirst();
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
        requireServerStartedOnce();
        assertTrue(logRecorder().await(toJUL(level), messageStartsWith));
    }
    
    protected final void awaitLog(System.Logger.Level level, String messageStartsWith, Class<? extends Throwable> error)
            throws InterruptedException
    {
        requireServerStartedOnce();
        assertTrue(logRecorder().await(toJUL(level), messageStartsWith, error));
    }
    
    protected final Throwable awaitFirstLogError() throws InterruptedException {
        return awaitFirstLogError(Throwable.class);
    }
    
    protected final Throwable awaitFirstLogError(Class<? extends Throwable> filter)
            throws InterruptedException
    {
        requireServerStartedOnce();
        requireNonNull(filter);
        AtomicReference<Throwable> thr = new AtomicReference<>();
        assertTrue(logRecorder().await(rec -> {
            var t = rec.getThrown();
            if (filter.isInstance(t)) {
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
        requireServerStartedOnce();
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
        requireServerStartedOnce();
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
        requireServerStartedOnce();
        Throwable t = pollServerError();
        assertSame(t, awaitFirstLogError());
        return assertThat(t);
    }
    
    /**
     * Will gracefully stop the server (to capture all log records) and assert
     * that no log record was found with a level greater than {@code INFO}.
     *
     * @throws IOException if server stop fails
     * @throws ExecutionException if waiting for server stop fails
     * @throws InterruptedException if interrupted while waiting
     * @throws TimeoutException if waiting for server stop fails
     */
    // TODO: boolean to check also record throwable
    protected final void assertThatNoWarningOrErrorIsLogged()
            throws IOException, ExecutionException, InterruptedException, TimeoutException
    {
        assertThatNoWarningOrErrorIsLoggedExcept();
    }
    
    /**
     * Will gracefully stop the server (to capture all log records) and assert
     * that no log record was found with a level greater than {@code INFO}.
     * 
     * @param excludeClasses classes that are allowed to log waring/error
     * 
     * @throws IOException if server stop fails
     * @throws ExecutionException if waiting for server stop fails
     * @throws InterruptedException if interrupted while waiting
     * @throws TimeoutException if waiting for server stop fails
     */
    // TODO: boolean to check also record throwable
    protected final void assertThatNoWarningOrErrorIsLoggedExcept(Class<?>... excludeClasses)
            throws IOException, ExecutionException, InterruptedException, TimeoutException
    {
        server().stop().toCompletableFuture().get(1, SECONDS);
        
        Set<String> excl = excludeClasses.length == 0 ? Set.of() :
                Stream.of(excludeClasses).map(Class::getName).collect(toSet());
        
        Predicate<String> match = source -> source != null &&
                                  excl.stream().anyMatch(source::startsWith);
        
        assertThat(stopLogRecording()
                .filter(r -> !match.test(r.getSourceClassName()))
                .mapToInt(r -> r.getLevel().intValue()))
                .noneMatch(v -> v > INFO.intValue());
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