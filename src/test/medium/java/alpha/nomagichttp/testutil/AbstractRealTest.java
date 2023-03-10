package alpha.nomagichttp.testutil;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.message.Response;
import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.channels.AsynchronousCloseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.LogRecord;
import java.util.stream.Stream;

import static alpha.nomagichttp.Config.DEFAULT;
import static alpha.nomagichttp.util.ScopedValues.channel;
import static java.lang.System.Logger.Level.DEBUG;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Will create a {@link #server() server} (on first access) and a
 * {@link #client() client} (on first access).<p>
 * 
 * The client will be configured with the server's port.<p>
 * 
 * Both the server- and client APIs are already pretty easy to use on their own.
 * The added value of this class is packaging both into one class with some
 * added details such as automatic log recording and collection/verification of
 * server errors.<p>
 * 
 * This class will assert on server stop that no errors were delivered to an
 * error handler. If errors are expected, then the test must consume all errors
 * using {@link #pollServerError()}.<p>
 * 
 * <pre>
 *   class MyTest extends AbstractRealTest {
 *      {@literal @}Test
 *       void first() {
 *           // Implicit start of server on access
 *           server().add(someRoute)...
 *           // Implicit connection from client to server
 *           String response = client().writeReadTextUntilEOS(myRequest)...
 *           assertThat(response)...
 *           // Implicit server stop
 *       }
 *      {@literal @}Test
 *       void second() {
 *           // Uses another server instance
 *           server()...
 *       }
 *   }
 * </pre>
 * 
 * By default, after each test; the server is stopped and both the server and
 * client reference is set to null. This can be disabled through a constructor
 * argument "afterEachStop". Sharing the server across tests is good for
 * reducing system taxation when running a large number of tests where test
 * isolation is not needed or perhaps even unwanted. Also useful could be to
 * share a client's persistent connection across many tests.<p>
 * 
 * Log recording will by default be activated for each test. The recorder can be
 * retrieved using {@link #logRecorder()}. Records can be retrieved at any time
 * using {@link #logRecorderStop()}.<p>
 * 
 * Log recording is intended for detailed tests that are awaiting log events
 * and/or running assertions on log records. Tests concerned with performance
 * ought to not use log recording which can be disabled with a constructor
 * argument.
 * 
 * <pre>
 *  {@literal @}TestInstance(PER_CLASS)
 *   class MyRepeatedTest extends AbstractRealTest {
 *       private final Channel conn;
 *       MyRepeatedTest() {
 *           // Save server + client references across tests,
 *           // and disable log recording.
 *           super(false, false);
 *           conn = client().openConnection();
 *       }
 *       // Each message pair is exchange over the same connection
 *       {@literal @}RepeatedTest(999_999_999)
 *       void httpExchange() {
 *           server()...
 *           client().writeReadTextUntilNewlines(...)
 *       }
 *       {@literal @}AfterAll
 *       void afterAll() { //  {@literal <}-- no need to be static (coz of PER_CLASS thing)
 *           stopServer();
 *           conn.close();
 *       }
 *       // Or alternatively, save on annotations and just loop inside one test method lol
 *   }
 * </pre>
 * 
 * This class will in a static {@code @BeforeAll} method named "beforeAll" call
 * {@code Logging.setLevel(ALL)} in order to enable very detailed logging, such
 * as each byte processed in the request head. Tests that run a large number of
 * requests and/or are concerned about performance ought to stop JUnit from
 * calling the method by hiding it.
 * 
 * <pre>
 *   class MyQuietTest extends AbstractRealTest {
 *       static void beforeAll() {
 *           // Use default log level
 *       }
 *       ...
 *   }
 * </pre>
 * 
 * Please note that currently, the {@code TestClient} is not thread-safe nor is
 * this class (well, except for the collection and retrieval of server errors).
 * This will likely change when work commence to add tests that execute requests
 * in parallel.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public abstract class AbstractRealTest
{
    private static final System.Logger LOG
            = System.getLogger(AbstractRealTest.class.getPackageName());
    
    private final boolean afterEachStop,
                          useLogRecording;
    
    // impl permits null values and keys
    private final Map<Class<? extends Exception>, List<Consumer<ClientChannel>>> onError;
    
    /**
     * Same as {@code AbstractRealTest(true, true, true)}.
     * 
     * @see AbstractRealTest
     */
    protected AbstractRealTest() {
        this(true, true);
    }
    
    /**
     * Constructs this object.
     * 
     * @param afterEachStop see {@link AbstractRealTest}
     * @param useLogRecording see {@link AbstractRealTest}
     */
    protected AbstractRealTest(
            boolean afterEachStop,
            boolean useLogRecording)
    {
        this.afterEachStop = afterEachStop;
        this.useLogRecording = useLogRecording;
        this.onError = new HashMap<>();
    }
    
    private Logging.Recorder key;
    private HttpServer server;
    private Future<Void> start;
    private Config config;
    private ErrorHandler custom;
    private BlockingDeque<Exception> errors;
    private int port;
    private TestClient client;
    
    @BeforeAll
    static void beforeAll() {
        Logging.everything();
    }
    
    @BeforeEach
    final void beforeEach(TestInfo test) {
        LOG.log(DEBUG, () -> "Executing " + toString(test));
        if (useLogRecording) {
            key = Logging.startRecording();
        }
    }
    
    @AfterEach
    final void afterEach(TestInfo test)
            throws IOException, InterruptedException
    {
        try {
            if (afterEachStop) {
                client = null;
                stopServer();
            }
        } finally {
            if (useLogRecording) {
                logRecorderStop();
            }
        }
        LOG.log(DEBUG, () -> "Finished " + toString(test));
    }
    
    /**
     * Tailor the server's configuration.<p>
     * 
     * Consider using the more compact form provided by {@link
     * #usingConfiguration()}.
     * 
     * @param config of server
     */
    protected final void usingConfig(Config config) {
        requireServerIsNotRunning();
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
     * Configure an error handler.<p>
     * 
     * The error handler will be called after actions registered
     * with {@code onErrorRun}/{@code onErrorAssert}, meaning that the actions
     * will always run whether the given handler would resolve the error or
     * not.<p>
     * 
     * The error handler will be called before this class's built-in error
     * handler which collects unhandled errors, meaning that if the given
     * handler handles the error, the same error will <i>not</i> be observed
     * through a {@code pollServerError} method.
     * 
     * @param handler error handler
     * 
     * @throws IllegalStateException
     *             if the server has already been started
     */
    protected final void usingErrorHandler(ErrorHandler handler) {
        requireServerIsNotRunning();
        this.custom = handler;
    }
    
    /**
     * Schedules an action to run before error handlers.<p>
     * 
     * An action is useful, for example, to release blocked threads and other
     * low-level hacks installed by the test.<p>
     * 
     * The purpose of the action is <i>not</i> to handle errors, nor can it stop
     * the server's error handler chain from executing. For the purpose of
     * handling errors, use {@link #usingErrorHandler(ErrorHandler)}.<p>
     * 
     * Technically, the action will execute within a server-registered error
     * handler. And so, the action should not throw an exception. In other
     * words, do not run <i>assertions</i> in the action. For this, use
     * {@link #onErrorAssert(Class, Consumer)}.
     * 
     * @param trigger an instance of this type triggers the action
     * @param action to run
     * 
     * @throws IllegalStateException
     *             if the server has already been started
     */
    protected final void onErrorRun(
            Class<? extends Exception> trigger, Runnable action)
    {
        requireNonNull(trigger);
        requireNonNull(action);
        requireServerIsNotRunning();
        onError.compute(trigger, (k, v) -> {
            if (v == null) {
                v = new ArrayList<>();
            }
            v.add(channelIgnored -> action.run());
            return v;
        });
    }
    
    /**
     * Schedules an action that executes assertions on observed exceptions.<p>
     * 
     * The purpose is to run assertions on state depending on a particular
     * exception, even if an error handler is expected to handle the error. For
     * example, to verify that an {@code EndOfStreamException} also shut down
     * the read stream. A throwable thrown by the action will instead of
     * propagating to the server, be collected and consequently fail the
     * test.<p>
     * 
     * The purpose of the action is <i>not</i> to handle errors, nor can it stop
     * the server's error handler chain from executing. For the purpose of
     * handling errors, use {@link #usingErrorHandler(ErrorHandler)}.<p>
     * 
     * @param trigger an instance of this type triggers the action
     * @param action to run
     * 
     * @throws IllegalStateException
     *             if the server has already been started
     */
    protected final void onErrorAssert(
            Class<? extends Exception> trigger, Consumer<ClientChannel> action)
    {
        requireNonNull(trigger);
        requireNonNull(action);
        requireServerIsNotRunning();
        onError.compute(trigger, (k, v) -> {
            if (v == null) {
                v = new ArrayList<>();
            }
            v.add(channel -> {
                try {
                    action.accept(channel);
                } catch (Exception e) {
                    errors.add(e);
                } catch (Throwable t) {
                    errors.add(new Exception(
                        "Test-action returned exceptionally", t));
                }
            });
            return v;
        });
    }
    
    /**
     * Returns the server instance.<p>
     * 
     * If the current server reference is null, a server will be created;
     * listening on a system-picked port.
     * 
     * @return the server instance
     * 
     * @throws IOException if an I/O error occurs
     */
    protected final HttpServer server() throws IOException {
        if (server == null) {
            errors = new LinkedBlockingDeque<>();
            ErrorHandler executeActions = (exc, ch, req) -> {
                onError.forEach((k, v) -> {
                    if (k.isInstance(exc)) {
                        v.forEach(act -> act.accept(channel()));
                    }
                });
                return ch.proceed();
            };
            ErrorHandler collectErrors = (exc, ch, req) -> {
                errors.add(exc);
                return ch.proceed();
            };
            Config arg1 = config != null ? config : DEFAULT;
            ErrorHandler[] arg2 = custom == null ?
                    new ErrorHandler[]{executeActions, collectErrors} :
                    new ErrorHandler[]{executeActions, custom, collectErrors};
            var s = HttpServer.create(arg1, arg2);
            var fut = s.startAsync();
            assertThat(s.isRunning()).isTrue();
            // May wish to save this future and perform other assertions on it
            assertThat(fut).isNotDone();
            port = s.getPort();
            server = s;
            start = fut;
        }
        return server;
    }
    
    /**
     * Returns the cached server port.<p>
     * 
     * This method is useful for testing communication on the port even after
     * the server has stopped (at which point the port can no longer be
     * retrieved from the server instance).
     * 
     * @return see JavaDoc
     * 
     * @throws IllegalStateException
     *             if the server has never started
     */
    protected final int serverPort() {
        if (port == 0) {
            throw new IllegalStateException("Server never started.");
        }
        return port;
    }
    
    /**
     * Returns the client instance.<p>
     * 
     * If the current client reference is null, a client will be created with
     * the server's port. In this case, the server will be accessed using the
     * {@link #server() server} method. Consequently, a test only needs to call
     * this method to set up both.
     * 
     * @return the client instance
     * 
     * @throws IOException if an I/O error occurs
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
     * @throws InterruptedException if interrupted while waiting
     */
    protected final Exception pollServerError() throws InterruptedException {
        return pollServerError(3, SECONDS);
    }
    
    /**
     * Poll an error caught by the error handler, waiting at most 3 seconds.
     * 
     * @param timeout how long to wait before giving up, in units of unit
     * @param unit determining how to interpret the timeout parameter
     * @return an error, or {@code null} if none is available
     * @throws InterruptedException if interrupted while waiting
     */
    protected final Exception pollServerError(long timeout, TimeUnit unit)
            throws InterruptedException
    {
        requireServerIsRunning();
        return errors.poll(timeout, unit);
    }
    
    /**
     * Retrieves and removes the first error caught by the error handler.
     * 
     * @return an error, or {@code null} if none is available
     */
    protected final Exception pollServerErrorNow() {
        requireServerIsRunning();
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
    protected final Stream<LogRecord> logRecorderStop() {
        return Logging.stopRecording(key);
    }
    
    /**
     * Stop server gracefully and await the completion of all HTTP exchanges.<p>
     * 
     * Is NOP if server never started.
     */
    protected final void stopServer() throws IOException, InterruptedException {
        if (server == null) {
            return;
        }
        try {
            server.stop(Duration.ofSeconds(1));
            assertThat(server.isRunning()).isFalse();
            assertThat(errors).isEmpty();
            assertThatServerStoppedNormally(start);
        } finally {
            server = null;
            start = null;
        }
    }
    
    /**
     * Asserts that {@link #pollServerError()} and
     * {@link Logging.Recorder#assertAwaitFirstLogError()} is the same
     * instance.<p>
     * 
     * May be used when test case needs to assert the base error handler was
     * delivered a particular error <i>and</i> logged it (or, someone did).
     * 
     * @return an assert API of sorts
     * 
     * @throws InterruptedException if interrupted while waiting
     */
    protected final AbstractThrowableAssert<?, ? extends Throwable>
            assertThatServerErrorObservedAndLogged() throws InterruptedException
    {
        requireServerIsRunning();
        Throwable t = pollServerError();
        assertSame(t, logRecorder().assertAwaitFirstLogError());
        return assertThat(t);
    }
    
    /**
     * Will gracefully stop the server (to capture all log records) and assert
     * that no log record was found with a level greater than {@code INFO}.
     */
    protected final void assertThatNoWarningOrErrorIsLogged()
            throws IOException, InterruptedException {
        assertThatNoWarningOrErrorIsLoggedExcept();
    }
    
    /**
     * Will gracefully stop the server (to capture all log records) and assert
     * that no log record was found with a level greater than {@code INFO}.
     * 
     * @param excludeClasses classes that are allowed to log waring/error
     */
    protected final void assertThatNoWarningOrErrorIsLoggedExcept(
            Class<?>... excludeClasses)
            throws IOException, InterruptedException
    {
        requireServerIsRunning();
        stopServer();
        
        Set<String> excl = excludeClasses.length == 0 ? Set.of() :
                Stream.of(excludeClasses).map(Class::getName).collect(toSet());
        
        Predicate<String> match = source -> source != null &&
                                  excl.stream().anyMatch(source::startsWith);
        
        assertThat(logRecorderStop()
                .filter(r -> !match.test(r.getSourceClassName()))
                .mapToInt(r -> r.getLevel().intValue()))
                .noneMatch(v -> v > java.util.logging.Level.INFO.intValue());
    }
    
    /**
     * Asserts that the given future completed with an
     * {@code AsynchronousCloseException}.<p>
     * 
     * The {@code AsynchronousCloseException} is how a {@code HttpServer.start}
     * method normally returns, if the server was normally stopped.
     * 
     * @param fut representing the {@code start} method call
     */
    protected static void assertThatServerStoppedNormally(Future<Void> fut) {
        assertThat(fut.isDone()).isTrue();
        assertThatThrownBy(fut::get)
            .isExactlyInstanceOf(ExecutionException.class)
            .hasNoSuppressedExceptions()
            .hasMessage("java.nio.channels.AsynchronousCloseException")
            .cause()
              .isExactlyInstanceOf(AsynchronousCloseException.class)
              .hasNoSuppressedExceptions()
              .hasNoCause()
              .hasMessage(null);
    }
    
    /**
     * Shortcut for opening up the response and set the "Connection: close"
     * header.<p>
     * 
     * This method is useful for tests where the HTTP client would have kept the
     * connection alive which in turn would hinder a graceful server stop from
     * completing in a timely manner.
     * 
     * @param rsp response
     * @return with command set
     */
    protected static Response setHeaderConnectionClose(Response rsp) {
        return rsp.toBuilder().header("Connection", "close").build();
    }
    
    private static String toString(TestInfo test) {
        Method m = test.getTestMethod().get();
        String n = m.getDeclaringClass().getSimpleName() + "." + m.getName() + "()";
        return n + " -> " + test.getDisplayName();
    }
    
    private void requireServerIsRunning() {
        if (server == null) {
            throw new IllegalStateException("Server is not running.");
        }
    }
    
    private void requireServerIsNotRunning() {
        if (server != null) {
            throw new IllegalStateException("Server is running.");
        }
    }
}