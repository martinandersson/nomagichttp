package alpha.nomagichttp.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static alpha.nomagichttp.testutil.ThreadScheduler.Stage;
import static alpha.nomagichttp.testutil.ThreadScheduler.Yielder;
import static alpha.nomagichttp.testutil.ThreadScheduler.runAsync;
import static alpha.nomagichttp.testutil.ThreadScheduler.runAsyncUnchecked;
import static alpha.nomagichttp.testutil.ThreadScheduler.runInParallel;
import static alpha.nomagichttp.testutil.ThreadScheduler.runSequentially;
import static java.lang.Integer.parseInt;
import static java.lang.Thread.currentThread;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Small tests for {@link SeriallyRunnable}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class SeriallyRunnableTest
{
    SeriallyRunnable sr;
    
    int counter;
    
    void increment() {
        ++counter;
    }
    
    int incrementAndGet() {
        increment();
        return counter;
    }
    
    @Test
    void rerun_sync() {
        sr = new SeriallyRunnable(this::increment);
        assertThat(counter).isZero();
        
        sr.run();
        assertThat(counter).isOne();
        
        sr.run();
        assertThat(counter).isEqualTo(2);
    }
    
    @Test
    void loop_sync() {
        sr = new SeriallyRunnable(() -> {
            if (counter == 2) {
                return;
            }
            int noChangePlz = incrementAndGet();
            sr.run();
            assertThat(counter).isEqualTo(noChangePlz);
        });
        
        sr.run();
        assertThat(counter).isEqualTo(2);
    }
    
    // loop_async()? Alias: sameThreadRecursivelyCompletes_withRerun()
    
    @Test
    void rerun_async() {
        sr = new SeriallyRunnable(this::increment, true);
        assertThat(counter).isZero();
        
        sr.run();
        assertThat(counter).isOne();
        
        // No reentrancy, need complete signal first
        sr.run();
        assertThat(counter).isOne();
        
        // This causes a repeat run
        sr.complete();
        assertThat(counter).isEqualTo(2);
    }
    
    @Test
    void noCompleteInSyncMode() {
        sr = new SeriallyRunnable(() -> {});
        assertThatThrownBy(sr::complete)
                .isExactlyInstanceOf(UnsupportedOperationException.class)
                .hasMessage("In synchronous mode.");
    }
    
    @Test
    void completeNeverStarted() {
        sr = new SeriallyRunnable(() -> {}, true);
        assertThatThrownBy(sr::complete)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("The run completed already (exceptionally) or complete() was called more than once.");
    }
    
    @Test
    void recursivelyCompleteTwice() {
        sr = new SeriallyRunnable(() -> {
            sr.complete();
            sr.complete();
        }, true);
        
        assertThatThrownBy(sr::run)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("complete() called more than once.");
    }
    
    @Test
    void otherThreadSignalRerunThenComplete() throws InterruptedException, TimeoutException {
        sr = new SeriallyRunnable(this::increment, true);
        
        // Enter+exit SR in another thread, but don't complete
        runAsync(sr);
        assertThat(counter).isOne();
        
        // Signal re-run by main thread
        sr.run();
        
        // Did not increment the counter
        assertThat(counter).isOne();
        
        // This executes the counter a second time
        sr.complete();
        
        assertThat(counter).isEqualTo(2);
    }
    
    @Test
    void sameThreadSignalRecursiveRerunOtherThreadComplete() throws InterruptedException, TimeoutException {
        sr = new SeriallyRunnable(() -> {
            increment();
            if (currentThread().getName().equals("T1")) {
                sr.run();
            }
        }, true);
        
        // Enter SR and immediately signal re-run, then exit
        runAsync(sr);
        assertThat(counter).isOne();
        
        // T2 completes
        sr.complete();
        assertThat(counter).isEqualTo(2);
    }
    
    @Test
    void otherThreadSignalConcurrentRerunAndComplete() throws InterruptedException, TimeoutException {
        Yielder y = new Yielder();
        
        sr = new SeriallyRunnable(() -> {
            increment();
            if (currentThread().getName().equals("T1")) {
                y.continueStage("T2S1");
            }
        }, true);
        
        Stage t1s1 = new Stage("T1", "S1", () -> {
            sr.run();
            // While T1 was entered, T2 signalled re-run but did not enter
            assertThat(counter).isOne();
            // Now let T2 complete
            y.continueStage("T2S1");
        });
        
        Stage t2s1 = new Stage("T2", "S1", () -> {
            sr.run();
            assertThat(counter).isOne();
            
            y.continueStage("T1S1");
            
            sr.complete();
            assertThat(counter).isEqualTo(2);
        });
        
        runSequentially(y, t1s1, t2s1);
    }
    
    @Test
    void exception_canGoAgain() {
        sr = new SeriallyRunnable(() -> {
            if (counter == 0) {
                throw new RuntimeException("Wazzap!");
            } else {
                ++counter;
            }
        });
        
        assertThatThrownBy(sr::run)
                .isExactlyInstanceOf(RuntimeException.class)
                .hasMessage("Wazzap!");
        
        // And can go again
        ++counter;
        sr.run();
        assertThat(counter).isEqualTo(2);
    }
    
    @Test
    void exception_scheduledRunVoided() {
        sr = new SeriallyRunnable(() -> {
            increment();
            sr.run();
            assertThat(counter).isOne();
            throw new RuntimeException("Wazzap!");
        });
        
        assertThatThrownBy(sr::run)
                .isExactlyInstanceOf(RuntimeException.class)
                .hasMessage("Wazzap!");
        
        assertThat(counter).isOne();
    }
    
    @Test
    void exception_implicitComplete() {
        sr = new SeriallyRunnable(() -> {
            increment();
            assertThat(counter).isOne();
            throw new RuntimeException("Wazzap!");
        }, true);
        
        assertThatThrownBy(sr::run)
                .isExactlyInstanceOf(RuntimeException.class)
                .hasMessage("Wazzap!");
        
        assertThat(counter).isOne();
        
        assertThatThrownBy(sr::complete)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("The run completed already (exceptionally) or complete() was called more than once.");
        
        assertThat(counter).isOne();
    }
    
    @Test
    void sameThreadRecursivelyCompletes_noRerun() {
        sr = new SeriallyRunnable(() -> {
            increment();
            sr.complete();
        }, true);
        
        sr.run();
        assertThat(counter).isOne();
        
        // Can go again cos we immediately completed
        sr.run();
        assertThat(counter).isEqualTo(2);
    }
    
    @Test
    void sameThreadRecursivelyCompletes_withRerun() {
        sr = new SeriallyRunnable(() -> {
            if (counter == 2) {
                return;
            }
            increment();
            sr.run();
            sr.complete();
        }, true);
        
        sr.run();
        assertThat(counter).isEqualTo(2);
    }
    
    @Test
    void initiatingThreadLastToFinish_noRerun() {
        sr = new SeriallyRunnable(() -> {
            int noChangePlz = incrementAndGet();
            runAsyncUnchecked(sr::complete);
            assertThat(counter).isEqualTo(noChangePlz);
        }, true);
        
        sr.run();
        assertThat(counter).isOne();
        
        // Can go again
        sr.run();
        assertThat(counter).isEqualTo(2);
    }
    
    @Test
    void initiatingThreadLastToFinish_withRerun_beforeComplete() {
        sr = new SeriallyRunnable(() -> {
            if (counter == 2) {
                return;
            }
            int noChangePlz = incrementAndGet();
            sr.run(); // <-- signal rerun
            assertThat(counter).isEqualTo(noChangePlz);
            runAsyncUnchecked(sr::complete); // No effect, initiating thread has not returned
            assertThat(counter).isEqualTo(noChangePlz);
        }, true);
        
        // Initiator does block, and increments twice
        sr.run();
        assertThat(counter).isEqualTo(2);
    }
    
    @Test
    void initiatingThreadLastToFinish_withRerun_afterComplete() {
        sr = new SeriallyRunnable(() -> {
            if (counter == 2) {
                return;
            }
            int noChangePlz = incrementAndGet();
            runAsyncUnchecked(sr::complete);
            assertThat(counter).isEqualTo(noChangePlz);
            sr.run();
            assertThat(counter).isEqualTo(noChangePlz);
        }, true);
        
        sr.run();
        assertThat(counter).isEqualTo(2);
    }
    
    @Test
    void complete_noRerun() {
        sr = new SeriallyRunnable(this::increment, true);
        sr.run();
        sr.complete();
        assertThat(counter).isOne();
    }
    
    @Test
    void twoRerunSignalsOneRepeat() {
        sr = new SeriallyRunnable(() -> {
            increment();
            if (counter == 1) {
                sr.run(); // <-- schedules extra run
                assertThat(counter).isOne();
                sr.run(); // <-- ignored
                assertThat(counter).isOne();
            }
        });
        sr.run();
        assertThat(counter).isEqualTo(2);
    }
    
    // Branches such as "state.compareAndSet(END, BEGIN)" can not be hit
    // deterministically. That's what these parallel tests are for.
    
    @Test
    void parallel_syncMode_memoryVisibility() throws InterruptedException, TimeoutException {
        final int nThreads = 20, nRepetitions = 1_000;
        int[] thrLocal = new int[nThreads];
        sr = new SeriallyRunnable(() -> {
            increment(); // <-- bump global non-volatile var
            int thrId = parseInt(currentThread().getName().substring(1));
            int v = thrLocal[thrId - 1];
            thrLocal[thrId - 1] = ++v; // <-- also keep count in a ThreadLocal
        });
        runInParallel(nThreads, nRepetitions, () -> {
            sr.run();
            // Small pause outside as to not hog the runner with one looping thread only
            arbitraryWork();
        });
        assertThat(counter).isEqualTo(IntStream.of(thrLocal).sum());
        long uniq = IntStream.of(thrLocal).filter(v -> v > 0).count();
        assumeTrue(uniq >= 2, "Two or more actual threads required."); // Weird OS, too small pause.. aliens?
    }
    
    @Test
    void parallel_asyncMode_noOverlap() throws InterruptedException, TimeoutException {
        final int nThreads = 20, nRepetitions = 1_000;
        
        AtomicReference<Thread> occupiedBy = new AtomicReference<>();
        ThreadLocal<Boolean> doComplete = ThreadLocal.withInitial(() -> false);
        
        sr = new SeriallyRunnable(() -> {
            occupiedBy.updateAndGet(t -> {
                if (t != null && t != currentThread()) {
                    fail("Overlap");
                }
                doComplete.set(true);
                return currentThread();
            });
            currentThread().interrupt();
        }, true);
        
        runInParallel(nThreads, nRepetitions, () -> {
            sr.run();
            arbitraryWork();
            boolean c = doComplete.get();
            doComplete.set(false);
            if (c) {
                occupiedBy.set(null);
                sr.complete();
            }
        });
    }
    
    private static void arbitraryWork() {
        IntStream.range(0, 5).forEach(ignored -> Thread.onSpinWait());
    }
}