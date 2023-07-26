package alpha.nomagichttp.testutil;

import alpha.nomagichttp.HttpServer;

import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static alpha.nomagichttp.testutil.LogRecords.toJUL;
import static java.lang.System.Logger.Level;

/**
 * Logging utilities.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class Logging {
    private Logging() {
        // Empty
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
    
    private static final AtomicBoolean
            WEIRD_GUY_REMOVED = new AtomicBoolean(),
            GOOD_GUY_ADDED    = new AtomicBoolean();
    
    /**
     * Log everything.<p>
     * 
     * This method is equivalent to:
     * 
     * <pre>
     *     Logging.{@link #setLevel(Class, Level)
     *       setLevel}(HttpServer.class, Level.ALL);
     * </pre>
     */
    public static void everything() {
        setLevel(HttpServer.class, Level.ALL);
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
    
    private static void uninstallRootConsoleHandler() {
        final Logger root = Logger.getLogger("");
        
        Handler[] ch = Stream.of(root.getHandlers())
                .filter(Logging::isForeignConsoleHandler)
                .map(h -> (ConsoleHandler) h)
                .toArray(Handler[]::new);
        
        if (ch.length == 1) {
            root.removeHandler(ch[0]);
        } else if (ch.length > 1) {
            // One could specifically target "java.util.logging.LogManager$RootLogger@50013..."
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
    

}