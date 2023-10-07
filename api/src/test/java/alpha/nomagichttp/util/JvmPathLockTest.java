package alpha.nomagichttp.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

import static alpha.nomagichttp.testutil.Interrupt.interruptAfter;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static java.util.concurrent.ForkJoinPool.commonPool;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Small tests for {@link JvmPathLock}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class JvmPathLockTest
{
    static final Path blabla = Path.of("/blabla");
    
    @AfterEach
    void assertMapIsEmpty() {
        assertThat(JvmPathLock.map()).isEmpty();
    }
    
    @Test
    void happyPath()
         throws InterruptedException, FileLockTimeoutException {
         var r = readLock();
         assertLocksHeld(1, 0);
         r.close();
         assertLocksHeld(0, 0);
         var w = writeLock();
         assertLocksHeld(0, 1);
         w.close();
         assertLocksHeld(0, 0);
    }
    
    @Test
    void manyReadLocks_oneThread()
         throws InterruptedException, FileLockTimeoutException {
         try (var r1 = readLock();
              var r2 = readLock()) {
              assertLocksHeld(2, 0);
         }
    }
    
    @Test
    void manyReadLocks_twoThreads()
         throws InterruptedException, FileLockTimeoutException {
         try (var r1 = readLock()) {
              var other = commonPool().submit(() -> {
                  try (var r2 = readLock()) {
                      assertLocksHeld(1, 0);
                  }
                  return null;
              });
              assertLocksHeld(1, 0);
              assertThat(other).succeedsWithin(1, SECONDS);
         }
    }
    
    @Test
    void manyWriteLocks_oneThread_okay()
         throws InterruptedException, FileLockTimeoutException {
         try (var w1 = writeLock();
              var w2 = writeLock()) {
              assertLocksHeld(0, 2);
         }
    }
    
    @Test
    void manyWriteLocks_twoThreads_fails()
         throws InterruptedException, FileLockTimeoutException {
        acquireWriteLockThen(() -> writeLock(), "write");
    }
    
    @Test
    void writeLockBlocksRead()
         throws InterruptedException, FileLockTimeoutException {
         acquireWriteLockThen(() -> readLock(), "read");
    }
    
    private void acquireWriteLockThen(
            Throwing.Supplier<JvmPathLock, Exception> otherLock, String name)
         throws InterruptedException, FileLockTimeoutException {
         try (var w = writeLock()) {
              var other = commonPool().submit(() -> {
                  assertThatThrownBy(otherLock::get)
                      .isExactlyInstanceOf(FileLockTimeoutException.class)
                      .hasMessage("Wanted a "+ name +" lock for path: " + blabla)
                      .hasNoCause()
                      .hasNoSuppressedExceptions();
                  return null;
              });
              assertThat(other).succeedsWithin(1, SECONDS);
         }
    }
    
    @Test
    void lockDowngrading_okay()
         throws InterruptedException, FileLockTimeoutException {
         try (var w = writeLock();
              var r = readLock()) {
              assertLocksHeld(1, 1);
         }
    }
    
    @ParameterizedTest
    @ValueSource(ints = {0, 9999})
    void lockUpgrading_crash(int timeout)
            throws InterruptedException, FileLockTimeoutException {
         var r = readLock();
         // Unlike the JDK (which would block until timeout), our API crashes,
         // and no matter the specified timeout; it happens at once
         assertThatThrownBy(() ->
                 interruptAfter(1, SECONDS, "writeLock", () ->
                     JvmPathLock.writeLock(blabla, timeout, SECONDS)))
             .isExactlyInstanceOf(IllegalLockUpgradeException.class)
             .hasMessage(null)
             .hasNoCause()
             .hasNoSuppressedExceptions();
         assertLocksHeld(1, 0);
         r.close();
    }
    
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void InterruptedException(boolean rw) {
        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> {
            if (rw) readLock(); else writeLock();
        }).isExactlyInstanceOf(InterruptedException.class)
          .hasMessage(null)
          .hasNoCause()
          .hasNoSuppressedExceptions();
        assertThat(Thread.interrupted())
          .isFalse();
    }
    
    @Test
    void differentPathsDifferentLocks()
         throws InterruptedException, FileLockTimeoutException {
         // Read-to-write upgrading not okay for the same path
         try (var r = readLock();
              var w = JvmPathLock.writeLock(Path.of("other"), 1, SECONDS)) {
              assertLocksHeld(1, 1);
         }
    }
    
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void IllegalMonitorException(boolean readOrWrite)
         throws ExecutionException, InterruptedException, TimeoutException {
         try (var t2 = newSingleThreadExecutor()) {
             var x = t2.submit(() -> readOrWrite ? readLock() : writeLock())
                       .get(1, SECONDS);
             // Test worker is not the thread holding the lock
             assertLocksHeld(0, 0);
             assertThatThrownBy(x::close)
                 .isExactlyInstanceOf(IllegalMonitorStateException.class)
                 .hasMessage(null)
                 .hasNoCause()
                 .hasNoSuppressedExceptions();
             // And this is just to not crash @AfterEach...
             t2.submit(x::close);
         }
    }
    
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closeTwice_nop(boolean readOrWrite)
            throws InterruptedException, FileLockTimeoutException {
        var x = readOrWrite ? readLock() : writeLock();
        x.close();
        assertLocksHeld(0, 0);
        assertThatCode(x::close)
            .doesNotThrowAnyException();
        assertLocksHeld(0, 0);
    }
    
    private static JvmPathLock readLock()
            throws InterruptedException, FileLockTimeoutException {
        return JvmPathLock.readLock(blabla, 0, SECONDS);
    }
    
    private static JvmPathLock writeLock()
            throws InterruptedException, FileLockTimeoutException {
        return JvmPathLock.writeLock(blabla, 0, SECONDS);
    }
    
    private static void assertLocksHeld(int read, int write) {
        assertThat(mapValues()
                .mapToInt(ReentrantReadWriteLock::getReadHoldCount)
                .sum()).isEqualTo(read);
        assertThat(mapValues()
                .mapToInt(ReentrantReadWriteLock::getWriteHoldCount)
                .sum()).isEqualTo(write);
    }
    
    private static Stream<ReentrantReadWriteLock> mapValues() {
        return JvmPathLock.map().values().stream();
    }
}