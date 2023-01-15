package alpha.nomagichttp.testutil;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static java.util.Arrays.copyOfRange;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.IntStream.range;
import static java.util.stream.Stream.of;


/**
 * A high-level thread scheduler.<p>
 * 
 * Used to orchestrate stages executed by threads managed internally by this
 * class. After all stages have completed, the test can then assert expected
 * state. Useful for testing the thread-safety of components.
 * 
 * <pre>
 *   // Test two threads incrementing a counter
 *   AtomicInteger counter = new AtomicInteger();
 *   ThreadScheduler.runInParallel(2, counter::incrementAndGet);
 *   assertThat(counter.get()).isEqualTo(2);
 * </pre>
 * 
 * This class does explicitly not create a happens-before relationship between
 * stages. It would be unfortunate if a component under test pass the test
 * because thread-safety was created as an unintentional side-effect of the test
 * itself.<p>
 * 
 * An unsafe counter implementation could still pass the previous example test,
 * because the test is not deterministic. For this reason, stages may run
 * sequentially without parallelism and instead rely on explicitly yielding
 * control to each other, a sort of playbook for threads.
 * 
 * <pre>{@code
 *   Yielder yielder = new ThreadScheduler.Yielder();
 *   
 *   class NotThreadSafeCounter {
 *     private int v;
 *     void increment(String interceptedBy) {
 *         int local = v;
 *         if (interceptedBy != null) {
 *             // Blocks until the specified stage completes
 *             yielder.continueStage(interceptedBy);
 *         }
 *         v = local + 1;
 *     }
 *     int get() {
 *         return v;
 *     }
 *   }
 *   
 *   NotThreadSafeCounter counter = new NotThreadSafeCounter();
 *   Stage t1s1 = new ThreadScheduler.Stage("T1", "S1", () -> counter.increment("T2S1"));
 *   Stage t2s1 = new ThreadScheduler.Stage("T2", "S1", () -> counter.increment(null));
 *   ThreadScheduler.runSequentially(yielder, t1s1, t2s1);
 *   assertThat(counter.get()).isEqualTo(1); // Now code can be proven wrong
 * }</pre>
 * 
 * When running sequentially using a yielder, a <i>best effort</i> is made to
 * not create happens-before relationships.  Still, the thread scheduler and
 * companion yielder does need to orchestrate how stages run. This is done
 * through opaque message passing and busy-waiting. The Java implementation is
 * free to use a stronger access mode than what is declared in code, meaning
 * there are no guarantees that the {@code ThreadScheduler} framework will not
 * have memory effects beyond intra-thread semantics.<p>
 * 
 * The point being that if we're testing the serial nature of a {@code
 * SeriallyRunnable} in asynchronous mode - for example - then we can not
 * <i>guarantee</i> the test passed because said component is programmed
 * correctly. The benefit is of course two-fold; we can prove what does
 * deterministically not work, and over time as the test is repeated across
 * different Java implementations, we gain more confidence that the component
 * works as advertised.<p>
 * 
 * See also {@code ThreadSchedulerTest}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class ThreadScheduler
{
    private ThreadScheduler() {
        // Intentionally empty
    }
    
    /**
     * Value type containing thread- and stage names and a runnable.
     */
    public static final class Stage {
        private final String threadName, stageName;
        private final Runnable code;
        
        /**
         * Constructs a {@code Stage}.
         * 
         * This is a shortcut for {@link #Stage(String, String, Runnable)}
         * which will prepend a "T" and "S" to the given arguments respectively.
         * 
         * @param threadNr e.g., 1 becomes "T1"
         * @param stageNr e.g., 1 becomes "S1"
         * @param code to execute
         */
        private Stage(int threadNr, int stageNr, Runnable code) {
            this("T" + threadNr, "S" + stageNr, code);
        }
        
        /**
         * Constructs a {@code Stage}.
         * 
         * @param threadName e.g., "T1"
         * @param stageName e.g., "S1"
         * @param code to execute
         * 
         * @throws NullPointerException if any arg is {@code null}
         */
        public Stage(String threadName, String stageName, Runnable code) {
            this.threadName = requireNonNull(threadName);
            this.stageName  = requireNonNull(stageName);
            this.code       = requireNonNull(code);
        }
        
        private String threadName() {
            return threadName;
        }
        
        private String stageName() {
            return stageName;
        }
        
        private Runnable code() {
            return code;
        }
        
        @Override
        public String toString() {
            return threadName() + stageName();
        }
    }
    
    /**
     * Run code asynchronously.<p>
     * 
     * The worker thread will have the name "T1".<p>
     * 
     * The thread that invokes this operation will lie dormant until the worker
     * completes, or 3 seconds have passed, whichever happens first.
     * 
     * @param code to run
     *
     * @throws NullPointerException
     *             if {@code code} is {@code null}
     *
     * @throws InterruptedException
     *             if driver thread is interrupted
     *
     * @throws TimeoutException
     *             if the run takes longer than 3 seconds
     *
     * @throws CompletionException
     *             anything else thrown from the worker
     */
    public static void runAsync(Runnable code) throws InterruptedException, TimeoutException {
        Runnable nop = () -> {};
        runInParallel(code, nop, nop);
    }
    
    /**
     * Run code asynchronously.<p>
     * 
     * This method is equivalent to
     * <pre>
     *     ThreadScheduler.{@link #runAsync(Runnable)
     *       runAsync}(code);
     * </pre>
     * except all checked exceptions are wrapped in a {@code RuntimeException}.
     * 
     * @param code to run
     */
    public static void runAsyncUnchecked(Runnable code) {
        try {
            runAsync(code);
        } catch (InterruptedException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }
    
    /**
     * An invocation of this method behaves in exactly the same way as the
     * invocation
     * <pre>
     *     ThreadScheduler.{@link #runInParallel(int, int, Runnable)
     *       runInParallel}(threads, repetitions, code);
     * </pre>
     * where {@code repetitions} is {@code 1}.
     * 
     * @param nThreads number of worker threads to use
     * @param code shared by all workers
     *
     * @throws IllegalArgumentException
     *             if any integer argument is less than 1
     *
     * @throws NullPointerException
     *             if {@code code} is {@code null}
     *
     * @throws InterruptedException
     *             if driver thread is interrupted
     *
     * @throws TimeoutException
     *             if the run takes longer than 3 seconds
     *
     * @throws CompletionException
     *             anything else thrown from a worker
     */
    public static void runInParallel(int nThreads, Runnable code)
            throws InterruptedException, TimeoutException
    {
        runInParallel(nThreads, 1, code);
    }
    
    /**
     * Run code asynchronously and in parallel.<p>
     * 
     * E.g., make ten threads increment a counter, five times per thread.
     * <pre>{@code
     *   ThreadScheduler.runInParallel(10, 5, myCounter::increment);
     *   assertThat(myCounter.value()).isEqualTo(50);
     * }</pre>
     * 
     * Worker thread names used will be "T1", "T2", and so on.<p>
     * 
     * The thread that invokes this operation will lie dormant until all workers
     * complete, or 3 seconds have passed, whichever happens first.
     * 
     * @param nThreads number of worker threads to use
     * @param nRepetitions number of code executions per thread
     * @param code shared by all workers
     * 
     * @throws IllegalArgumentException
     *             if any integer argument is less than 1
     * 
     * @throws NullPointerException
     *             if {@code code} is {@code null}
     * 
     * @throws InterruptedException
     *             if driver thread is interrupted
     * 
     * @throws TimeoutException
     *             if the run takes longer than 3 seconds
     * 
     * @throws CompletionException
     *             anything else thrown from a worker
     */
    public static void runInParallel(int nThreads, int nRepetitions, Runnable code)
            throws InterruptedException, TimeoutException
    {
        if (nThreads < 1 || nRepetitions < 1) {
            throw new IllegalArgumentException();
        }
        
        Stage[] arr = range(0, nThreads).boxed().flatMap(t ->
                      range(0, nRepetitions).mapToObj(   s ->
                              new Stage(t + 1, s, code))).toArray(Stage[]::new);
        
        switch (arr.length) {
            case 1  -> runAsync(arr[0].code());
            case 2  -> runInParallel(arr[0], arr[1]);
            default -> runInParallel(arr[0], arr[1], copyOfRange(arr, 2, arr.length));
        }
    }
    
    /**
     * Run code asynchronously and in parallel.<p>
     * 
     * As many threads will be created as there are arguments to this operation.
     * All threads will await each other's readiness before collectively
     * starting.<p>
     * 
     * Worker thread names used will be "T1", "T2", and so on.<p>
     * 
     * The thread that invokes this operation will lie dormant until all workers
     * complete, or 3 seconds have passed, whichever happens first.
     * 
     * @param first thread's unit of work
     * @param second thread's unit of work
     * @param more threads+work optionally (but at least two are required)
     * 
     * @throws NullPointerException
     *             if any arg (or element thereof) is {@code null}
     * 
     * @throws InterruptedException
     *             if driver thread is interrupted
     * 
     * @throws TimeoutException
     *             if the run takes longer than 3 seconds
     * 
     * @throws CompletionException
     *             anything else thrown from a worker
     */
    public static void runInParallel(Runnable first, Runnable second, Runnable... more)
            throws InterruptedException, TimeoutException
    {
        Stage f = new Stage(1, 1, first);
        Stage s = new Stage(2, 1, second);
        Stage[] m = range(0, more.length)
                        .mapToObj(i -> new Stage(i + 3, 1, more[i]))
                        .toArray(Stage[]::new);
        runInParallel(f, s, m);
    }
    
    /**
     * Run stages in parallel.<p>
     * 
     * As many threads will be created as there are unique thread names in the
     * given stages. All threads will await each other's readiness before
     * collectively starting to execute the stages that belong to respective
     * thread. Per thread, the stages execute in sequence and orderly according
     * to the argument order specified to this method.<p>
     * 
     * No validation occur related to the number of actual threads used. For
     * example, technically, one could specify only one unique thread name,
     * albeit this would then be a moot test (nothing runs in parallel).<p>
     * 
     * The thread that invokes this operation will lie dormant until all workers
     * complete, or 3 seconds have passed, whichever happens first.
     * 
     * @param first stage
     * @param second stage
     * @param more optionally (but at least two are required)
     * 
     * @throws NullPointerException
     *             if any arg (or element thereof) is {@code null}
     * 
     * @throws InterruptedException
     *             if driver thread is interrupted
     * 
     * @throws TimeoutException
     *             if the run takes longer than 3 seconds
     * 
     * @throws CompletionException
     *             anything else thrown from a worker/stage
     */
    private static void runInParallel(Stage first, Stage second, Stage... more)
            throws InterruptedException, TimeoutException
    {
        Map<String, List<Runnable>> threadNameToCode = stream(first, second, more)
                .collect(groupingBy(Stage::threadName, mapping(Stage::code, toList())));
        
        CountDownLatch ready = new CountDownLatch(threadNameToCode.size()),
                       done  = new CountDownLatch(threadNameToCode.size());
        
        Collection<CompletionException> problems = new ConcurrentLinkedQueue<>();
        
        threadNameToCode.forEach((name, work) ->
                new RunInParallelThread(name, work, ready, done, problems).start());
        
        try {
            if (!done.await(3, SECONDS)) {
                var ex = new TimeoutException("Driver thread timed out waiting on workers.");
                problems.forEach(ex::addSuppressed);
                throw ex;
            }
        } catch (InterruptedException ex) {
            problems.forEach(ex::addSuppressed);
            throw ex;
        }
        
        throwFirstOf(problems);
    }
    
    private static void throwFirstOf(Collection<? extends CompletionException> problems)
            throws TimeoutException, IllegalArgumentException, CompletionException
    {
        if (problems.isEmpty()) {
            return;
        }
        
        Throwable t = null;
        for (Throwable e : problems) {
            if (e instanceof PleaseUnboxTheCause) {
                e = e.getCause();
            }
            if (t == null) {
                t = e;
            } else {
                t.addSuppressed(e);
            }
        }
        
        if (t instanceof TimeoutException) {
            throw (TimeoutException) t;
        } else if (t instanceof IllegalArgumentException) {
            throw (IllegalArgumentException) t;
        } else if (t instanceof CompletionException) {
            throw (CompletionException) t;
        }
        
        assert false : "Unexpected type: " + t.getClass();
    }
    
    private static final class RunInParallelThread extends Thread
    {
        private final List<Runnable> work;
        private final CountDownLatch ready, done;
        private final Collection<? super CompletionException> onError;
        
        RunInParallelThread(
                String name,
                List<Runnable> work,
                CountDownLatch ready,
                CountDownLatch done,
                Collection<? super CompletionException> onError)
        {
            setName(name);
            this.work = work;
            this.ready = ready;
            this.done = done;
            this.onError = onError;
            setDaemon(true);
        }
        
        @Override
        public void run() {
            ready.countDown();
            try {
                if (!ready.await(3, SECONDS)) {
                    onError.add(new CompletionException(new TimeoutException(
                            "Worker thread timed out waiting on start signal.")));
                    done.countDown();
                    return;
                }
            } catch (InterruptedException e) {
                onError.add(new CompletionException(e));
                done.countDown();
                return;
            }
            try {
                work.forEach(Runnable::run);
            } catch (Throwable t) {
                onError.add(new CompletionException(t));
            }
            done.countDown();
        }
    }
    
    /**
     * Run stages sequentially as orchestrated through the yielder.<p>
     * 
     * The first given stage executes immediately and is the only stage that
     * is initiated by the driver thread. The remaining path of stage execution
     * is solely determined by calls to the yielder. In the end, all stages must
     * have been given control at least once and must have executed fully. No
     * repetition allowed.<p>
     * 
     * The thread that invokes this operation will lie dormant until all workers
     * complete, or 3 seconds have passed, whichever happens first.
     * 
     * @param yielder the playbook
     * @param first stage
     * @param second stage
     * @param more optionally (but at least two are required)
     * 
     * @throws NullPointerException
     *             if any arg is {@code null}
     * 
     * @throws InterruptedException
     *             if driver thread is interrupted
     * 
     * @throws TimeoutException
     *             if the run takes longer than 3 seconds
     * 
     * @throws IllegalArgumentException
     *             if not all stages executed fully
     *             (missing a {@code yielder.continueStage("someone")})
     * 
     * @throws CompletionException
     *             anything else thrown from a worker/stage
     */
    public static void runSequentially(Yielder yielder, Stage first, Stage second, Stage... more)
            throws InterruptedException, TimeoutException
    {
        Map<String, List<Stage>> threadNameToStages
                = stream(first, second, more).collect(groupingBy(Stage::threadName));
        
        Collection<CompletionException> problems = new ConcurrentLinkedQueue<>();
        CountDownLatch done = new CountDownLatch(threadNameToStages.size());
        Instant giveUp = Instant.now().plusSeconds(3);
        
        List<Thread> workers = threadNameToStages.entrySet().stream().map(e -> {
            Thread t = new RunInSequenceThread(
                    Thread.currentThread(), e.getKey(), yielder, e.getValue(), done, giveUp, problems);
            t.start();
            return t;
        }).collect(toList());
        
        yielder.append(first.toString());
        
        try {
            if (!done.await(3, SECONDS)) {
                var ex = new TimeoutException(
                        "Driver thread timed out waiting on workers.");
                problems.forEach(ex::addSuppressed);
                throw ex;
            }
        } catch (InterruptedException ex) {
            if (problems.isEmpty()) {
                throw ex;
            } // Else interrupted by driver (who first registered a problem)
        } finally {
            forceStop(giveUp, yielder, workers);
        }
        
        throwFirstOf(problems);
    }
    
    @SafeVarargs
    private static <T> Stream<T> stream(T first, T second, T... more) {
        @SuppressWarnings("varargs")
        Stream<T> s = Stream.concat(of(first, second), of(more));
        return s;
    }
    
    private static void forceStop(Instant giveUp, Yielder yielder, Collection<Thread> workers) throws TimeoutException {
        while (workers.stream().anyMatch(Thread::isAlive)) {
            Yielder.STACK.setVolatile(yielder, ":null");
            if (Instant.now().isAfter(giveUp)) {
                throw new TimeoutException(
                        "Failed stopping all workers within max runtime. " +
                        "Unknown what exceptions this one has suppressed.");
            }
        }
    }
    
    /**
     * May be used by a stage to yield execution to another stage.
     */
    public static final class Yielder {
        
        /**
         * Constructs a {@code Yielder}.
         */
        public Yielder() {
            // Intentionally empty
        }
        
        /**
         * Yield control from one stage to the specified other.<p>
         * 
         * This method will block until the current stage is yielded back
         * control.<p>
         * 
         * A stage can technically yield to itself, it's not going to have a
         * visible effect. The thread returns immediately.<p>
         * 
         * Stage execution can jump backwards any number of levels (e.g. stage 1
         * {@literal >} 2 {@literal >} 3 {@literal >} 1), but in the end, all
         * stages given to the thread scheduler must execute fully or else the
         * test will fail.<p>
         * 
         * A stage can not yield to a stage that has already completed.<p>
         * 
         * If you specify a stage that does not exist than most likely the test
         * will timeout (haven't tested lol).
         * 
         * @param threadAndStageNames e.g. "T1S1"
         * 
         * @throws NullPointerException
         *             if {@code threadAndStageName} is {@code null}
         * 
         * @throws UnsupportedOperationException
         *             if called by a non-worker thread
         * 
         * @throws IllegalArgumentException
         *             if the given stage has already executed fully
         *             (repetition not permitted)
         */
        public void continueStage(String threadAndStageNames) {
            requireNonNull(threadAndStageNames);
            
            final RunInSequenceThread self;
            if (!(Thread.currentThread() instanceof RunInSequenceThread)) {
                throw new UnsupportedOperationException(
                        "Call this method from inside a stage.");
            }
            self = (RunInSequenceThread) Thread.currentThread();
            
            // Either we go back or forward in stack
            if (!__tryUnwindTo(threadAndStageNames)) {
                append(threadAndStageNames);
            }
            self.waitOnMyTurn();
        }
        
        // Good read: http://gee.cs.oswego.edu/dl/html/j9mm.html
        private static final VarHandle STACK;
        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                STACK = l.findVarHandle(Yielder.class, "stack", String.class);
            } catch (ReflectiveOperationException e) {
                throw new Error(e);
            }
        }
        
        /*
         * This guy is our message passing mechanism. A thread that wish to
         * yield control appends the next stage's thread- and stage names to the
         * stack. Busy-waiting threads peek the tail constantly, but only the
         * thread which observes a stage that belongs to him proceeds to execute
         * it. Once the stage completes, the worker removes it from the stack,
         * which reveals the next stage, picked up by that worker, and so the
         * cycle continues.
         * 
         * Initial state of the stack  is set by the driver.
         * 
         * ":null:t1s1"      // T1 picks up his stage and executes it
         * ":null:t1s1:t2s1" // T1 yielded control to T2, goes back into busy-waiting
         * ":null:t1s1"      // T2 completed his stage and popped the stack
         * ":null"           // T1 continued where he left off, then popped the stack
         * 
         * When workers observe "null" the test is over.
         */
        private String stack = ":";
        
        private String getCurrentStage() {
            String s = __getStack();
            return s.substring(s.lastIndexOf(":") + 1);
        }
        
        private void append(String stage) {
            String stack = __getStack();
            if (stack.equals(":")) {
                stack = ":null";
            }
            __setStack(stack + ":" + stage);
        }
        
        private boolean pop() {
            String stack = __getStack();
            assert !stack.equals(":") : "Should be initialized.";
            
            if (stack.endsWith("null")) {
                return false;
            }
            
            // Remove last stage
            __setStack(stack.substring(0, stack.lastIndexOf(":")));
            return true;
        }
        
        private boolean __tryUnwindTo(String stage) {
            String s = __getStack();
            int p = s.lastIndexOf(stage);
            if (p == -1) {
                return false;
            }
            __setStack(s.substring(0, p + stage.length()));
            return true;
        }
        
        private String __getStack() {
            return (String) STACK.getOpaque(this);
        }
        
        private void __setStack(String stack) {
            STACK.setOpaque(this, stack);
        }
    }
    
    /**
     * The cause of this exception must be unboxed and thrown as-is.<p>
     * 
     * For example, an {@code IllegalArgumentException} that was thrown from a
     * worker because not all stages completed, indicates a bad {@code yielder}
     * and must therefore be thrown by the {@code runInParallel()} method
     * unwrapped.
     */
    private static final class PleaseUnboxTheCause extends CompletionException {
        private static final long serialVersionUID = 1L;
        PleaseUnboxTheCause(TimeoutException e) {
            super(e);
        }
        PleaseUnboxTheCause(IllegalArgumentException e) {
            super(e);
        }
    }
    
    private static final class RunInSequenceThread extends Thread {
        private final Thread driver;
        private final Yielder yielder;
        private final Collection<Stage> myStages;
        private final Set<Stage> myStarted;
        private final Set<Stage> myFinished;
        private final CountDownLatch done;
        private final Instant giveUp;
        private final Collection<CompletionException> onError;
        
        RunInSequenceThread(
                Thread driver,
                String name,
                Yielder yielder,
                Collection<Stage> myStages,
                CountDownLatch done,
                Instant giveUp,
                Collection<CompletionException> onError)
        {
            this.driver = driver;
            this.yielder = yielder;
            this.myStages = myStages;
            this.myStarted = new HashSet<>();
            this.myFinished = new HashSet<>();
            this.done = done;
            this.giveUp = giveUp;
            this.onError = onError;
            setName(name);
            setDaemon(true);
        }
        
        @Override
        public void run() {
            try {
                waitOnMyTurn();
            } catch (Throwable t) {
                onError.add(t instanceof CompletionException ?
                        (CompletionException) t :
                        new CompletionException(t));
                driver.interrupt();
            }
            done.countDown();
        }
        
        void waitOnMyTurn() {
            for (;;) {
                requireWithinRuntime();
                
                String token = yielder.getCurrentStage();
                
                if (token.equals("null")) { // Test over
                    requireAllStagesCompleted();
                    return;
                }
                
                Stage ours = findRef(token);
                
                if (ours == null) { // Someone else's
                    Thread.onSpinWait();
                    continue;
                }
                
                if (myStarted.contains(ours)) {
                    // Recursive wake-up, continue already started stage
                    return;
                }
                
                requireNotAlreadyCompleted(ours);
                runTilCompletion(ours);
                
                if (!yielder.pop()) { // Test over
                    return;
                } else {
                    Thread.onSpinWait();
                }
            }
        }
        
        private void requireWithinRuntime() {
            if (Instant.now().isAfter(giveUp)) {
                throw new PleaseUnboxTheCause(new TimeoutException(
                        "Busy-wait prolonged past max runtime."));
            }
        }
        
        private void requireAllStagesCompleted() {
            if (!myStarted.isEmpty()) {
                throw new PleaseUnboxTheCause(new IllegalArgumentException(
                        "Stage(s) started but never completed: " + myStarted));
            }
        }
        
        private Stage findRef(String token) {
            if (!token.startsWith(getName())) {
                return null;
            }
            return myStages.stream()
                    .filter(s -> token.endsWith(s.stageName()))
                    .findFirst()
                    .get();
        }
        
        private void requireNotAlreadyCompleted(Stage s) {
            if (myFinished.contains(s)) {
                throw new IllegalArgumentException(
                        "Stage \" " + s + " \" already finished. " +
                        "Repetition not supported.");
            }
        }
        
        private void runTilCompletion(Stage s) {
            myStarted.add(s);
            s.code().run();
            myStarted.remove(s);
            myFinished.add(s);
        }
    }
}