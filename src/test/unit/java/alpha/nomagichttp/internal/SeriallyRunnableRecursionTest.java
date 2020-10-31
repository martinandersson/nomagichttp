package alpha.nomagichttp.internal;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
    static void computeMaxRecursionLevel() {
        BoundedRunnable task = new BoundedRunnable(Integer.MAX_VALUE);
        task.andThenRun(task);
        
        try {
            task.run();
            throw new AssertionError("Expected SOE at some point.");
        } catch (StackOverflowError e) {
            System.out.println("Hit StackOverflowError after: " + task.runCount());
            System.out.println("Will target recursive depth: " + (n = multiplyExact(task.runCount(), 100)));
        }
    }
    
    @Test
    void verify_test_integrity() {
        BoundedRunnable task = new BoundedRunnable(n);
        task.andThenRun(task);
        
        assertThatThrownBy(task::run)
                .isExactlyInstanceOf(StackOverflowError.class);
    }
    
    @Test
    void synchronous_noStackOverflowError() {
        BoundedRunnable task = new BoundedRunnable(n);
        
        SeriallyRunnable sync = new SeriallyRunnable(task);
        task.andThenRun(sync);
        
        assertThatThrownBy(sync::run)
                .isExactlyInstanceOf(LimitReachedException.class);
    }
    
    @Test
    void asynchronous_noStackOverflowError() {
        BoundedRunnable task = new BoundedRunnable(n);
        
        SeriallyRunnable async = new SeriallyRunnable(task, true);
        task.andThenRun(() -> {
            async.run();
            async.complete();
        });
        
        assertThatThrownBy(async::run)
                .isExactlyInstanceOf(LimitReachedException.class);
    }
    
    private static class BoundedRunnable implements Runnable {
        private final long max;
        private Runnable andThen;
        private int runs;
        
        BoundedRunnable(long max) {
            this.max = max;
        }
        
        void andThenRun(Runnable andThen) {
            this.andThen = andThen;
        }
        
        @Override
        public void run() {
            if (++runs == max) {
                throw new LimitReachedException();
            }
            
            andThen.run();
        }
        
        int runCount() {
            return runs;
        }
    }
    
    private static class LimitReachedException extends RuntimeException {
        // Empty
    }
}