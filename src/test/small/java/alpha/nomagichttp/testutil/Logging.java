package alpha.nomagichttp.testutil;

import alpha.nomagichttp.HttpServer;
import org.assertj.core.groups.Tuple;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static alpha.nomagichttp.testutil.LogRecords.toJUL;
import static java.lang.System.Logger.Level;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Stream.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Logging utilities.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class Logging
{
    private static final System.Logger LOG
            = System.getLogger(Logging.class.getPackageName());
    
    private static final AtomicBoolean
            WEIRD_GUY_REMOVED = new AtomicBoolean(),
            GOOD_GUY_ADDED    = new AtomicBoolean();
    
    private Logging() {
        // Empty
    }
    
    /**
     * Log everything.<p>
     * 
     * An invocation of this method behaves in exactly the same way as the
     * invocation
     * <pre>
     *     Logging.{@link #setLevel(Class, Level)
     *       setLevel}(HttpServer.class, level.ALL);
     * </pre>
     */
    public static void everything() {
        setLevel(HttpServer.class, Level.ALL);
    }
    
    /**
     * Set logging level for the package of a given component.<p>
     * 
     * This method is assumed to be used by test cases interested to increase
     * the logging output, purposefully to aid a human operator's study of
     * Gradle's test report. Therefore, this method also takes the liberty of
     * hacking the log environment a bit.<p>
     * 
     * Firstly, by default, the root logger's console handler will dump all
     * records from {@code INFO} and above on {@code System.err}. Having all of
     * them show up as an "error" in Gradle's report is less... intuitive? So
     * an attempt is made to remove this guy.<p>
     * 
     * Secondly, all found console handlers of the component's logger will also
     * have the new level set (digging recursively through parents). Normally,
     * there will be none, so a new console handler of the target level is
     * installed on the root logger, which also formats all records elegantly
     * and writes them on {@code System.out}.<p>
     * 
     * Voila! The end result ought to be a much prettier and useful Gradle test
     * report.
     * 
     * @param component to extract package from
     * @param level to set
     * 
     * @throws NullPointerException if any argument is {@code null}
     */
    public static void setLevel(Class<?> component, Level level) {
        final java.util.logging.Level impl = toJUL(level);
        
        Logger l = Logger.getLogger(component.getPackageName());
        l.setLevel(impl);
        
        if (WEIRD_GUY_REMOVED.compareAndSet(false, true)) {
            uninstallRootConsoleHandler();
        }
        
        int n = 0;
        while (l != null) {
            for (Handler h : l.getHandlers()) {
                if (isForeignConsoleHandler(h)) {
                    h.setLevel(impl);
                    ++n;
                }
            }
            l = l.getParent();
        }
        
        if (n == 0 && GOOD_GUY_ADDED.compareAndSet(false, true)) {
            Handler h = newConsoleHandler();
            h.setLevel(impl);
            Logger.getLogger("").addHandler(h);
        }
    }
    
    /**
     * Add handler to the logger of the package that the component belongs to.
     * 
     * @param component to extract package from
     * @param handler to add
     * 
     * @throws NullPointerException
     *             if {@code component} is {@code null}
     *             (should also be the case for {@code handler})
     */
    public static void addHandler(Class<?> component, Handler handler) {
        Logger.getLogger(component.getPackageName()).addHandler(handler);
    }
    
    /**
     * Remove handler from the logger of the package that the component belongs to.
     * 
     * This method returns silently if the given handler is not found or
     * {@code null} (LOL).
     * 
     * @param component to extract package from
     * @param handler to remove (may be {@code null})
     * 
     * @throws NullPointerException if {@code component} is {@code null}
     */
    public static void removeHandler(Class<?> component, Handler handler) {
        Logger.getLogger(component.getPackageName()).removeHandler(handler);
    }
    
    /**
     * Start global log recording.<p>
     * 
     * An invocation of this method behaves in exactly the same way as the
     * invocation
     * <pre>
     *     Logging.{@link #startRecording(Class, Class[])
     *       startRecording}(HttpServer.class);
     * </pre>
     * 
     * @return key to use as argument to {@link #stopRecording(Recorder)}
     * 
     * @throws NullPointerException if any arg is {@code null}
     */
    public static Recorder startRecording() {
        return startRecording(HttpServer.class);
    }
    
    /**
     * Start recording log records from the loggers of the packages that the
     * given components belong to.<p>
     * 
     * Recording should eventually be stopped using {@link
     * #stopRecording(Recorder)}.<p>
     * 
     * Recording is especially useful for running assertions on the server's
     * generated log.<p>
     * 
     * The returned recorder supports {@linkplain
     * Recorder#await(Level, String) awaiting} a particular log record. This is
     * crucial for tests asserting records produced by other threads than the
     * test worker, which applies to most server components as the server is
     * fully asynchronous. Otherwise, there could be timing issues.<p>
     * 
     * The awaiting feature can also be used solely as a mechanism to await a
     * particular quote unquote "event" before moving on.<p>
     * 
     * WARNING: Recording is implemented through adding a handler to each
     * targeted logger. The handler's {@code publish(LogRecord)} method is
     * {@code synchronized}. This lock is not expected to be contended.
     * Nonetheless, the recording feature should not be used in time-critical
     * code.
     * 
     * @param firstComponent at least one
     * @param more may be provided
     * 
     * @return key to use as argument to {@link #stopRecording(Recorder)}
     * 
     * @throws NullPointerException if any arg is {@code null}
     */
    public static Recorder startRecording(Class<?> firstComponent, Class<?>... more) {
        RecordListener[] l = Stream.concat(of(firstComponent), of(more))
                .map(c -> {
                    RecordListener rl = new RecordListener(c);
                    addHandler(c, rl);
                    return rl;
                }).toArray(RecordListener[]::new);
        
        return new Recorder(l);
    }
    
    /**
     * Stop recording log records and extract all observed records.
     * 
     * @param key returned from {@link #startRecording(Class, Class[])}
     * 
     * @return all log records recorded from start until now (orderly; FIFO)
     */
    public static Stream<LogRecord> stopRecording(Recorder key) { // TODO: Replace with recorder.stop()
        return key.listeners()
                .peek(r -> removeHandler(r.component(), r))
                .flatMap(RecordListener::recordsStream)
                .sorted(comparing(LogRecord::getInstant));
    }
    
    private static void uninstallRootConsoleHandler() {
        final Logger root = Logger.getLogger("");
        
        Handler[] ch = Stream.of(root.getHandlers())
                .filter(Logging::isForeignConsoleHandler)
                .map(h -> (ConsoleHandler) h)
                .toArray(Handler[]::new);
        
        if (ch.length == 1) {
            root.removeHandler(ch[0]);
        } else if (ch.length > 1) {
            // We could specifically target "java.util.logging.LogManager$RootLogger@50013..."
            System.getLogger(Logging.class.getPackageName()).log(
                    System.Logger.Level.INFO,
                    () -> "Root's console handler not removed. Found many of them: " + ch.length);
        }
    }
    
    private static boolean isForeignConsoleHandler(Handler h) {
        return h instanceof ConsoleHandler &&
             !(h instanceof SystemOutInsteadOfSystemErr);
    }
    
    private static ConsoleHandler newConsoleHandler() {
        ConsoleHandler h = new SystemOutInsteadOfSystemErr();
        h.setFormatter(new LogRecords.ElegantFormatter());
        return h;
    }
    
    private static final class SystemOutInsteadOfSystemErr extends ConsoleHandler {
        private boolean initialized;
        
        @Override
        protected synchronized void setOutputStream(OutputStream out) throws SecurityException {
            if (initialized) {
                super.setOutputStream(out);
            } else {
                super.setOutputStream(System.out);
                initialized = true;
            }
        }
    }
    
    /**
     * Is a subscription key and API for waiting on- and asserting log
     * records.<p>
     * 
     * {@code await()} methods (and derivatives such as {@code assertAwait()})
     * will by default wait at most 3 seconds. This can be configured
     * differently using {@link #timeoutAfter(long, TimeUnit)}.
     * 
     * @see #startRecording(Class, Class[])
     */
    public static final class Recorder {
        private final RecordListener[] l;
        private long timeout;
        private TimeUnit unit;
        
        Recorder(RecordListener[] listeners) {
            l = listeners;
            timeout = 3;
            unit = SECONDS;
        }
        
        Stream<RecordListener> listeners() {
            return Stream.of(l);
        }
        
        /**
         * Stream a snapshot of all records observed.
         * 
         * @return a snapshot of all records observed
         */
        public Stream<LogRecord> records() {
            return listeners()
                    .flatMap(RecordListener::recordsStream)
                    .sorted(comparing(LogRecord::getInstant));
        }
        
        /**
         * Take the earliest record found that fulfils the given criteria.
         * 
         * This method is useful to extract log records and run assertions only
         * on what is left behind. For example, to ensure a specific log record
         * of an error was the only logged error, call this method and then
         * {@link #assertThatNoErrorWasLogged()}.
         * 
         * @param level record level predicate
         * @param messageStartsWith record message predicate
         * @param error record error predicate (instance of)
         * @return the record, or {@code null} if not found
         */
        public LogRecord take(
                Level level, String messageStartsWith,
                Class<? extends Throwable> error)
        {
            var jul = toJUL(level);
            LogRecord match = null;
            for (var listener : l) {
                var it = listener.recordsDeque().iterator();
                while (it.hasNext()) {
                    var r = it.next();
                    if (r.getLevel().equals(jul) &&
                        r.getMessage().startsWith(messageStartsWith) &&
                        error.isInstance(r.getThrown()))
                    {
                        it.remove();
                        match = r;
                        break;
                    }
                }
            }
            return match;
        }
        
        /**
         * Set a new timeout for {@code await()} methods.<p>
         * 
         * This method is not thread-safe.
         * 
         * @param timeout value
         * @param unit of timeout
         * @return this for chaining/fluency
         */
        public Recorder timeoutAfter(long timeout, TimeUnit unit) {
            this.timeout = timeout;
            this.unit = unit;
            return this;
        }
        
        /**
         * Return immediately if a log record passing the given test has been
         * published, or await its arrival.<p>
         * 
         * WARNING: This method may block the publication of a log record
         * temporarily and if so, the block is minuscule. Nonetheless, awaiting
         * should not be done by time-critical code.
         * 
         * @param test of record
         * 
         * @return {@code true} when target record is observed, or
         *         {@code false} on timeout (record not observed)
         * 
         * @throws NullPointerException
         *             if {@code test} is {@code null}
         * @throws InterruptedException
         *             if the current thread is interrupted while waiting
         */
        public boolean await(Predicate<LogRecord> test) throws InterruptedException {
            requireNonNull(test);
            
            CountDownLatch cl = new CountDownLatch(1);
            
            for (RecordListener rl : l) {
                rl.monitor(rec -> {
                    if (test.test(rec)) {
                        cl.countDown();
                    }
                });
                
                // optimization (no need to continue), that's all
                if (cl.getCount() == 0) {
                    return true;
                }
            }
            
            return cl.await(timeout, unit);
        }
        
        /**
         * Return immediately if a log record of the given level with the given
         * message-prefix has been published, or await its arrival.<p>
         * 
         * Uses {@link #await(Predicate)} under the hood. Same warning apply.
         * 
         * @param level record level predicate
         * @param messageStartsWith record message predicate
         * 
         * @return {@code true} when target record is observed, or
         *         {@code false} on timeout (record not observed)
         * 
         * @throws NullPointerException
         *             if any arg is {@code null}
         * @throws InterruptedException
         *             if the current thread is interrupted while waiting
         */
        public boolean await(Level level, String messageStartsWith)
                throws InterruptedException {
            requireNonNull(level);
            requireNonNull(messageStartsWith);
            return await(r -> r.getLevel().equals(toJUL(level)) &&
                              r.getMessage().startsWith(messageStartsWith));
        }
        
        /**
         * Return immediately if a log record of the given level with the given
         * message-prefix and error has been published, or await its arrival.<p>
         * 
         * Uses {@link #await(Predicate)} under the hood. Same warning apply.
         * 
         * @param level record level predicate
         * @param messageStartsWith record message predicate
         * @param error record error predicate (record's error must be instance of)
         * 
         * @return {@code true} when target record is observed, or
         *         {@code false} on timeout (record not observed)
         * 
         * @throws NullPointerException
         *             if any arg is {@code null}
         * @throws InterruptedException
         *             if the current thread is interrupted while waiting
         */
        public boolean await(
                Level level, String messageStartsWith, Class<? extends Throwable> error)
                throws InterruptedException
        {
            requireNonNull(level);
            requireNonNull(messageStartsWith);
            requireNonNull(error);
            return await(r -> r.getLevel().equals(toJUL(level)) &&
                              r.getMessage().startsWith(messageStartsWith) &&
                              error.isInstance(r.getThrown()));
        }
        
        /**
         * This method behaves the same as {@link #await(Predicate)}, except it
         * returns normally instead of {@code true}, and it returns
         * exceptionally instead of {@code false}.
         * 
         * @param test of record
         * 
         * @throws NullPointerException
         *             if {@code test} is {@code null}
         * @throws InterruptedException
         *             if the current thread is interrupted while waiting
         * @throws AssertionError
         *             on timeout (record not observed)
         */
        public void assertAwait(Predicate<LogRecord> test) throws InterruptedException {
            assertTrue(await(test));
        }
        
        /**
         * This method behaves the same as {@link #await(Level, String)}, except
         * it returns normally instead of {@code true}, and it returns
         * exceptionally instead of {@code false}.
         * 
         * @param level record level predicate
         * @param messageStartsWith record message predicate
         * 
         * @throws NullPointerException
         *             if any arg is {@code null}
         * @throws InterruptedException
         *             if the current thread is interrupted while waiting
         * @throws AssertionError
         *             on timeout (record not observed)
         */
        public void assertAwait(
                Level level, String messageStartsWith)
                throws InterruptedException {
            assertTrue(await(level, messageStartsWith));
        }
        
        /**
         * This method behaves the same as {@link #await(Level, String, Class)},
         * except it returns normally instead of {@code true}, and it returns
         * exceptionally instead of {@code false}.
         * 
         * @param level record level predicate
         * @param messageStartsWith record message predicate
         * @param error record error predicate (record's error must be instance of)
         * 
         * @throws NullPointerException
         *             if any arg is {@code null}
         * @throws InterruptedException
         *             if the current thread is interrupted while waiting
         * @throws AssertionError
         *             on timeout (record not observed)
         */
        public void assertAwait(
                Level level, String messageStartsWith, Class<? extends Throwable> error)
                throws InterruptedException {
            assertTrue(await(level, messageStartsWith, error));
        }
        
        /**
         * Assertively await on the server log to indicate a child was accepted.
         * 
         * @throws InterruptedException
         *             if the current thread is interrupted while waiting
         * @throws AssertionError
         *             on timeout (record not observed)
         */
        public void assertAwaitChildAccept() throws InterruptedException {
            assertAwait(DEBUG, "Accepted child:");
        }
        
        /**
         * Assertively await on the server log to indicate a child was closed.
         * 
         * @throws InterruptedException
         *             if the current thread is interrupted while waiting
         * @throws AssertionError
         *             on timeout (record not observed)
         */
        public void assertAwaitChildClose() throws InterruptedException {
            assertAwait(DEBUG, "Closed child:");
        }
        
        /**
         * Assertively await the first logged error.
         * 
         * @return the error
         * 
         * @throws InterruptedException
         *             if the current thread is interrupted while waiting
         * @throws AssertionError
         *             on timeout (record not observed)
         */
        public Throwable assertAwaitFirstLogError() throws InterruptedException {
            return assertAwaitFirstLogErrorOf(Throwable.class);
        }
        
        /**
         * Assertively await the first logged error that is an instance of the
         * given type.
         * 
         * @param filter type expected
         * @return the error
         * 
         * @throws NullPointerException
         *             if {@code filter} is {@code null}
         * @throws InterruptedException
         *             if the current thread is interrupted while waiting
         * @throws AssertionError
         *             on timeout (throwable not observed)
         */
        public Throwable assertAwaitFirstLogErrorOf(
                Class<? extends Throwable> filter)
                throws InterruptedException
        {
            requireNonNull(filter);
            AtomicReference<Throwable> thr = new AtomicReference<>();
            assertAwait(rec -> {
                var t = rec.getThrown();
                if (filter.isInstance(t)) {
                    thr.set(t);
                    return true;
                }
                return false;
            });
            var t = thr.get();
            assert t != null; // Just my paranoia lol
            return t;
        }
        
        /**
         * Assert that no observed log record contains a throwable.
         */
        public void assertThatNoErrorWasLogged() {
            assertThat(records()).extracting(r -> {
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
         * Assert that observed log records contains the given values only once.
         * 
         * @param values use {@link LogRecords#rec(Level, String, Throwable error)}
         */
        public void assertThatLogContainsOnlyOnce(Tuple... values) {
            assertThat(records())
                .extracting(
                    LogRecord::getLevel,
                    LogRecord::getMessage,
                    LogRecord::getThrown)
                .containsOnlyOnce(values);
        }
    }
    
    private static class RecordListener extends Handler {
        private final Class<?> cmp;
        private final Deque<LogRecord> deq;
        private final List<Consumer<LogRecord>> mon;
        
        RecordListener(Class<?> component) {
            cmp = component;
            deq = new ConcurrentLinkedDeque<>();
            mon = new ArrayList<>();
            setLevel(java.util.logging.Level.ALL);
        }
        
        /**
         * Synchronously replay all until-now observed records to the given
         * consumer and then subscribe the consumer to asynchronous future
         * records as they arrive.
         * 
         * @param consumer code to execute with the records
         */
        synchronized void monitor(Consumer<LogRecord> consumer) {
            requireNonNull(consumer);
            recordsStream().forEach(consumer);
            mon.add(consumer);
        }
        
        Class<?> component() {
            return cmp;
        }
        
        Deque<LogRecord> recordsDeque() {
            return deq;
        }
        
        Stream<LogRecord> recordsStream() {
            return deq.stream();
        }
        
        @Override
        public synchronized void publish(LogRecord record) {
            deq.add(record);
            mon.forEach(c -> c.accept(record));
        }
        
        @Override
        public void flush() {
            // Empty
        }
        
        @Override
        public void close() {
            // Empty
        }
    }
}