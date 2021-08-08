package alpha.nomagichttp.util;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Decorates a {@code Runnable} called "the delegate" with the ability to
 * <i>run serially</i> despite recursive calls to this class from the delegate
 * itself or concurrent calls to this class by other threads.<p>
 * 
 * If a party (self or another thread) calls <i>{@code run}</i> when already
 * running, then the invocation will immediately schedule a new run to be
 * executed after the current execution completes and proceed to return without
 * blocking (no reentrancy, no waste of stack-memory, no {@code
 * StackOverflowError}). The scheduled run will take place later in time
 * immediately after the current execution, either by the thread already
 * executing or another competing thread, whichever wins the race to start a new
 * run at that time.<p>
 * 
 * This class can be used as a primitive to protect a block of code from
 * parallel execution but still have the code block repeat itself as to not miss
 * processing updates.<p>
 * 
 * This class does not try to "catch up" by keeping track of how many runs
 * attempted to start while one was already executing. Only a maximum of 1 such
 * extra repetition will be scheduled in any given moment of time.<p>
 * 
 * This class is used throughout the codebase of NoMagicHTTP to solve several
 * problems that requires a serial solution. For instance asynchronous channels
 * may have concurrent pending read/write operations, but not concurrent pending
 * operations of the same type. This class is also a core contributor behind the
 * awesome semantics defined in {@link Publishers} that completely eradicates
 * the need for the end-user to trouble himself with reentrancy and
 * thread-safety.<p>
 * 
 * In this example, we process items asynchronously, serially and never miss an
 * update with just a few lines of code:
 * 
 * <pre>{@code
 *   // State fields
 *   Queue<Item> items = ...concurrent collection of some kind
 *   Runnable operation = new SeriallyRunnable(this::pollAndProcessAsync, true);
 *   
 *   public void add(Item i) {
 *       items.add(i);
 *       operation.run();
 *   }
 *   
 *   private void pollAndProcessAsync() {
 *       Item i = items.poll();
 *       if (i == null) {
 *           operation.complete();
 *       } else {
 *           myThreadPool.submit(() -> {
 *               doSomethingWithItem(i);
 *               operation.complete();
 *               operation.run(); // Signal re-run, perhaps more items
 *           });
 *       }
 *   }
 * }</pre>
 * 
 * In the previous example, order between {@code .run()} and {@code .complete()}
 * doesn't really matter for program correctness, but if run comes after then we
 * increase the chance that a competing thread wins the race to run a scheduled
 * re-run with the benefit of unloading the current thread from being kept
 * busy.<p>
 * 
 * There's no point in configuring async mode or signalling a re-run if all
 * items are processed in one go:
 * 
 * <pre>{@code
 *   Runnable operation = new SeriallyRunnable(this::pollAndProcess);
 *   
 *   private void pollAndProcess() {
 *       Item i;
 *       while ((i = items.poll()) != null) {
 *           doSomethingWithItem(i);
 *       }
 *   }
 * }</pre>
 * 
 * 
 * <h2>Memory Synchronization</h2>
 * 
 * Each run establishes a <i>happens-before</i> relationship with the
 * <i>subsequent</i> run. Client code does not need to provide external
 * synchronization in order to create memory visibility <i>between</i> runs.
 * "Normal" state variables can be written to by one run and safely observed
 * and/or updated in the next run, even if the subsequent run is executed by
 * another thread.<p>
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
 * the first run.<p>
 * 
 * There is only one atomic running-state variable in this class, which also
 * embeds the flag/value of a scheduled re-run. Thus, the scheduling of a run
 * happens-before the next run. This means that the actions of the thread
 * initiating a run will be observed by whichever thread is executing it, i.e.
 * there is no need to write to volatile fields if these updates are also
 * followed by {@code run()}).
 * 
 * 
 * <h2>Modes Of Completion</h2>
 * 
 * <h3>Synchronous Mode</h3>
 * 
 * By default, as soon as the run-method returns, the delegate will become
 * eligible to execute again. This is called "synchronous mode" and client code
 * must not call <i>{@link #complete}</i>.
 * 
 * <h3>Asynchronous Mode</h3>
 * 
 * This class also supports serial execution of "logical runs" that doesn't
 * necessarily complete when the run-method returns. In <i>asynchronous mode</i>
 * (enabled through a constructor argument), client code must mark the task
 * completed by calling <i>{@link #complete()}</i>
 * <strong>exactly-once</strong>.<p>
 * 
 * The complete signal can be raised either by the thread currently executing
 * the run-method (as a mechanism to abort the asynchronous nature of the task)
 * or by any thread in the future.<p>
 * 
 * The delegate will not be eligible to execute again before both the
 * run-method has returned and the complete signal has been raised. This was
 * implemented not only to support the executing thread also raising the
 * complete signal, but also to fix a race where an async task completes before
 * the run method do which could have ended up breaking the serial guarantee of
 * this class.<p>
 * 
 * The requirement for both parties to complete also brings in another advantage
 * free of charge. The task-submitting thread invoking the run-method can safely
 * split the work between itself and another thread - only when both finish will
 * the delegate be eligible to run again. If the work needs to be shared amongst
 * even more workers, then the call to the complete method must be coordinated,
 * for example; {@code CompletableFuture.allOf(myTasks).whenComplete((ign,ored) ->
 * mySeriallyRunnable.complete())}.<p>
 * 
 * Just as with {@code run()}, {@code complete()} too can block or return
 * immediately depending on if a new run was scheduled concurrently to execute
 * and the calling thread was selected to perform the new run.<p>
 * 
 * Semantically, the same memory semantics applies as in synchronous mode. In
 * particular, the state variable is touched after both the implicit completion
 * (normal or exceptional return from the run method) and explicit completion
 * (the complete method) meaning that updates from a thread running the serially
 * runnable will be visible to the next run even if the logical run is completed
 * by another thread.
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
 * However, if one wish to have a guarantee that the delegate is never executed
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
 * The semantics for an exceptional return is not changed in asynchronous
 * mode. This class will assume that an asynchronous task was never started and
 * the logical run is implicitly completed through the exceptional return.<p>
 * 
 * The complete-method will signal complete for whatever logical run is active
 * at that time or if no one is active, an {@code IllegalStateException} will be
 * thrown.<p>
 * 
 * It is therefore imperative that if the run-method successfully starts an
 * asynchronous task, then it must also ensure a normal return or otherwise make
 * sure the asynchronous task never signals complete.<p>
 * 
 * Starting an asynchronous task and then crash out from the run-method with a
 * throwable could have the effect that the asynchronous task completes a run it
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
 */
public final class SeriallyRunnable implements Runnable
{
    private static final int
            END     = 0, // Not running, may start
            BEGIN_1 = 1, // Running, awaiting one completion
            BEGIN_2 = 2, // Running, awaiting two completions
            AGAIN_1 = 3, // BEGIN_1 with extra run scheduled
            AGAIN_2 = 4; // BEGIN_2 with extra run scheduled
    
    private final Runnable delegate;
    private final boolean async;
    private final AtomicInteger state;
    private final ThreadLocal<Boolean> initiator;
    
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
        this.delegate  = delegate;
        this.async     = async;
        this.state     = new AtomicInteger(END);
        this.initiator = ThreadLocal.withInitial(() -> false);
    }
    
    @Override
    public void run() {
        boolean tryAgain = true;
        while (tryAgain) {
            // must ask for permission before running
            if (!mayStart()) {
                // well, at least I scheduled a new run
                return;
            }
            
            try {
                initiator.set(true);
                delegate.run();
            } catch (Throwable t) {
                if (async) {
                    // assume async task failed to start
                    try {
                        getAndCountDown();
                    } catch (IllegalStateException next) {
                        t.addSuppressed(next);
                    }
                }
                throw t;
            } finally {
                try {
                    tryAgain = countDownAndTryAgain();
                } finally {
                    initiator.set(false);
                }
            }
        }
    }
    
    private boolean mayStart() {
        int old = state.getAndUpdate(v -> {
            switch (v) {
                case END:
                    // job not running; start
                    v = async ? BEGIN_2 : BEGIN_1;
                    break;
                case BEGIN_1:
                    // job running; schedule re-run and exit
                    v = AGAIN_1;
                    break;
                case BEGIN_2:
                    v = AGAIN_2;
                    break;
                case AGAIN_1:
                case AGAIN_2:
                    // job running and already notified to restart, nothing to do
                    break;
                default:
                    throw new AssertionError();
            }
            return v;
        });
        return old == END;
    }
    
    /**
     * In asynchronous mode, mark the active logical run as completed.
     * 
     * @throws IllegalStateException if running in synchronous mode, or
     *                               if no run is active
     */
    public void complete() {
        if (!async) {
            throw new IllegalStateException("Call to complete() in synchronous mode.");
        }
        if (countDownAndTryAgain() && !initiator.get()) {
            run();
        }
    }
    
    private boolean countDownAndTryAgain() {
        return getAndCountDown() == AGAIN_1;
    }
    
    private int getAndCountDown() {
        return state.getAndUpdate(v -> {
            switch (v) {
                case END:
                    throw new IllegalStateException("No run active.");
                case BEGIN_1:
                case AGAIN_1:
                    v = END;
                    break;
                case BEGIN_2:
                    v = BEGIN_1;
                    break;
                case AGAIN_2:
                    v = AGAIN_1;
                    break;
                default:
                    throw new AssertionError();
            }
            return v;
        });
    }
}