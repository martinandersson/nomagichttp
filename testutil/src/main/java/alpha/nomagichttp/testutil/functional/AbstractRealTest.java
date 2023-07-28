package alpha.nomagichttp.testutil.functional;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.testutil.LogRecorder;
import alpha.nomagichttp.testutil.Logging;
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
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.LogRecord;
import java.util.stream.Stream;

import static alpha.nomagichttp.Config.DEFAULT;
import static alpha.nomagichttp.testutil.LogRecords.rec;
import static alpha.nomagichttp.util.ScopedValues.channel;
import static java.lang.System.Logger.Level.DEBUG;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Operates a {@link #server() server} and a {@link #client() client}.<p>
 * 
 * Both the server and the client are created on first access. The client will
 * be configured with the server's port.<p>
 * 
 * Both the server and client APIs are easy to use on their own. The added value
 * of this class is packaging both into one class, together with some extra
 * features such as enabled log recording, and collection/verification of server
 * errors.<p>
 * 
 * When the server is stopped, this class asserts that no errors were delivered
 * to an error handler, that no log record was logged on a level greater than
 * {@code INFO}, and that no log record has a throwable.<p>
 * 
 * If any kind of errors and/or warnings are expected, then the test must
 * consume exceptions using {@link #pollServerError()}, or take records from the
 * recorder using {@link LogRecorder#assertTake(System.Logger.Level, String)}.
 * If it is expected that an exception is both handled and logged, one can use
 * {@link #assertThatServerErrorObservedAndLogged()}.<p>
 * 
 * {@snippet :
 *   class MyTest extends AbstractRealTest {
 *       @Test
 *       void first() {
 *           // Implicit start of server on access
 *           server().add(someRoute)...
 *           // Implicit connection from client to server
 *           String response = client().writeReadTextUntilEOS(myRequest)...
 *           assertThat(response)...
 *           // Implicit server stop
 *       }
 *       @Test
 *       void second() {
 *           // Is using another server instance
 *           server()...
 *       }
 *   }
 * }
 * 
 * By default (using the no-arg constructor), after each test, the server is
 * stopped and both the server and client references are set to null
 * (test isolation). This can be disabled through a constructor argument
 * {@code afterEachStop}.<p>
 * 
 * By default, a {@link #logRecorder()} is activated for each test. Recording is
 * intended for detailed tests that are awaiting log events and/or running
 * assertions on the records. Tests concerned with performance ought to disable
 * recording by means of the constructor argument {@code useLogRecording}.<p>
 * 
 * {@snippet :
 *   @TestInstance(PER_CLASS)
 *   class MyRepeatedTest extends AbstractRealTest {
 *       private final Channel conn;
 *       MyRepeatedTest() {
 *           // Reuse server + client, and disable log recording
 *           super(false, false);
 *           server();
 *           conn = client().openConnection();
 *       }
 *       // Each message pair is exchanged over the same connection
 *       @RepeatedTest(999_999_999)
 *       void httpExchange() {
 *           client().writeReadTextUntilNewlines("GET / HTTP/1.1\r\n\r\n");
 *           ...
 *       }
 *       @AfterAll
 *       void afterAll() { //  {@literal <}-- no need to be static (coz of PER_CLASS thing)
 *           stopServer();
 *           conn.close();
 *       }
 *       // Or alternatively, save on annotations and loop inside a test method lol
 *   }
 * }
 * 
 * This class will in a static {@code @BeforeAll} method named "beforeAll" call
 * {@code Logging.setLevel(ALL)} in order to enable very detailed logging, such
 * as each byte processed in the request head. Tests that run a large number of
 * requests and/or are concerned with performance ought to stop JUnit from
 * calling the method by hiding it.<p>
 * 
 * {@snippet :
 *   class MyQuietTest extends AbstractRealTest {
 *       static void beforeAll() {
 *           // Use default log level
 *       }
 *       ...
 *   }
 * }
 * 
 * Both of the former examples have been combined into
 * {@code AbstractLargeRealTest}.<p>
 * 
 * Please note that currently, the {@code TestClient} is not thread-safe nor is
 * this class. This may change when work commences to add tests that execute
 * requests in parallel.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public abstract class AbstractRealTest
{
    /**
     * The server stop's graceful period, in seconds.<p>
     * 
     * Used by {@link #stopServer(boolean)}.
     */
    protected static final int STOP_GRACEFUL_SECONDS = 1;
    
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
    
    private LogRecorder recorder;
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
            recorder = LogRecorder.startRecording();
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
     *  Config modified = currentConf.toBuilder().
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
     * handling errors, use {@link #usingErrorHandler(ErrorHandler)}.
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
        requireServerStarted();
        return port;
    }
    
    /**
     * Returns the client instance.<p>
     * 
     * If the current client reference is null, a client will be created with
     * the server's port.
     * 
     * @return the client instance
     * 
     * @throws IllegalStateException
     *             if the server has never started
     * @throws IOException
     *             if an I/O error occurs
     */
    protected final TestClient client() throws IOException {
        requireServerStarted();
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
     * Returns the log recorder instance.
     * 
     * @return see JavaDoc
     * 
     * @throws IllegalStateException
     *             if log recording is not active
     */
    protected final LogRecorder logRecorder() {
        if (recorder == null) {
            throw new IllegalStateException("Log recording is not active.");
        }
        return recorder;
    }
    
    /**
     * Stop log recording.
     * 
     * @return all logged records
     */
    private Stream<LogRecord> logRecorderStop() {
        return recorder.stopRecording();
    }
    
    /**
     * Equivalent to {@link #stopServer(boolean) stopServer(true)}.
     * 
     * @throws IOException
     *           on I/O error
     * @throws InterruptedException
     *           if interrupted while waiting on client connections to terminate
     */
    protected final void stopServer() throws IOException, InterruptedException {
        stopServer(true);
    }
    
    /**
     * Stop the server and await, at most {@value STOP_GRACEFUL_SECONDS}
     * seconds, the completion of all HTTP exchanges.<p>
     * 
     * Is NOP if the server never started or has stopped already.<p>
     * 
     * The parameter {@code clean} has an effect only if log recording is
     * active, and then it affects what debug message is logged by the server
     * implementation.
     * 
     * @param clean asserts the log; exchanges finish within the graceful period
     * 
     * @throws IOException
     *           on I/O error
     * @throws InterruptedException
     *           if interrupted while waiting on client connections to terminate
     */
    protected final void stopServer(boolean clean) throws IOException, InterruptedException {
        if (server == null) {
            return;
        }
        try {
            server.stop(Duration.ofSeconds(STOP_GRACEFUL_SECONDS));
            assertThat(server.isRunning()).isFalse();
            assertThat(errors).isEmpty();
            assertThatServerStopsNormally(start);
            if (recorder != null) {
                logRecorder().assertContainsOnlyOnce(rec(DEBUG, clean ?
                    "All exchanges finished within the graceful period." :
                    "Graceful deadline expired; shutting down scope."));
                logRecorder().assertNoThrowableNorWarning();
            }
        } finally {
            server = null;
            start = null;
        }
    }
    
    /**
     * Asserts that {@link #pollServerError()} and
     * {@link LogRecorder#assertAwaitTakeError()} is the same instance.<p>
     * 
     * May be used when a test case needs to assert that the base error handler
     * was delivered a particular error <i>and</i> logged it (or, someone did).
     * 
     * @return an assert API of sorts
     * 
     * @throws InterruptedException
     *             if interrupted while waiting
     * @throws IllegalStateException
     *             if the server is not running, or
     *             if log recording is not active
     */
    protected final AbstractThrowableAssert<?, ? extends Throwable>
            assertThatServerErrorObservedAndLogged() throws InterruptedException
    {
        requireServerIsRunning();
        return logRecorder()
                   .assertAwaitTakeError()
                   .isSameAs(pollServerError());
    }
    
    /**
     * Asserts that the given future completes with an
     * {@code AsynchronousCloseException}.<p>
     * 
     * The {@code AsynchronousCloseException} is how a {@code HttpServer.start}
     * method normally returns, if the server was normally stopped.<p>
     * 
     * There is no defined order which method returns first;
     * {@code HttpServer.start()} or {@code stop()}. And so, this method awaits
     * at most 1 second for the future to complete. In most cases though, the
     * actual waiting time (if any) will probably be more like a millisecond or
     * two.
     * 
     * @param fut representing the {@code start} method call
     */
    protected static void assertThatServerStopsNormally(Future<Void> fut) {
        assertThatThrownBy(() -> fut.get(1, SECONDS))
            .isExactlyInstanceOf(ExecutionException.class)
            .hasNoSuppressedExceptions()
            .hasMessage("java.nio.channels.AsynchronousCloseException")
            .cause()
              .isExactlyInstanceOf(AsynchronousCloseException.class)
              .hasNoSuppressedExceptions()
              .hasNoCause()
              .hasMessage(null);
    }
    
    private static String toString(TestInfo test) {
        Method m = test.getTestMethod().get();
        String n = m.getDeclaringClass().getSimpleName() + "." + m.getName() + "()";
        return n + " -> " + test.getDisplayName();
    }
    
    private void requireServerStarted() {
        if (port == 0) {
            throw new IllegalStateException("Server never started.");
        }
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