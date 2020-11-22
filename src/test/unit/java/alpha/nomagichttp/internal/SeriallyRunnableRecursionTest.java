package alpha.nomagichttp.internal;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static java.lang.Integer.MAX_VALUE;
import static java.lang.Math.multiplyExact;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs {@code SeriallyRunnable} recursively in both sync- and async modes,
 * hopefully without running into a StackOverflowError.<p>
 * 
 * Instead of targeting a recursion depth of {@code Integer.MAX_VALUE} for each
 * test, we limit the depth to a value one hundred times greater than what is
 * probed through one executed experience to cause a StackOverflowError. There's
 * no guarantee the targeted depth would have always caused a StackOverflowError
 * as this number varies nondeterministically. But, the purpose of using the
 * boundary is to reduce the time these tests takes to complete and still
 * provide a high level of confidence that the implementation works.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class SeriallyRunnableRecursionTest
{
    private static long n;
    
    @BeforeAll
    static void test_BoundedRunnable() {
        BoundedRunnable testee = new BoundedRunnable(1, false);
        testee.run(); // <- only one run is allowed
        assertThatThrownBy(testee::run)
                .isExactlyInstanceOf(LimitReachedException.class);
        
        testee = new BoundedRunnable(MAX_VALUE, false);
        for (int i = 0; i < 10; ++i) testee.run(); // <-- okay, not bounded
        
        testee.andThenRun(testee);
        assertThatThrownBy(testee::run)
                .isExactlyInstanceOf(OverlapException.class);
    }
    
    @BeforeAll
    static void computeMaxRecursionLevel() {
        BoundedRunnable task = new BoundedRunnable(MAX_VALUE, true);
        task.andThenRun(task);
        
        try {
            task.run();
            throw new AssertionError("Expected SOE at some point.");
        } catch (StackOverflowError e) {
            System.out.println("Hit StackOverflowError after: " +
                    task.invocationCount());
            System.out.println("Will target recursive depth: " +
                    (n = multiplyExact(task.invocationCount(), 100)));
        }
    }
    
    // Having the task run itself causes StackOverflowError...
    @Test
    void verify_test_integrity() {
        BoundedRunnable task = new BoundedRunnable(n, true);
        task.andThenRun(task);
        
        assertThatThrownBy(task::run)
                .isExactlyInstanceOf(StackOverflowError.class);
    }
    
    // ...but wrapped in SeriallyRunnable, no longer - instead we hit the limit!
    @Test
    void synchronous_noStackOverflowError() {
        BoundedRunnable task = new BoundedRunnable(n, false);
        
        SeriallyRunnable sync = new SeriallyRunnable(task);
        task.andThenRun(sync);
        
        assertThatThrownBy(sync::run)
                .isExactlyInstanceOf(LimitReachedException.class);
    }
    
    // Same is true also in async mode
    @Test
    void asynchronous_noStackOverflowError() {
        BoundedRunnable task = new BoundedRunnable(n, false);
        
        SeriallyRunnable async = new SeriallyRunnable(task, true);
        task.andThenRun(() -> {
            async.run();
            async.complete();
        });
        
        assertThatThrownBy(async::run)
                .isExactlyInstanceOf(LimitReachedException.class);
    }
    
    /**
     * Accepts a {@code bound} which limits how many times the runnable can run
     * before crashing with a {@code LimitReachedException}. Further, {@code
     * acceptOverlap} if set to true, will cause a call overlapping with another
     * call to blow up with an {@code OverlapException} (i.e. strict
     * serialization; no recursion and no concurrency).<p>
     * 
     * Note: this class is not thread-safe and is expected to run in a
     * single-threaded test.
     */
    private static class BoundedRunnable implements Runnable {
        private final long bound;
        private final boolean acceptOverlap;
        private Runnable andThen;
        private int invocations;
        private int level;
        
        BoundedRunnable(long bound, boolean acceptOverlap) {
            this.bound = bound;
            this.acceptOverlap = acceptOverlap;
            this.andThen = () -> {};
        }
        
        void andThenRun(Runnable andThen) {
            this.andThen = andThen;
        }
        
        @Override
        public void run() {
            try {
                if (++level > 1 && !acceptOverlap) {
                    throw new OverlapException();
                }
                run0();
            } finally {
                --level;
            }
        }
        
        public void run0() {
            if (++invocations > bound) {
                throw new LimitReachedException();
            }
            
            andThen.run();
        }
        
        int invocationCount() {
            return invocations;
        }
    }
    
    private static class LimitReachedException extends RuntimeException {
        // Empty
    }
    
    private static class OverlapException extends RuntimeException {
        // Empty
    }
}