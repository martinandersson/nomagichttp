package alpha.nomagichttp.testutil;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.message.Response;
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
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
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
 * first access), the latter configured with the server's port.<p>
 * 
 * Both the server- and client APIs are pretty easy-to-use already. The added
 * value of this class are related details such as automatic- log recording and
 * collection/memorization of server errors.<p>
 * 
 * This class will assert on server stop that no errors were delivered to the
 * error handler. If errors are expected, then the test must consume all errors
 * using {@link #pollServerError()}.<p>
 * 
 * After each test - by default - the server will be stopped and the
 * server+client reference will be set to null ({@linkplain
 * TestClient#openConnection() a persistent connection} opened by the test must
 * be closed by the test). This is great for test isolation.
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
 * The automatic stop and set-reference-to-null actions can be disabled through
 * a constructor argument, and thus, the life of the server and/or client can be
 * extended at the discretion of the subclass. An extended server scope can be
 * used to save on port resources when running a large number of test cases
 * where isolation is not needed or perhaps even unwanted. A client with an
 * extended scoped is useful when it is desired to have many tests share a
 * persistent connection.<p>
 * 
 * Log recording will by default be activated for each test. The recorder can be
 * retrieved using {@link #logRecorder()}. Records can be retrieved at any time
 * using {@link #stopLogRecording()}.<p>
 * 
 * Log recording is intended for detailed tests that are awaiting log events
 * and/or running asserts on log records. Tests concerned with performance ought
 * to not use log recording which can be disabled with a constructor
 * argument.
 * 
 * <pre>
 *  {@literal @}TestInstance(PER_CLASS)
 *   class MyRepeatedTest extends AbstractRealTest {
 *       private final Channel conn;
 *       MyRepeatedTest() {
 *           // Save server + client references across tests,
 *           // and disable log recording.
 *           super(false, false, false);
 *           conn = client().openConnection();
 *       }
 *      {@literal @}RepeatedTest(999_999_999)
 *       void httpExchange() {
 *           server()...
 *           client().writeReadTextUntilNewlines(...)
 *       }
 *      {@literal @}AfterAll
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
 * as each char processed in the request head processor. Tests that run a large
 * number of requests and/or are concerned about performance ought to stop JUnit
 * from calling the method by hiding it.
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
 * This will likely change when work commence to add tests that executes
 * requests in parallel.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public abstract class AbstractRealTest
{
    private final static Logger LOG =  Logger.getLogger(AbstractRealTest.class.getPackageName());
    
    private final boolean stopServerAfterEach,
                          nullifyClientAfterEach,
                          useLogRecording;
    
    // impl permits null values and keys
    private final Map<Class<? extends Throwable>, List<Consumer<ClientChannel>>> onError;
    
    /**
     * Same as {@code AbstractRealTest(true, true, true)}.
     * 
     * @see AbstractRealTest
     */
    protected AbstractRealTest() {
        this(true, true, true);
    }
    
    /**
     * Constructs this object.
     * 
     * @param stopServerAfterEach see {@link AbstractRealTest}
     * @param nullifyClientAfterEach see {@link AbstractRealTest}
     * @param useLogRecording see {@link AbstractRealTest}
     */
    protected AbstractRealTest(
            boolean stopServerAfterEach,
            boolean nullifyClientAfterEach,
            boolean useLogRecording)
    {
        this.stopServerAfterEach = stopServerAfterEach;
        this.nullifyClientAfterEach = nullifyClientAfterEach;
        this.useLogRecording = useLogRecording;
        this.onError = new HashMap<>();
    }
    
    private Logging.Recorder key;
    private HttpServer server;
    private Config config;
    private ErrorHandler custom;
    private BlockingDeque<Throwable> errors;
    private int port;
    private TestClient client;
    
    @BeforeAll
    static void beforeAll() {
        Logging.setLevel(ALL);
    }
    
    @BeforeEach
    final void beforeEach(TestInfo test) {
        LOG.log(INFO, () -> "Executing " + toString(test));
        if (useLogRecording) {
            key = Logging.startRecording();
        }
    }
    
    @AfterEach
    final void afterEach(TestInfo test) {
        try {
            if (nullifyClientAfterEach) {
                client = null;
            }
            if (stopServerAfterEach) {
                stopServer();
            }
        } finally {
            if (useLogRecording) {
                stopLogRecording();
            }
        }
        LOG.log(INFO, () -> "Finished " + toString(test));
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
     * Pre-empty this class' error handler with a custom one.
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
     * 
     * @throws IOException if an I/O error occurs
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
    
    /**
     * Stop server gracefully and await the completion of all HTTP exchanges.<p>
     * 
     * Is NOP if server never started.
     */
    protected final void stopServer() {
        if (server == null) {
            return;
        }
        try {
            // Not stopNow() and then move on because...
            //    asynchronous/delayed logging from active exchanges may
            //    spill into a subsequent new test and consequently and
            //    wrongfully fail that test if it were to run assertions on
            //    the server log (which many tests do).
            // Awaiting stop() also...
            //    boosts our confidence significantly that children are
            //    never leaked.
            assertThat(server.stop())
                    .succeedsWithin(1, SECONDS)
                    .isNull();
            assertThat(errors)
                    .isEmpty();
        } finally {
            server = null;
        }
    }
    
    /**
     * Shortcut for {@link Logging.Recorder#await(Level, String)}.
     * 
     * @param level record level predicate
     * @param messageStartsWith record message predicate
     * 
     * @throws NullPointerException   if any arg is {@code null}
     * @throws IllegalStateException  if server hasn't started once
     * @throws InterruptedException   if the current thread is interrupted while waiting
     */
    protected final void awaitLog(System.Logger.Level level, String messageStartsWith)
            throws InterruptedException
    {
        requireServerStartedOnce();
        assertTrue(logRecorder().await(toJUL(level), messageStartsWith));
    }
    
    /**
     * Shortcut for {@link Logging.Recorder#await(Level, String, Class)}.
     * 
     * @param level record level predicate
     * @param messageStartsWith record message predicate
     * @param error record error predicate (record's error must be instance of)
     * 
     * @throws NullPointerException   if any arg is {@code null}
     * @throws IllegalStateException  if server hasn't started once
     * @throws InterruptedException   if the current thread is interrupted while waiting
     */
    protected final void awaitLog(
            System.Logger.Level level,
            String messageStartsWith,
            Class<? extends Throwable> error)
                throws InterruptedException
    {
        requireServerStartedOnce();
        assertTrue(logRecorder().await(toJUL(level), messageStartsWith, error));
    }
    
    // TODO: Move all "await" util methods to the [Log-]Recorder class.
    //       Concrete test class do assertXxx() or consider extending Recorder
    //       API to offer an assert-service, or have his API throw an exception
    //       instead of returning true/false.
    
    /**
     * Await first logged error.
     * 
     * @return the error
     * 
     * @throws NullPointerException   if {@code filter} is {@code null}
     * @throws IllegalStateException  if server hasn't started once
     * @throws InterruptedException   if the current thread is interrupted while waiting
     */
    protected final Throwable awaitFirstLogError() throws InterruptedException {
        return awaitFirstLogError(Throwable.class);
    }
    
    /**
     * Await first logged error that is an instance of the given type.
     * 
     * @param filter type expected
     * @return the error
     * 
     * @throws NullPointerException   if {@code filter} is {@code null}
     * @throws IllegalStateException  if server hasn't started once
     * @throws InterruptedException   if the current thread is interrupted while waiting
     */
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
    
    /**
     * Create an AssertJ Tuple consisting of a log- level and message.
     * 
     * @param level of log record
     * @param msg of log record
     * @return a tuple
     * 
     * @throws NullPointerException
     *             if {@code level} is {@code null}, perhaps also for {@code msg}
     */
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
     * 
     * @throws InterruptedException if interrupted while waiting
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
     */
    // TODO: boolean to check also record throwable
    protected final void assertThatNoWarningOrErrorIsLogged() {
        assertThatNoWarningOrErrorIsLoggedExcept();
    }
    
    /**
     * Will gracefully stop the server (to capture all log records) and assert
     * that no log record was found with a level greater than {@code INFO}.
     * 
     * @param excludeClasses classes that are allowed to log waring/error
     */
    // TODO: boolean to check also record throwable
    protected final void assertThatNoWarningOrErrorIsLoggedExcept(Class<?>... excludeClasses) {
        requireServerStartedOnce();
        stopServer();
        
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
    
    /**
     * Shortcut for opening up the response and set the must-close-after-write
     * command.<p>
     * 
     * Useful for tests where the HTTP client would keep the connection alive
     * which would hinder a graceful server stop from completing in a timely
     * manner.
     * 
     * @param rsp response
     * @return with command set
     */
    protected static Response setMustCloseAfterWrite(Response rsp) {
        return rsp.toBuilder().mustCloseAfterWrite(true).build();
    }
    
    private static String toString(TestInfo test) {
        Method m = test.getTestMethod().get();
        String n = m.getDeclaringClass().getSimpleName() + "." + m.getName() + "()";
        return n + " -> " + test.getDisplayName();
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