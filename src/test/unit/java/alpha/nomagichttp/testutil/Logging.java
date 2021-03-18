package alpha.nomagichttp.testutil;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiConsumer;
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
    private Logging() {
        // Empty
    }
    
    /**
     * Set logging level for the package of a given component.
     * 
     * @param component to extract package from
     * @param level to set
     * @throws NullPointerException if any argument is {@code null}
     */
    public static void setLevel(Class<?> component, System.Logger.Level level) {
        final java.util.logging.Level impl = toJUL(level);
        
        Logger l = Logger.getLogger(component.getPackageName());
        
        l.setLevel(impl);
        l.setUseParentHandlers(false);
        
        Optional<ConsoleHandler> ch = stream(l.getHandlers())
                .filter(h -> h instanceof ConsoleHandler)
                .map(h -> (ConsoleHandler) h)
                .findAny();
        
        if (ch.isPresent()) {
            ch.get().setLevel(impl);
        }
        else {
            l.addHandler(newConsoleHandler(impl));
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
     * The awaiting feature can also be used without running assertions, as a
     * simple mechanism to await a particular server-event (as revealed through
     * the log) before moving on.<p>
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
    
    private static java.util.logging.Level toJUL(System.Logger.Level level) {
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
    
    private static ConsoleHandler newConsoleHandler(java.util.logging.Level level) {
        ConsoleHandler h = new SystemOutInsteadOfSystemErr();
        h.setLevel(level);
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
         * Return immediately if a log record of the given level with the given
         * message has been published, or await it's arrival for a maximum of 3
         * seconds.<p>
         * 
         * Currently, due to implementation simplicity, only one away per
         * recorder instance is allowed.<p>
         * 
         * WARNING: This method may block the publication of a log record
         * temporarily and if so, the block is minuscule. Nonetheless, awaiting
         * should not be done by time-critical code.
         * 
         * @param level of record
         * @param message of record
         * 
         * @return {@code true} when target record is observed, or
         *         {@code false} if 3 seconds passes without observing the record
         * 
         * @throws IllegalStateException
         *             if this method was used before
         * 
         * @throws InterruptedException
         *             if the current thread is interrupted while waiting
         */
        public boolean await(Level level, String message) throws InterruptedException {
            requireNonNull(level);
            requireNonNull(message);
            
            CountDownLatch cl = new CountDownLatch(1);
            
            for (RecordListener rl : l) {
                rl.monitor((lvl, msg) -> {
                    if (lvl.equals(level) && msg.equals(message)) {
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
        
        Stream<RecordListener> listeners() {
            return Stream.of(l);
        }
    }
    
    private static class RecordListener extends Handler {
        private final Class<?> cmp;
        private final Deque<LogRecord> deq;
        private BiConsumer<Level, String> mon;
        
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
        synchronized void monitor(BiConsumer<Level, String> consumer) {
            if (mon != null) {
                throw new IllegalStateException();
            }
            records().forEach(r -> consumer.accept(r.getLevel(), r.getMessage()));
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
                mon.accept(record.getLevel(), record.getMessage());
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