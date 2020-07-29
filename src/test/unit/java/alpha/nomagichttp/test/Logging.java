package alpha.nomagichttp.test;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.Optional;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static java.lang.reflect.Modifier.isFinal;
import static java.lang.reflect.Modifier.isStatic;
import static java.time.temporal.ChronoUnit.MILLIS;
import static java.util.Arrays.stream;

public final class Logging
{
    private Logging() {
        // Empty
    }
    
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
    
    public static void addHandler(Class<?> component, Handler handler) {
        Logger.getLogger(component.getPackageName()).addHandler(handler);
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
                .findAny().orElseThrow(() -> new UnsupportedOperationException(
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
        
        private static String rightPad(String s, int minLenght) {
            if (s.length() >= minLenght) {
                return s;
            }
            
            return s + " ".repeat(minLenght - s.length());
        }
    }
}