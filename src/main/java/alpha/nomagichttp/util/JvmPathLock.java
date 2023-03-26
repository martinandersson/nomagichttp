package alpha.nomagichttp.util;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

import static java.lang.String.format;
import static java.util.Collections.unmodifiableMap;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

/**
 * A {@code Path}-unique lock that supports concurrent reading or exclusive
 * writing.<p>
 * 
 * Any number of read locks may be held by the same or different threads, but
 * only if no write locks have been acquired. Only one thread at a time can hold
 * one or many write locks.<p>
 * 
 * The lock is mapped to a {@link Path}, which is likely to be a file, but could
 * as well be a directory (this class does not enforce any kind of hierarchy
 * rules).<p>
 * 
 * The path is only used for association with a lock; this class does not
 * perform any I/O operations whilst acquiring or unlocking.<p>
 * 
 * Locks acquired by this class has no effect outside the currently running
 * JVM.<p>
 * 
 * The {@code close} method is what other lock APIs may have called
 * <i>unlock</i>. The {@code close} method must be called by the end of the
 * user's presumable I/O operation. For example, one could first open a
 * {@link FileChannel}, then acquire the relevant lock from this class; both in
 * a try-with-resources statement. Failure to close/unlock the lock will forever
 * impair the capability of other threads to acquire locks (and possibly create
 * a memory leak). This class does not implement any kind of eviction policy.<p>
 * 
 * This class uses one {@link ReentrantReadWriteLock} per path. Thus, this class
 * supports the same features as the {@code ReentrantReadWriteLock}, perhaps
 * most notably; reentrancy and lock downgrading.<p>
 * 
 * This class is thread-safe.
 * 
 * @apiNote
 * Java's {@link FileLock} is only suitable to orchestrate file access across
 * "concurrently-running programs". JavaDoc of {@link FileChannel} further
 * states: "[FileLocks] are not suitable for controlling access to a file by
 * multiple threads within the same virtual machine".<p>
 * 
 * Indeed, one may be very surprised to find that attempting to get a shared
 * file lock within the same JVM will actually throw an
 * {@link OverlappingFileLockException}. The quote unquote "shared" lock is not
 * shared within the same JVM! Shame on the developer who attempts to share
 * file access within his own program lol (well, they must have a reason).<p>
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class JvmPathLock implements AutoCloseable
{
    private static final Map<Path, ReentrantReadWriteLock>
            RWL = new ConcurrentHashMap<>();
    
    /**
     * Acquires a read-lock.<p>
     * 
     * The given path is allowed to be associated with any number of reader
     * threads, or just one; a writer thread acquiring a read-lock. If another
     * thread is holding a write-lock when this method is called, then the call
     * will block until timeout.
     * 
     * @param path     to associate the lock with
     * @param timeout  the maximum time to wait for the lock
     * @param unit     the time unit of the time argument
     * 
     * @return the lock
     * 
     * @throws NullPointerException
     *             if any argument is {@code null}
     * @throws InterruptedException
     *             if the current thread is interrupted while acquiring the lock
     * @throws TimeoutException
     *             if the lock is not acquired within the permissible time frame
     * 
     * @see JvmPathLock
     */
    public static JvmPathLock readLock(
            Path path, long timeout, TimeUnit unit)
            throws InterruptedException, TimeoutException {
        return acquire(path, timeout, unit, ReentrantReadWriteLock::readLock);
    }
    
    /**
     * Acquires a write-lock.<p>
     * 
     * The given path is allowed to be exclusively associated with only one
     * writer thread and only if no read locks are held. If a read-lock is held
     * by any thread, then the operation will block
     * until timeout.
     * 
     * @param path     to associate the lock with
     * @param timeout  the maximum time to wait for the lock
     * @param unit     the time unit of the time argument
     * 
     * @return the lock
     * 
     * @throws NullPointerException
     *             if any argument is {@code null}
     * @throws IllegalLockUpgradeException
     *             if the calling thread holds a read-lock
     * @throws InterruptedException
     *             if the current thread is interrupted while acquiring the lock
     * @throws TimeoutException
     *             if the lock is not acquired within the permissible time frame
     * 
     * @see JvmPathLock
     */
    public static JvmPathLock writeLock(
            Path path, long timeout, TimeUnit unit)
            throws InterruptedException, TimeoutException {
        return acquire(path, timeout, unit, ReentrantReadWriteLock::writeLock);
    }
    
    private static JvmPathLock acquire(
            Path path, long timeout, TimeUnit unit,
            Function<ReentrantReadWriteLock, Lock> readOrWrite)
            throws InterruptedException, TimeoutException
    {
        var key = path.toAbsolutePath();
        requireNonNull(unit);
        var outer = RWL.computeIfAbsent(key, k -> new ReentrantReadWriteLock(true));
        var inner = readOrWrite.apply(outer);
        if (tryLock(inner, timeout, unit, key, outer)) {
            // The mapping could have been removed (by concurrent thread)
            RWL.putIfAbsent(key, outer);
        } else {
            tryRemove(key, outer);
            boolean w = inner instanceof WriteLock;
            if (w && outer.getReadHoldCount() > 0) {
                throw new IllegalLockUpgradeException();
            }
            throw new TimeoutException(format(
                "Wanted a %s lock for path: %s", w ? "write" : "read", path));
        }
        return new JvmPathLock(key, outer, inner);
    }
    
    private static boolean tryLock(
            Lock inner,
            long timeout, TimeUnit unit,
            Path key, ReentrantReadWriteLock outer)
            throws InterruptedException {
        try {
            return inner.tryLock(timeout, unit);
        } catch (Throwable rethrow) {
            // Expecting only InterruptedExc
            tryRemove(key, outer);
            throw rethrow;
        }
    }
    
    private static void tryRemove(Path key, ReentrantReadWriteLock outer) {
        assert key.isAbsolute() : "Wrong reference, lol";
        // This does not care about the fairness setting (waiting threads)
        if (outer.writeLock().tryLock()) {
            try {
                RWL.remove(key);
            } finally {
                outer.writeLock().unlock();
            }
        }
    }
    
    private final Path key;
    private final ReentrantReadWriteLock outer;
    private final Lock inner;
    
    private JvmPathLock(Path key, ReentrantReadWriteLock outer, Lock inner) {
        this.key = key;
        this.outer = outer;
        this.inner = inner;
    }
    
    /**
     * Attempts to release this lock.
     * 
     * @throws IllegalMonitorStateException
     *             if the current thread does not hold this lock
     * 
     * @see Lock#unlock()
     */
    @Override
    public void close() {
        inner.unlock();
        tryRemove(key, outer);
    }
    
    /**
     * Returns the static cache (unmodifiable).<p>
     * 
     * For tests only!
     * 
     * @return see JavaDoc
     */
    static Map<Path, ReentrantReadWriteLock> map() {
        return unmodifiableMap(RWL);
    }
}