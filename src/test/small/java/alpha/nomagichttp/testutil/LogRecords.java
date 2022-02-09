package alpha.nomagichttp.testutil;

import org.assertj.core.groups.Tuple;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

import static java.lang.System.Logger.Level;
import static java.lang.reflect.Modifier.isFinal;
import static java.lang.reflect.Modifier.isStatic;
import static java.time.temporal.ChronoUnit.MILLIS;
import static java.util.Arrays.stream;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Utils for JUL's {@link LogRecord} and related types.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class LogRecords {
    private LogRecords() {
        // Empty
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
    public static Tuple rec(Level level, String msg) {
        return rec(level, msg, null);
    }
    
    /**
     * Create an AssertJ Tuple consisting of a log- level, message and error.
     * 
     * @param level of log record
     * @param msg of log record
     * @param error of log record
     * @return a tuple
     * 
     * @throws NullPointerException
     *             if {@code level} is {@code null},
     *             perhaps also for the other arguments
     */
    public static Tuple rec(Level level, String msg, Throwable error) {
        return tuple(toJUL(level), msg, error);
    }
    
    /**
     * Elegantly formats the given record.<p>
     * 
     * {@code LogRecord} does not implement {@code toString} and would return
     * something like "java.util.logging.LogRecord@4e196770".<p>
     * 
     * Internally, a new instance of a formatter class is used to produce the
     * string and so this method should only be used when programmatically
     * peeking log records from a {@link Logging.Recorder}.<p>
     * 
     * The same formatting is automagically applied to the Gradle report if test
     * calls {@link Logging#setLevel(Class, Level)}, except the
     * formatter instance will be re-used of course and not created anew for
     * each record.
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
    static java.util.logging.Level toJUL(System.Logger.Level level) {
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
    
    /**
     * Convert {@code java.util.logging.Level} to {@code System.Logger.Level}.
     * 
     * @param level to convert
     * @return the converted value
     * @throws NullPointerException if {@code level} is {@code null}
     */
    static System.Logger.Level toSL(java.util.logging.Level level) {
        requireNonNull(level);
        return stream(System.Logger.Level.values())
                .filter(l -> l.getSeverity() == level.intValue())
                .findAny().orElseThrow(() -> new IllegalArgumentException(
                        "No SL match for this level: " + level));
    }
    
    static final class ElegantFormatter extends Formatter {
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
            ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(r.getLongThreadID());
            String thread = info == null ? "dead-" + r.getLongThreadID() : info.getThreadName();
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
}