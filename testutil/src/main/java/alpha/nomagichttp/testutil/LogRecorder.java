package alpha.nomagichttp.testutil;

import alpha.nomagichttp.HttpServer;
import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.ObjectAssert;
import org.assertj.core.groups.Tuple;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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
import static java.util.stream.Stream.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Is a util for asserting and awaiting log records.<p>
 * 
 * Methods that await log records will by default timeout at most 3 seconds.
 * This can be configured differently using
 * {@link #timeoutAfter(long, TimeUnit)}.<p>
 * 
 * WARNING: Recording is implemented through adding a handler to each targeted
 * logger. The handler's {@code publish(LogRecord)} method is
 * {@code synchronized}. This lock is not expected to be contended. Nonetheless,
 * the recording feature should not be used in time-critical code.
 * 
 * @see #startRecording(Class, Class[])
 */
public final class LogRecorder
{
    /**
     * Starts global log recording.<p>
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
     * Starts recording log records from the loggers of the packages that the
     * given components belong to.<p>
     * 
     * Recording should eventually be stopped using {@link #stopRecording()}.<p>
     * 
     * Recording is especially useful for running assertions on the server's
     * generated log.<p>
     * 
     * The returned recorder supports waiting on the arrival of a particular log
     * record. This is crucial for tests asserting records produced by other
     * threads than the test worker, which applies to most server components as
     * the server runs in a different thread. Otherwise, there could be timing
     * issues.<p>
     * 
     * The awaiting feature can also be used solely as a mechanism to await a
     * particular event before moving on.
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
    
    /**
     * Sets a new timeout for {@code await()} methods.<p>
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
     * Takes the earliest matched record.<p>
     * 
     * For a record to be a match, all tests of the record's mapped values must
     * pass:
     * 
     * <ul>
     *   <li>The level is <i>equal to</i> the given level
     *   <li>The message starts with the given string
     * </ul>
     * 
     * @param level record level predicate
     * @param messageStartsWith record message predicate
     * 
     * @return this for chaining/fluency
     * 
     * @throws NullPointerException
     *             if any argument is {@code null}
     * @throws AssertionError
     *             if a match could not be found
     */
    public LogRecorder assertTake(
            System.Logger.Level level, String messageStartsWith) {
        var jul = toJUL(level);
        requireNonNull(messageStartsWith);
        assertNotNull(take(r -> r.getLevel().equals(jul) &&
                  r.getMessage().startsWith(messageStartsWith)));
        return this;
    }
    
    /**
     * Takes the earliest matched record.<p>
     * 
     * This method is useful to extract log records and limit subsequent
     * assertions to what is left behind.<p>
     * 
     * For example, to ensure that a specific log record of an error was the
     * only logged error, call this method and then
     * {@link #assertNoThrowable()}.<p>
     * 
     * For a record to be a match, all tests of the record's mapped values must
     * pass:
     * 
     * <ul>
     *   <li>The level is <i>equal to</i> the given level
     *   <li>The message starts with the given string
     *   <li>The throwable is an <i>instance of</i> {@code error}
     * </ul>
     * 
     * @param level record level predicate
     * @param messageStartsWith record message predicate
     * @param error record error predicate
     * 
     * @return an assert object of the throwable
     * 
     * @throws NullPointerException
     *             if any argument is {@code null}
     * @throws AssertionError
     *             if a match could not be found
     */
    public AbstractThrowableAssert<?, ? extends Throwable> assertTake(
            System.Logger.Level level, String messageStartsWith,
            Class<? extends Throwable> error)
    {
        var jul = toJUL(level);
        requireNonNull(messageStartsWith);
        requireNonNull(error);
        var rec = take(r -> r.getLevel().equals(jul) &&
                            r.getMessage().startsWith(messageStartsWith) &&
                            error.isInstance(r.getThrown()));
        return assertThat(rec.getThrown());
    }
    
    /**
     * Takes the earliest record which has a throwable.
     * 
     * @return an assert object of the throwable
     * 
     * @throws AssertionError
     *             if a match could not be found
     * 
     * @see #assertTake(System.Logger.Level, String, Class)
     */
    public AbstractThrowableAssert<?, ? extends Throwable> assertTakeError() {
        return assertThat(take(r -> r.getThrown() != null).getThrown());
    }
    
    /**
     * Returns immediately if a log record passing the given test has been
     * published, or awaits its arrival.
     * 
     * @param test of record
     * 
     * @return this for chaining/fluency
     * 
     * @throws NullPointerException
     *             if {@code test} is {@code null}
     * @throws InterruptedException
     *             if the current thread is interrupted while waiting
     * @throws AssertionError
     *             on timeout (record not observed)
     */
    public LogRecorder assertAwait(Predicate<LogRecord> test)
                throws InterruptedException {
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
                return this;
            }
        }
        assertTrue(latch.await(timeout, unit));
        return this;
    }
    
    /**
     * Returns immediately if a log record of the given level with the given
     * message-prefix has been published, or await its arrival.
     * 
     * @param level record level predicate
     * @param messageStartsWith record message predicate
     * 
     * @return this for chaining/fluency
     * 
     * @throws NullPointerException
     *             if any arg is {@code null}
     * @throws InterruptedException
     *             if the current thread is interrupted while waiting
     * @throws AssertionError
     *             on timeout (record not observed)
     */
    public LogRecorder assertAwait(System.Logger.Level level, String messageStartsWith)
                throws InterruptedException {
        requireNonNull(level);
        requireNonNull(messageStartsWith);
        return assertAwait(r -> r.getLevel().equals(toJUL(level)) &&
                                r.getMessage().startsWith(messageStartsWith));
    }
    
    /**
     * Returns immediately if a log record of the given level with the given
     * message-prefix and error has been published, or await its arrival.
     * 
     * @param level record level predicate
     * @param messageStartsWith record message predicate
     * @param error record error predicate (must be an <i>instance of</i>)
     * 
     * @return an assert object of the throwable
     * 
     * @throws NullPointerException
     *             if any arg is {@code null}
     * @throws InterruptedException
     *             if the current thread is interrupted while waiting
     * @throws AssertionError
     *             on timeout (record not observed)
     */
    public AbstractThrowableAssert<?, ? extends Throwable>
           assertAwaitTake(
               System.Logger.Level level, String messageStartsWith,
               Class<? extends Throwable> error)
           throws InterruptedException
    {
        requireNonNull(level);
        requireNonNull(messageStartsWith);
        requireNonNull(error);
        return assertAwait(r ->
                   r.getLevel().equals(toJUL(level)) &&
                   r.getMessage().startsWith(messageStartsWith) &&
                   error.isInstance(r.getThrown()))
              .assertTake(level, messageStartsWith, error);
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
     * Assertively await and take the first logged error of any type.
     * 
     * @return an assert object of the throwable
     * 
     * @throws InterruptedException
     *             if the current thread is interrupted while waiting
     * @throws AssertionError
     *             on timeout (record not observed)
     */
    public AbstractThrowableAssert<?, ? extends Throwable>
           assertAwaitTakeError()
           throws InterruptedException
    {
        return assertAwaitTakeError(Throwable.class);
    }
    
    /**
     * Assertively await and take the first logged error that is an instance of
     * the given type.
     * 
     * @param filter type expected
     * 
     * @return an assert object of the throwable
     * 
     * @throws NullPointerException
     *             if {@code filter} is {@code null}
     * @throws InterruptedException
     *             if the current thread is interrupted while waiting
     * @throws AssertionError
     *             on timeout (throwable not observed)
     */
    public AbstractThrowableAssert<?, ? extends Throwable>
           assertAwaitTakeError(Class<? extends Throwable> filter)
           throws InterruptedException
    {
        requireNonNull(filter);
        var match = new AtomicReference<LogRecord>();
        assertAwait(rec -> {
            var t = rec.getThrown();
            if (filter.isInstance(t)) {
                match.set(rec);
                return true;
            }
            return false;
        });
        var rec = match.get();
        assertNotNull(take(r -> r == rec));
        return assertThat(rec.getThrown());
    }
    
    /**
     * Assert that no observed log record contains a throwable.
     * 
     * @return this for chaining/fluency
     */
    public LogRecorder assertNoThrowable() {
        assertThat(records()).extracting(r -> {
            var t = r.getThrown();
            if (t != null) {
                LOG.log(WARNING, () ->
                        "Log record that has a throwable also has this message: " +
                        r.getMessage());
            }
            return t;
        }).containsOnlyNulls();
        return this;
    }
    
    /**
     * Asserts that no log record exists with a level greater than {@code INFO},
     * nor anyone that has a throwable.
     * 
     * @return this for chaining/fluency
     */
    public LogRecorder assertNoThrowableNorWarning() {
        assertThat(records())
            .noneMatch(v -> v.getLevel().intValue() > java.util.logging.Level.INFO.intValue())
            .noneMatch(v -> v.getThrown() != null);
        return this;
    }
    
    /**
     * Assert that observed log records contain the given values only once.
     * 
     * @param values use {@link LogRecords#rec(System.Logger.Level, String, Throwable error)}
     * 
     * @return this for chaining/fluency
     */
    public LogRecorder assertContainsOnlyOnce(Tuple... values) {
        assertThat(records())
            .extracting(
                LogRecord::getLevel,
                LogRecord::getMessage,
                LogRecord::getThrown)
            .containsOnlyOnce(values);
        return this;
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
    
    private Stream<RecordHandler> handlers() {
        return Stream.of(handlers);
    }
    
    private Stream<LogRecord> records() {
        return handlers()
                .flatMap(RecordHandler::recordsStream)
                .sorted(comparing(LogRecord::getInstant));
    }
    
    private LogRecord take(Predicate<LogRecord> test) {
        LogRecord match = null;
        search: for (var h : handlers) {
            var it = h.recordsDeque().iterator();
            while (it.hasNext()) {
                var r = it.next();
                if (test.test(r)) {
                    it.remove();
                    match = r;
                    break search;
                }
            }
        }
        assertNotNull(match);
        return match;
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
