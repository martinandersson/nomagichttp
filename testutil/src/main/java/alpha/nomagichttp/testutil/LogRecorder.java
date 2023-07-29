package alpha.nomagichttp.testutil;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.testutil.functional.AbstractRealTest;
import org.assertj.core.api.AbstractThrowableAssert;

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

import static alpha.nomagichttp.testutil.LogRecords.rec;
import static alpha.nomagichttp.testutil.LogRecords.toJUL;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Stream.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A utility for asserting and optionally awaiting log records.<p>
 * 
 * Many methods in this class accept predicate arguments for matching a record.
 * Each observed record's values are compared with the arguments accordingly:
 * 
 * <ul>
 *   <li>{@code getLevel()} must be equal to {@code level} 
 *   <li>{@code getMessage()} must be equal to {@code message}
 *   <li>{@code getMessage().}{@link String#startsWith(String) startsWith}{@code ()}
 *       must return {@code true} given {@code messageStartsWith}
 *   <li>{@code getThrown()} must be an instance of {@code thr}</li>
 * </ul>
 * 
 * Methods with an "assert" prefix throws an {@code AssertionError} if the
 * record can not be found.<p>
 * 
 * Methods with an "await" word in the name will block waiting on a log record
 * if it hasn't already been published. By default, the timeout happens after 3
 * seconds. This can be configured differently using
 * {@link #timeoutAfter(long, TimeUnit)}.<p>
 * 
 * Sometimes, waiting on a log record is crucial for tests asserting records
 * produced by other threads than the test worker, which applies to most server
 * components as the server runs in a different thread. Otherwise, there could
 * be timing issues.<p>
 * 
 * Methods with "remove" in their name will remove and return the earliest
 * record which is a match, meaning that the matched record will not be matched
 * again. The purpose is to limit subsequent assertions to what is left
 * behind.<p>
 * 
 * For example:
 * {@snippet :
 *              // The test provoked an expected warning...
 *              // @link substring="assertRemove" target="#assertRemove(System.Logger.Level, String)" :
 *     recorder.assertRemove(WARNING, "Exceptional event")
 *              // ...but no other warnings or records with a throwable are expected
 *              // @link substring="assertNoProblem" target="#assertNoProblem()" :
 *             .assertNoProblem();
 * }
 * 
 * Removing records with a warning or a throwable is required by
 * {@link AbstractRealTest}, which after each test asserts a problem-free
 * log.<p>
 * 
 * Create a log recorder using {@link #startRecording()}.<p>
 * 
 * <strong>WARNING</strong>: Recording is implemented by adding a handler to each
 * targeted logger. The handler's {@code publish(LogRecord)} method is
 * {@code synchronized}. This lock is not expected to be contended. Nonetheless,
 * the recording feature should not be used in time-critical code.
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
     * @throws NullPointerException
     *             if any arg is {@code null}
     */
    public static LogRecorder startRecording() {
        return startRecording(HttpServer.class);
    }
    
    /**
     * Starts recording log records from the loggers of the packages that the
     * given components belong to.<p>
     * 
     * Recording should eventually be stopped using {@link #stopRecording()}.
     * 
     * @param firstComponent at least one
     * @param more may be provided
     * 
     * @return a new log recorder
     * 
     * @throws NullPointerException
     *             if any argument is {@code null}
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
     * Sets a new timeout for methods awaiting a log record.<p>
     * 
     * This method is not thread-safe.
     * 
     * @param timeout value
     * @param unit of timeout
     * 
     * @return this for chaining/fluency
     */
    public LogRecorder timeoutAfter(long timeout, TimeUnit unit) {
        this.timeout = timeout;
        this.unit = unit;
        return this;
    }
    
    /**
     * Removes the matched record.
     * 
     * @param level record's level predicate
     * @param messageStartsWith record's message predicate
     * 
     * @return this for chaining/fluency
     * 
     * @throws NullPointerException
     *             if any argument is {@code null}
     * @throws AssertionError
     *             if a match could not be found
     * 
     * @see LogRecorder
     */
    public LogRecorder assertRemove(
            System.Logger.Level level, String messageStartsWith) {
        var jul = toJUL(level);
        requireNonNull(messageStartsWith);
        assertNotNull(assertRemoveIf(r ->
                r.getLevel().equals(jul) &&
                r.getMessage().startsWith(messageStartsWith)));
        return this;
    }
    
    /**
     * Removes the matched record.
     * 
     * @param level record's level predicate
     * @param messageStartsWith record's message predicate
     * @param thr record's thrown predicate
     * 
     * @return an assert object of the throwable
     * 
     * @throws NullPointerException
     *             if any argument is {@code null}
     * @throws AssertionError
     *             if a match could not be found
     * 
     * @see LogRecorder
     */
    public AbstractThrowableAssert<?, ? extends Throwable>
           assertRemove(System.Logger.Level level, String messageStartsWith,
           Class<? extends Throwable> thr)
    {
        var jul = toJUL(level);
        requireNonNull(messageStartsWith);
        requireNonNull(thr);
        var rec = assertRemoveIf(r ->
                r.getLevel().equals(jul) &&
                r.getMessage().startsWith(messageStartsWith) &&
                thr.isInstance(r.getThrown()));
        return assertThat(rec.getThrown());
    }
    
    /**
     * Removes the earliest record which has a throwable.
     * 
     * @return an assert object of the throwable
     * 
     * @throws AssertionError
     *             if a match could not be found
     * 
     * @see LogRecorder
     */
    public AbstractThrowableAssert<?, ? extends Throwable> assertRemoveThrown() {
        return assertThat(assertRemoveIf(r -> r.getThrown() != null).getThrown());
    }
    
    /**
     * Returns a matched record immediately, or awaits its arrival.
     * 
     * @param level record's level predicate
     * @param messageStartsWith record's message predicate
     * 
     * @return this for chaining/fluency
     * 
     * @throws NullPointerException
     *             if any argument is {@code null}
     * @throws InterruptedException
     *             if the current thread is interrupted while waiting
     * @throws AssertionError
     *             on timeout (record not observed)
     * 
     * @see LogRecorder
     */
    public LogRecorder
           assertAwait(System.Logger.Level level, String messageStartsWith)
           throws InterruptedException {
        requireNonNull(level);
        requireNonNull(messageStartsWith);
        return assertAwait(r -> r.getLevel().equals(toJUL(level)) &&
                                r.getMessage().startsWith(messageStartsWith));
    }
    
    /**
     * Removes and returns a matched record immediately, or awaits its arrival.
     * 
     * @param level record's level predicate
     * @param messageStartsWith record's message predicate
     * @param thr record's thrown predicate
     * 
     * @return an assert object of the throwable
     * 
     * @throws NullPointerException
     *             if any argument is {@code null}
     * @throws InterruptedException
     *             if the current thread is interrupted while waiting
     * @throws AssertionError
     *             on timeout (record not observed)
     * 
     * @see LogRecorder
     */
    public AbstractThrowableAssert<?, ? extends Throwable>
           assertAwaitRemove(
               System.Logger.Level level, String messageStartsWith,
               Class<? extends Throwable> thr)
           throws InterruptedException
    {
        requireNonNull(level);
        requireNonNull(messageStartsWith);
        requireNonNull(thr);
        return assertAwait(r ->
                   r.getLevel().equals(toJUL(level)) &&
                   r.getMessage().startsWith(messageStartsWith) &&
                   thr.isInstance(r.getThrown()))
              .assertRemove(level, messageStartsWith, thr);
    }
    
    /**
     * Removes and returns, immediately, a record with a throwable, or await its
     * arrival.
     * 
     * @return an assert object of the throwable
     * 
     * @throws InterruptedException
     *             if the current thread is interrupted while waiting
     * @throws AssertionError
     *             on timeout (record not observed)
     * 
     * @see LogRecorder
     */
    public AbstractThrowableAssert<?, ? extends Throwable>
           assertAwaitRemoveThrown()
           throws InterruptedException
    {
        var match = new AtomicReference<LogRecord>();
        assertAwait(rec -> {
            if (rec.getThrown() != null) {
                match.set(rec);
                return true;
            }
            return false;
        });
        var rec = match.get();
        assert rec != null;
        assertRemoveIf(r -> r == rec);
        return assertThat(rec.getThrown());
    }
    
    /**
     * Asserts that no record has a throwable nor a level greater than
     * {@code INFO}.
     * 
     * @return this for chaining/fluency
     * 
     * @throws AssertionError
     *             if a record has a throwable
     *             or a level greater than {@code INFO}
     */
    public LogRecorder assertNoProblem() {
        assertThat(records())
            .noneMatch(v -> v.getLevel().intValue() > java.util.logging.Level.INFO.intValue())
            .noneMatch(v -> v.getThrown() != null);
        return this;
    }
    
    /**
     * Asserts that only one record has the given values.<p>
     * 
     * The record's throwable, if present, has no effect.
     * 
     * @param level record's level predicate
     * @param message record's message predicate
     * 
     * @return this for chaining/fluency
     * 
     * @throws NullPointerException
     *             if any argument is {@code null}
     * @throws AssertionError
     *             if not exactly one record is found
     */
    public LogRecorder assertContainsOnlyOnce(System.Logger.Level level, String message) {
        assertThat(records())
            .extracting(
                LogRecord::getLevel,
                LogRecord::getMessage)
            .containsOnlyOnce(rec(level, message));
        return this;
    }
    
    /**
     * Stop recording log records.
     */
    public void stopRecording() {
        handlers().forEach(r -> Logging.removeHandler(r.component(), r));
    }
    
    private Stream<RecordHandler> handlers() {
        return Stream.of(handlers);
    }
    
    private Stream<LogRecord> records() {
        return handlers()
                .flatMap(RecordHandler::recordsStream)
                .sorted(comparing(LogRecord::getInstant));
    }
    
    /**
     * Removes and returns the earliest record matching the given predicate.
     * 
     * @param test record predicate
     * 
     * @return the record (never {@code null}
     * 
     * @throws AssertionError
     *             if no record matched the predicate
     */
    private LogRecord assertRemoveIf(Predicate<LogRecord> test) {
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
    private LogRecorder assertAwait(Predicate<LogRecord> test)
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
