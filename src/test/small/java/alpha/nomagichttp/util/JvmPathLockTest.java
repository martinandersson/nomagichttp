package alpha.nomagichttp.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

import static java.util.concurrent.ForkJoinPool.commonPool;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
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
         throws InterruptedException, TimeoutException {
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
         throws InterruptedException, TimeoutException {
         try (var r1 = readLock();
              var r2 = readLock()) {
              assertLocksHeld(2, 0);
         }
    }
    
    @Test
    void manyReadLocks_twoThreads()
         throws InterruptedException, TimeoutException {
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
         throws InterruptedException, TimeoutException {
         try (var w1 = writeLock();
              var w2 = writeLock()) {
              assertLocksHeld(0, 2);
         }
    }
    
    @Test
    void manyWriteLocks_twoThreads_fails()
         throws InterruptedException, TimeoutException {
        acquireWriteLockThen(() -> writeLock(), "write");
    }
    
    @Test
    void writeLockBlocksRead()
         throws InterruptedException, TimeoutException {
         acquireWriteLockThen(() -> readLock(), "read");
    }
    
    private void acquireWriteLockThen(
            Throwing.Supplier<JvmPathLock, Exception> otherLock, String name)
         throws InterruptedException, TimeoutException {
         try (var w = writeLock()) {
              var other = commonPool().submit(() -> {
                  assertThatThrownBy(otherLock::get)
                      .isExactlyInstanceOf(TimeoutException.class)
                      .hasMessage("Wanted a "+ name +" lock for path: \\blabla")
                      .hasNoCause()
                      .hasNoSuppressedExceptions();
                  return null;
              });
              assertThat(other).succeedsWithin(1, SECONDS);
         }
    }
    
    @Test
    void lockDowngrading_okay()
         throws InterruptedException, TimeoutException {
         try (var w = writeLock();
              var r = readLock()) {
              assertLocksHeld(1, 1);
         }
    }
    
    @Test
    void lockUpgrading_crash()
         throws InterruptedException, TimeoutException {
         var r = readLock();
         assertThatThrownBy(() -> writeLock())
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
         throws InterruptedException, TimeoutException {
         // Normally upgrading wouldn't be okay, but these are different locks
         try (var r = readLock();
              var w = JvmPathLock.writeLock(Path.of("other"), 1, SECONDS)) {
              assertLocksHeld(1, 1);
         }
    }
    
    private static JvmPathLock readLock()
            throws InterruptedException, TimeoutException {
        return JvmPathLock.readLock(blabla, 0, SECONDS);
    }
    
    private static JvmPathLock writeLock()
            throws InterruptedException, TimeoutException {
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