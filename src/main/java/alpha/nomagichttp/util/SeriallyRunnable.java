package alpha.nomagichttp.util;

import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.requireNonNull;

/**
 * Decorates a {@code Runnable} called "the delegate" with the ability to
 * <i>{@code run}</i> serially despite recursive calls to this class from the
 * delegate itself or concurrent calls to this class by other threads.<p>
 * 
 * If a party (self or another thread) calls <i>{@code run}</i> when already
 * running, then the invocation will immediately schedule a new run to be
 * executed after the current execution completes and proceed to return without
 * blocking. The scheduled run will take place later in time immediately after
 * the current execution, either by the thread already executing or another
 * contending thread, whichever wins the race to start a new run at that
 * time.<p>
 * 
 * This class can be used as a primitive to protect a block of code from
 * parallel execution but still have the code block repeat itself for as long as
 * a signal keeps on arriving.<p>
 * 
 * This class does not try to "catch up" by keeping track of how many runs
 * attempted to start while one was already executing. Only a maximum of 1 such
 * extra repetition will be scheduled in any given moment of time.<p>
 * 
 * For example:
 * <pre>{@code
 *     TODO: Give example
 * }</pre>
 * 
 * 
 * <h2>Memory Synchronization</h2>
 * 
 * Each run establishes a <i>happens-before</i> relationship with the
 * <i>subsequent</i> run. Put in other words: client code does not need to
 * provide external synchronization in order to create memory visibility
 * <i>between</i> runs. "Normal" state variables can be written to by one run
 * and safely observed and/or updated in the next run, even if the subsequent
 * run is executed by another thread.<p>
 * 
 * Implementation-wise, this is guaranteed because each run starts with a read
 * of- followed by an ending write of a running-state variable using volatile
 * semantics. The subsequent initial read of the subsequent run is what
 * establishes the relationship with the write from after the previous run.<p>
 * 
 * This also means that the very first run is only guaranteed to be
 * synchronized-with the initial state value written by the class constructor.
 * Put in other words: if anything of great importance performed by thread A
 * happens between the point in time where an object of this class was created
 * up until the first run of said object by thread B, then client code has the
 * responsibility to make sure these actions are visible to thread B executing
 * the first run.
 * 
 * 
 * <h2>Modes Of Completion</h2>
 * 
 * <h3>Synchronous Mode</h3>
 * 
 * By default, as soon as the run-method returns, the delegate will become
 * eligible to execute again. This is called "synchronous mode" and any attempts
 * in this mode by client code to explicitly <i>{@link #complete}</i> will
 * immediately fail.
 * 
 * <h3>Asynchronous Mode</h3>
 * 
 * This class also supports serial execution of "logical runs" that doesn't
 * necessarily complete when the run-method returns. In <i>asynchronous mode</i>
 * (enabled through a constructor argument), client code must explicitly
 * complete the work by calling the <i>{@link #complete()}</i> method
 * <strong>exactly-once</strong>.<p>
 * 
 * The delegate will not be eligible to execute again before <i>both</i> the
 * run-method has returned and the complete signal has been raised.<p>
 * 
 * The complete signal can be raised either by the thread currently executing
 * the run-method (as a mechanism to temporarily abort the asynchronous nature
 * of the task) or by any thread in the future.<p>
 * 
 * As stated earlier, the run-method must return <strong>and</strong> an
 * explicit complete signal must have been raised before the delegate is
 * eligible to be executed again. This was implemented for the purpose
 * of not introducing a race which could have broken the serial nature of this
 * class (async task can complete before run method do). But, it also brings
 * some other few advantages.<p>
 * 
 * The task-submitting thread invoking the run-method can safely split the work
 * between itself and another thread - only when both finish will the delegate
 * be eligible to run again. If the work needs to be shared amongst even more
 * workers, then the call to the complete method must be coordinated, for
 * example; {@code CompletableFuture.allOf(myTasks).whenComplete((ign,ored) ->
 * mySeriallyRunnable.complete())}.<p>
 * 
 * Just as with {@code run()}, {@code complete()} too can block or return
 * immediately depending on if a new run was scheduled to execute and the
 * calling thread was selected to perform the new run.<p>
 * 
 * For example:
 * <pre>{@code
 *     TODO: Give example of a successful async task, and
 *     TODO: Give example of a unsuccesful async task, perhaps RejectedExecutionException.
 * }</pre>
 * 
 * 
 * <h2>Error Handling</h2>
 * 
 * If the delegate while executing throws a throwable, a potentially scheduled
 * repetition will be voided. The throwable will be rethrown as-is (in
 * asynchronous mode this could break a blocking call to {@code complete()}.<p>
 * 
 * If a throwable is thrown, then this does not invalidate the instance of this
 * class. A new subsequent attempt to run will succeed.<p>
 * 
 * However, if you wish to have a guarantee that the delegate is never executed
 * again after a throwable, then this is rather straight forward to implement
 * given the serial nature of this class, as in the following example:
 * 
 * <pre>{@code
 *     // if written to outside of SeriallyRunnable, make volatile
 *     private boolean closed = false;
 *     private final SeriallyRunnable serially = new SeriallyRunnable(this::notWhenClosed);
 *     
 *     public void perform() {
 *         // throws IllegalStateException if closed
 *         serially.run();
 *     }
 *     
 *     private void notWhenClosed() {
 *         if (closed) {
 *             throw new IllegalStateException();
 *         }
 *         
 *         try {
 *             logic();
 *         } catch (Throwable t) {
 *             closed = true;
 *             throw t;
 *         }
 *     }
 *     
 *     private void logic() {
 *         // this code runs serially until first error, then never again
 *     }
 * }</pre>
 * 
 * (Note to self: before using the example above, probably we should provide it
 * natively, either through a subclass or make this new feature (and
 * asynchronicity) something configurable through a builder.)<p>
 * 
 * The semantics for an exceptional return is not changed in asynchronous
 * mode. In other words: this class will assume an asynchronous task was never
 * started and the logical run is implicitly completed through the exceptional
 * return.<p>
 * 
 * The complete-method will signal complete for whatever logical run is active
 * at that time or if no one is active, an {@code IllegalStateException} will be
 * thrown.<p>
 * 
 * It is therefore very important that if the run-method successfully starts an
 * asynchronous task, then he must also ensure a normal return or otherwise make
 * sure the asynchronous task never signals complete.<p>
 * 
 * Starting an asynchronous task and then crash out from the run-method with a
 * throwable could have the effect that the asynchronous task completes a run he
 * did not intend to complete, which could further cascade into a new run
 * executed despite the previous one being in progress; i.e. breaking the serial
 * guarantee of this class<p>
 * 
 * When programming with this class in asynchronous mode, the most easiest
 * and safest thing to do is to start an asynchronous task and then immediately
 * return normally.
 * 
 * 
 * <h2>Performance Considerations</h2>
 * 
 * The implementation is lock-free and should perform well.
 * 
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class SeriallyRunnable implements Runnable
{
    private static final ThreadLocal<Boolean> RUNNING = ThreadLocal.withInitial(() -> false);
    
    private final Runnable delegate;
    private final boolean async;
    private final AtomicInteger state;
    private boolean recursivelyCompleted;
    private final AtomicInteger lastToFinishTriesAgain;
    
    private static final int
            END   = 1,
            BEGIN = 2,
            AGAIN = 3;
    
    /**
     * Constructs a {@code SeriallyRunnable} executing in synchronous mode.
     * 
     * @param delegate which runnable to run (non-null)
     * 
     * @throws NullPointerException if {@code delegate} is {@code null}
     */
    public SeriallyRunnable(Runnable delegate) {
        this(delegate, false);
    }
    
    /**
     * Constructs a {@code SeriallyRunnable}.
     *
     * @param delegate which runnable to run (non-null)
     * @param async async mode on/off
     * 
     * @throws NullPointerException if {@code delegate} is {@code null}
     */
    public SeriallyRunnable(Runnable delegate, boolean async) {
        this.delegate = requireNonNull(delegate);
        this.async = async;
        this.state = new AtomicInteger(END);
        this.recursivelyCompleted = false;
        this.lastToFinishTriesAgain = new AtomicInteger();
    }
    
    @Override
    public final void run() {
        /*
        
        Apart from details, the following implementation works semantically like
        this (pseudo-code provided to facilitate understanding):
        
          do {
              // must ALWAYS ask for permission before running
              if (!mayStart()) {
                  // not allowed to start, but at least I scheduled a new run
                  return;
              }
              
              delegate.run();
              
          // mark job as done, and perhaps I am obliged to go again
          } while (tryAgain());
        
         */
        
        while (mayStart()) {
            if (async) {
                RUNNING.set(true);
                recursivelyCompleted = false;
                lastToFinishTriesAgain.set(2);
            }
            
            try {
                delegate.run();
            } catch (Throwable t) {
                if (async) {
                    lastToFinishTriesAgain.set(-1);
                }
                state.set(END);
                throw t;
            } finally {
                RUNNING.set(false);
            }
            
            if (async) {
                if (recursivelyCompleted) {
                    if (tryAgain()) {
                        continue;
                    }
                } else if (lastToFinish() && tryAgain()) {
                    continue;
                }
            } else if (tryAgain()) {
                continue;
            }
            
            // Otherwise, job done
            break;
        }
    }
    
    /**
     * In asynchronous mode, mark the current logical run as completed.
     * 
     * @throws UnsupportedOperationException
     *             if asynchronous mode is not active
     * 
     * @throws IllegalStateException
     *             if no logical run is active or this method was called more than once
     */
    public void complete() {
        if (!async) {
            throw new UnsupportedOperationException("In synchronous mode.");
        }
        
        if (RUNNING.get()) {
            if (recursivelyCompleted) {
                throw new IllegalStateException("complete() called more than once.");
            }
            
            recursivelyCompleted = true;
            return;
        }
        
        if (lastToFinish() && tryAgain()) {
            run();
        }
    }
    
    /**
     * @return {@code} true if party may proceed to run the delegate,
     *         otherwise {@code false}
     */
    private boolean mayStart() {
        // repeatedly try to set a new state until success or break through return
        for (;;) {
            switch (state.get()) {
                case END:
                    if (state.compareAndSet(END, BEGIN)) {
                        // job not running; start
                        return true;
                    }
                    break;
                case BEGIN:
                    if (state.compareAndSet(BEGIN, AGAIN)) {
                        // job running; schedule re-run and exit
                        return false;
                    }
                    break;
                case AGAIN:
                    // job running and notified to restart, nothing to do
                    return false;
                default:
                    throw new AssertionError();
            }
        }
    }
    
    /**
     * This method marks the current run as completed and returns whether or not
     * the caller should run the delegate again. I.e., this method must be
     * called before returning out from the run-method.
     * 
     * @return {@code true} caller should run again, otherwise {@code false}
     */
    private boolean tryAgain() {
        for (int s;;) {
            switch (s = state.get()) {
                case BEGIN:
                    if (state.compareAndSet(BEGIN, END)) {
                        // No scheduled re-run, we're done
                        return false;
                    }
                    break;
                case AGAIN:
                    // Go again, return true
                    int prev = state.getAndSet(END);
                    assert prev == AGAIN : "Expected no other state updates after the AGAIN flag was set.";
                    return true;
                case END:
                    if (async) {
                        throw new IllegalStateException("No logical run active.");
                    } else {
                        throw new AssertionError();
                    }
                default:
                    throw new AssertionError();
            }
        }
    }
    
    private boolean lastToFinish() {
        int val = lastToFinishTriesAgain.decrementAndGet();
        
        if (val < 0) {
            throw new IllegalStateException(
                    "The run completed already (exceptionally) or complete() was called more than once.");
        }
        
        return val == 0;
    }
}