package alpha.nomagichttp.testutil;

import org.junit.jupiter.api.Test;

import java.util.Queue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static alpha.nomagichttp.testutil.ThreadScheduler.Stage;
import static alpha.nomagichttp.testutil.ThreadScheduler.Yielder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Small tests for {@link ThreadScheduler}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class ThreadSchedulerTest
{
    @Test
    void atomicIntegerExample() throws InterruptedException, TimeoutException {
        AtomicInteger c = new AtomicInteger();
        ThreadScheduler.runInParallel(2, c::incrementAndGet);
        assertThat(c.get()).isEqualTo(2);
    }
    
    @Test
    void notThreadSafeCounterExample() throws Throwable {
        Yielder y = new Yielder();
        
        class NotThreadSafeCounter {
            private int v;
            void increment(String interceptedBy) {
                int old = v;
                if (interceptedBy != null) {
                    y.continueStage(interceptedBy);
                }
                v = old + 1;
            }
            int get() {
                return v;
            }
        }
        
        NotThreadSafeCounter c = new NotThreadSafeCounter();
        Stage t1s1 = new Stage("T1", "S1", () -> c.increment("T2S1"));
        Stage t2s1 = new Stage("T2", "S1", () -> c.increment(null));
        ThreadScheduler.runSequentially(y, t1s1, t2s1);
        assertThat(c.get()).isEqualTo(1);
    }
    
    @Test
    void exceptionRethrown() {
        Yielder y = new Yielder();
        Stage t1s1 = new Stage("T1", "S1", () -> y.continueStage("T2S1"));
        Stage t2s1 = new Stage("T2", "S1", () -> {throw new RuntimeException("Wazzap!");});
        assertThatThrownBy(() -> ThreadScheduler.runSequentially(y, t1s1, t2s1))
                .isExactlyInstanceOf(CompletionException.class)
                .getCause()
                .isExactlyInstanceOf(RuntimeException.class)
                .hasMessage("Wazzap!");
    }
    
    @Test
    void indirectRecursion() throws Throwable {
        Yielder y = new Yielder();
        Queue<String> log = new ConcurrentLinkedQueue<>();
        
        Stage t1s1 = new Stage("T1", "S1", () -> {
            log.add(threadName() + "S1 yielding");
                y.continueStage("T2S1");
            log.add(threadName() + "S1 exiting");
        });
        
        Stage t2s1 = new Stage("T2", "S1", () -> {
            log.add(threadName() + "S1 yielding");
                y.continueStage("T1S2");
            log.add(threadName() + "S1 exiting");
        });
        
        Stage t1s2 = new Stage("T1", "S2", () ->
            log.add(threadName() + "S2 exiting"));
        
        ThreadScheduler.runSequentially(y, t1s1, t2s1, t1s2);
        assertThat(log).containsExactly(
                "T1S1 yielding", // <-- Thread 1
                    "T2S1 yielding",
                        "T1S2 exiting", // <-- Also Thread 1
                    "T2S1 exiting",
                "T1S1 exiting");
    }
    
    @Test
    void threadsPlayingPingPong() throws Throwable {
        Yielder y = new Yielder();
        Queue<String> s = new ConcurrentLinkedQueue<>();
        
        Stage ping = new Stage("T1", "Ping", () -> {
            s.add(threadName() + " Ping");
                y.continueStage("T2Pong");
            s.add(threadName() + " Ping");
                y.continueStage("T2Pong");
        });
        
        Stage pong = new Stage("T2", "Pong", () -> {
            s.add(threadName() + " Pong");
                y.continueStage("T1Ping");
            s.add(threadName() + " Pong");
                y.continueStage("T3Pang");
        });
        
        Stage pang = new Stage("T3", "Pang", () ->
            s.add(threadName() + " Pang!"));
        
        ThreadScheduler.runSequentially(y, ping, pong, pang);
        assertThat(s).containsExactly(
                "T1 Ping",
                "T2 Pong",
                "T1 Ping",
                "T2 Pong",
                "T3 Pang!");
    }
    
    @Test
    void allStagesMustExecuteFully() throws Throwable {
        Yielder y = new Yielder();
        
        Stage first = new Stage("T1", "First", () ->
            y.continueStage("T2Then"));
        
        Stage then = new Stage("T2", "Then", () -> {
            y.continueStage("T3JumpBack");
            assert false : "Execution never reaches here.";
        });
        
        Stage jumpBack = new Stage("T3", "JumpBack", () -> {
            y.continueStage("T1First"); // <-- because of this
            assert false : "Or here.";
        });
        
        // Can't "getSuppressed()" with AssertJ
        try {
            ThreadScheduler.runSequentially(y, first, then, jumpBack);
            fail("Expected error.");
        } catch (IllegalArgumentException e) {
            // T2 and T3 notices they never finished the stage, and so crash.
            // Not deterministic whose exception gets to suppress the other.
            assertThat(e).isExactlyInstanceOf(IllegalArgumentException.class);
            assertThat(e).hasMessageStartingWith("Stage(s) started but never completed"); // T2Then or T3JumpBack
            Throwable[] suppr = e.getSuppressed();
            assertThat(suppr).hasSize(1);
            Throwable s = suppr[0];
            assertThat(s).isExactlyInstanceOf(IllegalArgumentException.class);
            assertThat(s).hasMessageStartingWith("Stage(s) started but never completed");
        }
    }
    
    private static String threadName() {
        return Thread.currentThread().getName();
    }
}