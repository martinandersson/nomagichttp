package alpha.nomagichttp.testutil;

import alpha.nomagichttp.HttpServer;
import org.assertj.core.groups.Tuple;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.stream.Stream;

import static alpha.nomagichttp.testutil.LogRecords.toJUL;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Stream.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Is a util for waiting on and asserting log records.<p>
 * 
 * {@code await()} methods (and derivatives such as {@code assertAwait()}) will
 * by default wait at most 3 seconds. This can be configured differently using
 * {@link #timeoutAfter(long, TimeUnit)}.
 * 
 * @see #startRecording(Class, Class[])
 */
public final class LogRecorder
{
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
     * @return a new log recorder
     * 
     * @throws NullPointerException if any arg is {@code null}
     */
    public static LogRecorder startRecording() {
        return startRecording(HttpServer.class);
    }
    
    /**
     * Start recording log records from the loggers of the packages that the
     * given components belong to.<p>
     * 
     * Recording should eventually be stopped using {@link #stopRecording()}.<p>
     * 
     * Recording is especially useful for running assertions on the server's
     * generated log.<p>
     * 
     * The returned recorder supports {@linkplain
     * LogRecorder#await(System.Logger.Level, String) awaiting} a particular log record. This is
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
     * @return a new log recorder
     * 
     * @throws NullPointerException if any arg is {@code null}
     */
    public static LogRecorder startRecording(Class<?> firstComponent, Class<?>... more) {
        RecordHandler[] h = Stream.concat(of(firstComponent), of(more))
                .map(c -> {
                    var rh = new RecordHandler(c);
                    Logging.addHandler(c, rh);
                    return rh;
                }).toArray(RecordHandler[]::new);
        
        return new LogRecorder(h);
    }
    
    private static final System.Logger LOG
            = System.getLogger(LogRecorder.class.getPackageName());
    
    private final RecordHandler[] handlers;
    private long timeout;
    private TimeUnit unit;
    
    private LogRecorder(RecordHandler[] handlers) {
        this.handlers = handlers;
             timeout  = 3;
             unit     = SECONDS;
    }
    
    private Stream<RecordHandler> handlers() {
        return Stream.of(handlers);
    }
    
    /**
     * Returns a Stream of all records.
     * 
     * @return see JavaDoc
     */
    public Stream<LogRecord> records() {
        return handlers()
                .flatMap(RecordHandler::recordsStream)
                .sorted(comparing(LogRecord::getInstant));
    }
    
    /**
     * Returns a Deque of all recorded errors.
     * 
     * @return see JavaDoc
     */
    public Deque<Throwable> recordedErrors() {
        return records()
                .map(LogRecord::getThrown)
                .filter(Objects::nonNull)
                .collect(toCollection(ArrayDeque::new));
    }
    
    /**
     * Take the earliest matched record.<p>
     * 
     * This method is useful to extract log records and limit subsequent
     * assertions to what is left behind.<p>
     * 
     * For example, to ensure that a specific log record of an error was the
     * only logged error, call this method and then
     * {@link #assertThatNoErrorWasLogged()}.<p>
     * 
     * For a record to be a match, all tests of the record's mapped values must
     * pass:
     * 
     * <ul>
     *   <li>The level is <i>equal to</i> the given level
     *   <li>The message starts with the given string
     *   <li>The error is null if the given class argument is null,
     *       otherwise an <i>instance of</i>
     * </ul>
     * 
     * @param level record level predicate
     * @param messageStartsWith record message predicate
     * @param error record error predicate
     * 
     * @return the record, or {@code null} if not found
     * 
     * @throws NullPointerException
     *             if {@code level} or {@code messageStartsWith} is {@code null}
     */
    public LogRecord take(
            System.Logger.Level level, String messageStartsWith,
            Class<? extends Throwable> error)
    {
        var jul = toJUL(level);
        LogRecord match = null;
        for (var h : handlers) {
            var it = h.recordsDeque().iterator();
            while (it.hasNext()) {
                var r = it.next();
                var t = r.getThrown();
                if (r.getLevel().equals(jul) &&
                    r.getMessage().startsWith(messageStartsWith) &&
                    (error == null ? t == null : error.isInstance(t)))
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
    public LogRecorder timeoutAfter(long timeout, TimeUnit unit) {
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
        var latch = new CountDownLatch(1);
        for (RecordHandler h : handlers) {
            h.monitor(rec -> {
                // Run callback only once
                if (latch.getCount() == 0) {
                    return;
                }
                if (test.test(rec)) {
                    latch.countDown();
                }
            });
            // optimization (no need to continue), that's all
            if (latch.getCount() == 0) {
                return true;
            }
        }
        return latch.await(timeout, unit);
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
    public boolean await(System.Logger.Level level, String messageStartsWith)
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
            System.Logger.Level level, String messageStartsWith, Class<? extends Throwable> error)
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
     * This method behaves the same as {@link #await(System.Logger.Level, String)}, except
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
            System.Logger.Level level, String messageStartsWith)
            throws InterruptedException {
        assertTrue(await(level, messageStartsWith));
    }
    
    /**
     * This method behaves the same as {@link #await(System.Logger.Level, String, Class)},
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
            System.Logger.Level level, String messageStartsWith, Class<? extends Throwable> error)
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
     * Assertively await on the server log to indicate a child is being
     * closed.
     * 
     * @throws InterruptedException
     *             if the current thread is interrupted while waiting
     * @throws AssertionError
     *             on timeout (record not observed)
     */
    public void assertAwaitClosingChild() throws InterruptedException {
        assertAwait(DEBUG, "Closing child:");
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
     * Assert that observed log records contain the given values only once.
     * 
     * @param values use {@link LogRecords#rec(System.Logger.Level, String, Throwable error)}
     */
    public void assertThatLogContainsOnlyOnce(Tuple... values) {
        assertThat(records())
            .extracting(
                LogRecord::getLevel,
                LogRecord::getMessage,
                LogRecord::getThrown)
            .containsOnlyOnce(values);
    }
    
    /**
     * Stop recording log records and extract all observed records.
     * 
     * @return all log records recorded from start until now (orderly; FIFO)
     */
    public Stream<LogRecord> stopRecording() {
        return handlers().peek(r -> Logging.removeHandler(r.component(), r))
                          .flatMap(RecordHandler::recordsStream)
                          .sorted(comparing(LogRecord::getInstant));
    }
    
    private static final class RecordHandler extends Handler {
        private final Class<?> cmp;
        private final Deque<LogRecord> deq;
        private final List<Consumer<LogRecord>> mon;
        
        RecordHandler(Class<?> component) {
            cmp = component;
            deq = new ConcurrentLinkedDeque<>();
            mon = new ArrayList<>();
            super.setLevel(java.util.logging.Level.ALL);
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
