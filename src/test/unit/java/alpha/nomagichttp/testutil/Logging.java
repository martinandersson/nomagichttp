package alpha.nomagichttp.testutil;

import alpha.nomagichttp.HttpServer;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.lang.reflect.Modifier.isFinal;
import static java.lang.reflect.Modifier.isStatic;
import static java.time.temporal.ChronoUnit.MILLIS;
import static java.util.Arrays.stream;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.logging.Level.ALL;
import static java.util.stream.Stream.of;

/**
 * Logging utilities.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class Logging
{
    private static final AtomicBoolean
            WEIRD_GUY_REMOVED = new AtomicBoolean(),
            GOOD_GUY_ADDED    = new AtomicBoolean();
    
    private Logging() {
        // Empty
    }
    
    /**
     * Set level globally.<p>
     * 
     * An invocation of this method behaves in exactly the same way as the
     * invocation
     * <pre>
     *     Logging.{@link #setLevel(Class, System.Logger.Level)
     *       setLevel}(HttpServer.class, level);
     * </pre>
     * 
     * @param level to set
     */
    public static void setLevel(System.Logger.Level level) {
        setLevel(HttpServer.class, level);
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
    public static void setLevel(Class<?> component, System.Logger.Level level) {
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
     * The result is retrieved from {@link #stopRecording(Recorder)}.<p>
     * 
     * Recording is useful when running assertions on the server's generated
     * log.<p>
     * 
     * The returned recorder supports {@linkplain
     * Recorder#await(Level, String) awaiting} a particular log record. This is
     * crucial for tests asserting records produced by other threads than the
     * test worker, which applies to most server components as the server is
     * fully asynchronous. Otherwise there would be timing issues.<p>
     * 
     * The awaiting feature can also be used solely as a mechanism to await a
     * particular server-event (as revealed through the log) before moving
     * on.<p>
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
    public static Stream<LogRecord> stopRecording(Recorder key) {
        return key.listeners()
                .peek(r -> removeHandler(r.component(), r))
                .flatMap(RecordListener::records)
                .sorted(comparing(LogRecord::getInstant));
    }
    
    /**
     * Elegantly formats the given record.<p>
     * 
     * {@code LogRecord} does not implement {@code toString} and would return
     * something like "java.util.logging.LogRecord@4e196770".<p>
     * 
     * Internally, a new instance of a formatter class is used to produce the
     * string and so this method should only be used when programmatically
     * peeking log records from a {@link Recorder}.<p>
     * 
     * The same formatting is automagically applied to the Gradle report if test
     * calls {@link #setLevel(Class, System.Logger.Level)}, except the formatter
     * instance will be re-used of course and not created anew for each record.
     * 
     * @param rec log record to format
     * @return a well formatted string
     */
    public static String toString(LogRecord rec) {
        String s = new ElegantFormatter().format(rec);
        // Remove trailing newline
        return s.substring(0, s.length() - 1);
    }
    
    /**
     * Convert {@code System.Logger.Level} to {@code java.util.logging.Level}.
     * 
     * @param level to convert
     * @return the converted value
     * @throws NullPointerException if {@code level} is {@code null}
     */
    public static java.util.logging.Level toJUL(System.Logger.Level level) {
        requireNonNull(level);
        return stream(java.util.logging.Level.class.getFields())
                .filter(f ->
                    f.getType() == java.util.logging.Level.class &&
                    isStatic(f.getModifiers()) &&
                    isFinal(f.getModifiers()))
                .map(f -> {
                    try {
                        return (java.util.logging.Level) f.get(null);
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                })
                .filter(l -> l.intValue() == level.getSeverity())
                .findAny().orElseThrow(() -> new IllegalArgumentException(
                        "No JUL match for this level: " + level));
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
        h.setFormatter(new ElegantFormatter());
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
    
    private static final class ElegantFormatter extends Formatter {
        @Override public String format(LogRecord r) {
            String joined = String.join(" | ",
                    getTimestamp(r),
                    getThreadName(r),
                    getLevel(r),
                    getSource(r),
                    formatMessage(r));
            
            return r.getThrown() == null ? joined + '\n' :
                    joined + getStacktrace(r.getThrown());
        }
        
        private static String getTimestamp(LogRecord r) {
            return r.getInstant().truncatedTo(MILLIS)
                    .toString().replace("T", " | ");
        }
        
        private static String getThreadName(LogRecord r) {
            ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(r.getThreadID());
            String thread = info == null ? "dead-" + r.getThreadID() : info.getThreadName();
            return rightPad(thread, "pool-1-thread-23".length());
        }
        
        private static String getLevel(LogRecord r) {
            String level = r.getLevel().getLocalizedName();
            return rightPad(level, "WARNING".length()); 
        }
        
        private static String getSource(LogRecord r) {
            String s;
            
            if (r.getSourceClassName() != null) {
                s = r.getSourceClassName();
                if (r.getSourceMethodName() != null) {
                    s += " " + r.getSourceMethodName();
                }
            } else {
                s = r.getLoggerName();
            }
            
            return s;
        }
        
        private static String getStacktrace(Throwable t) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println();
            t.printStackTrace(pw);
            pw.close();
            return sw.toString();
        }
        
        private static String rightPad(String s, int minLength) {
            if (s.length() >= minLength) {
                return s;
            }
            
            return s + " ".repeat(minLength - s.length());
        }
    }
    
    /**
     * Is essentially a subscription key and API for waiting on a record.
     * 
     * @see #startRecording(Class, Class[])
     */
    public static class Recorder {
        private final RecordListener[] l;
        
        Recorder(RecordListener[] listeners) {
            l = listeners;
        }
        
        /**
         * Return immediately if a log record passing the given test has been
         * published, or await it's arrival for a maximum of 3 seconds.<p>
         * 
         * Currently, due to implementation simplicity, only one await per
         * recorder instance is allowed.<p>
         * 
         * WARNING: This method may block the publication of a log record
         * temporarily and if so, the block is minuscule. Nonetheless, awaiting
         * should not be done by time-critical code.
         * 
         * @param test of record
         *
         * @return {@code true} when target record is observed, or
         *         {@code false} if 3 seconds passes without observing the record
         * 
         * @throws NullPointerException
         *             if {@code test} is {@code null}
         * 
         * @throws IllegalStateException
         *             if this method was used before
         *
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
            
            return cl.await(3, SECONDS);
        }
        
        /**
         * Return immediately if a log record of the given level with the given
         * message-prefix has been published, or await it's arrival for a
         * maximum of 3 seconds.<p>
         * 
         * Uses {@link #await(Predicate)} under the hood. Same warning apply.
         * 
         * @param level record level predicate
         * @param messageStartsWith record message predicate
         * 
         * @return {@code true} when target record is observed, or
         *         {@code false} if 3 seconds passes without observing the record
         * 
         * @throws NullPointerException
         *             if any arg is {@code null}
         * 
         * @throws IllegalStateException
         *             if an {@code await} method was used before
         * 
         * @throws InterruptedException
         *             if the current thread is interrupted while waiting
         */
        public boolean await(Level level, String messageStartsWith) throws InterruptedException {
            requireNonNull(level);
            requireNonNull(messageStartsWith);
            return await(r -> r.getLevel().equals(level) &&
                              r.getMessage().startsWith(messageStartsWith));
        }
        
        /**
         * Return immediately if a log record of the given level with the given
         * message-prefix and error has been published, or await it's arrival
         * for a maximum of 3 seconds.<p>
         * 
         * Uses {@link #await(Predicate)} under the hood. Same warning apply.
         * 
         * @param level record level predicate
         * @param messageStartsWith record message predicate
         * @param error record error predicate (record's level must be instance of)
         * 
         * @return {@code true} when target record is observed, or
         *         {@code false} if 3 seconds passes without observing the record
         * 
         * @throws NullPointerException
         *             if any arg is {@code null}
         * 
         * @throws IllegalStateException
         *             if an {@code await} method was used before
         * 
         * @throws InterruptedException
         *             if the current thread is interrupted while waiting
         */
        public boolean await(Level level, String messageStartsWith, Class<? extends Throwable> error)
                throws InterruptedException
        {
            requireNonNull(level);
            requireNonNull(messageStartsWith);
            requireNonNull(error);
            return await(r -> r.getLevel().equals(level) &&
                              r.getMessage().startsWith(messageStartsWith) &&
                              error.isInstance(r.getThrown()));
        }
        
        Stream<RecordListener> listeners() {
            return Stream.of(l);
        }
    }
    
    private static class RecordListener extends Handler {
        private final Class<?> cmp;
        private final Deque<LogRecord> deq;
        private Consumer<LogRecord> mon;
        
        RecordListener(Class<?> component) {
            cmp = component;
            deq = new ConcurrentLinkedDeque<>();
            mon = null;
            setLevel(ALL);
        }
        
        /**
         * Synchronously replay all until-now observed records to the given
         * consumer and then subscribe the consumer to asynchronous future
         * records as they arrive.
         * 
         * @param consumer code to execute with the records
         */
        synchronized void monitor(Consumer<LogRecord> consumer) {
            if (mon != null) {
                throw new IllegalStateException();
            }
            records().forEach(consumer);
            mon = consumer;
        }
        
        Class<?> component() {
            return cmp;
        }
        
        Stream<LogRecord> records() {
            return deq.stream();
        }
        
        @Override
        public synchronized void publish(LogRecord record) {
            deq.add(record);
            if (mon != null) {
                mon.accept(record);
            }
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