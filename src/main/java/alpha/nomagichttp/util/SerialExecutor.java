package alpha.nomagichttp.util;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

import static java.lang.ThreadLocal.withInitial;

/**
 * Executes actions serially in FIFO order without overlapping unless configured
 * to allow for recursion.<p>
 * 
 * This class is quite simple, really. It holds an unbounded concurrent {@link
 * Queue} of actions to execute, and a {@link SeriallyRunnable} is used to run
 * them (so, same memory visibility rules apply).<p>
 * 
 * If the executor is busy and a new action arrives, whether the action executes
 * directly or is scheduled for the future depends on thread identity and a
 * boolean {@code mayRecurse} constructor argument.<p>
 * 
 * With {@code mayRecurse} {@code true}, a thread already executing an action
 * submitting a new one will recursively execute the new action without regards
 * to how many other actions is sitting in the queue. This comes with a
 * performance improvement since recursive actions impose virtually no overhead
 * at all and co-dependent actions will not appear to be out of order (discussed
 * monetarily). It may however produce a {@code StackOverflowError}.<p>
 * 
 * If {@code mayRecurse} is {@code false} then all actions will be scheduled for
 * the future no matter which thread is calling in; this stops recursion from
 * happening. It's a safe strategy as it will never produce a {@code
 * StackOverflowError}, it will be more fair in execution order (no action will
 * take precedence over another) and at times of contention it will have a
 * higher probability of distributing work amongst threads.<p>
 * 
 * Alas, there's one noticeable drawback with using {@code mayRecurse} {@code
 * false}. Without recursion, execution may appear to be re-ordered. For
 * example, suppose the logic of function A calls function B and both run
 * through the same SerialExecutor, then A will complete first before B
 * executes. With recursion, B would have executed before A completes. Whether
 * this has an impact on program correctness is very much dependent on the
 * context of the use site.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class SerialExecutor implements Executor
{
    private static final ThreadLocal<Boolean> EXECUTING = withInitial(() -> false);
    
    private final Queue<Runnable> actions;
    private final SeriallyRunnable serial;
    private final boolean mayRecurse;
    
    /**
     * Constructs a serial execute that never recurse.
     */
    public SerialExecutor() {
        this(false);
    }
    
    /**
     * Constructs a serial executor.
     * 
     * @param mayRecurse {@code true} or {@code false}
     */
    public SerialExecutor(boolean mayRecurse) {
        this.actions = new ConcurrentLinkedQueue<>();
        this.serial  = new SeriallyRunnable(this::pollAndExecute);
        this.mayRecurse = mayRecurse;
    }
    
    /**
     * Execute the given action or schedule it for future execution.
     * 
     * @param action to execute
     */
    @Override
    public void execute(Runnable action) {
        if (mayRecurse && EXECUTING.get()) {
            action.run();
        } else {
            actions.add(action);
            serial.run();
        }
    }
    
    private void pollAndExecute() {
        try {
            // if statement is merely a hint for the compiler/JIT to possibly
            // remove the use of EXECUTING if not necessary
            if (mayRecurse) EXECUTING.set(true);
            Runnable a;
            while ((a = actions.poll()) != null) {
                a.run();
            }
        } finally {
            if (mayRecurse) EXECUTING.set(false);
        }
    }
}