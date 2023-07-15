package alpha.nomagichttp.util;

import alpha.nomagichttp.message.Request;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

import static alpha.nomagichttp.util.Blah.toNanosOrMaxValue;
import static java.lang.String.format;
import static java.util.Collections.unmodifiableMap;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

/**
 * A lock that supports concurrent reading or exclusive writing of a particular
 * {@code Path}.<p>
 * 
 * Any number of read locks may be held by the same or different threads, but
 * only if no write locks are active. Only one thread at a time can hold one or
 * many write locks.<p>
 * 
 * The lock is semantically associated with a specified {@link Path}, which is
 * likely to be a file, but could as well be a directory (this class does not
 * enforce any kind of hierarchy rules). The path object is only used as a key
 * in an internal cache; this class does not perform any I/O operations whilst
 * acquiring or unlocking.<p>
 * 
 * Locks acquired by this class has no effect outside the currently running
 * JVM, nor are they specified for a subregion of a file. An application that
 * needs to co-ordinate file access with other system processes, or co-ordinate
 * subregion access, should likely be using a {@link FileLock} instead
 * (see note).<p>
 * 
 * Inter-process co-ordination is usually not needed, however. Processes should
 * be entitled to and assume ownership of files. For example, it would be weird
 * if App X writes to a settings' file used by App Y. Furthermore, even when
 * different processes does access the same file, co-ordination is many times
 * not desired. For example, log files are often read by a terminal, live, at
 * the same time they are being written to.<p>
 * 
 * With that said, the NoMagicHTTP library's
 * {@link Request.Body#toFile(Path, long, TimeUnit, Set, FileAttribute[]) Request.Body.toFile(Path, ...)}
 * and
 * {@link ByteBufferIterables#ofFile(Path, long, TimeUnit) ByteBufferIterables.ofFile(Path, ...)}
 * do use write and read locks from this class, respectively. Reason being that
 * most files that are received by an endpoint or sent out from an endpoint, is
 * expected to be sufficiently small, user-scoped files. For example, a
 * Pingwin-avatar (<i>not</i> torrents nor log files).<p>
 * 
 * The {@code close} method is what other lock APIs may have called
 * <i>unlock</i>. The {@code close} method must be called by the end of the
 * user's presumable I/O operation. For example, one could first acquire the
 * relevant lock from this class, and then open a {@link FileChannel}; both in a
 * try-with-resources statement. This order is recommended, as to not create
 * unnecessarily side effects by the {@code open} method in case the lock can
 * not be acquired.<p>
 * 
 * Failure to close/unlock the lock may forever impair the success to acquire
 * future locks for the associated path (and possibly creates a memory leak).
 * This class does not implement any kind of eviction policy.<p>
 * 
 * This class is implemented using one {@link ReentrantReadWriteLock} per path,
 * and so, this class supports the same features as the
 * {@code ReentrantReadWriteLock}. Perhaps most notably; reentrancy and lock
 * downgrading.<p>
 * 
 * But, this class is not a direct facade for the
 * {@code ReentrantReadWriteLock}. The {@code ReentrantReadWriteLock} works
 * internally through a simple counting mechanism; there are no different lock
 * objects. This makes sense because {@code ReentrantReadWriteLock} does not
 * impose structure; it has been designed as a concurrency primitive to be used
 * by many threads over a long period of time. This class, however, does impose
 * structure; a {@code JvmPathLock} instance is reserved to be used only by the
 * owning thread, and semantically tied to a specific lock acquisition.<p>
 * 
 * The following is an example of how a standard lock reference is often used
 * to unlock (i.e. to decrement respective thread's lock hold count):
 * <pre>
 *   static final ReadLock SHAREABLE_API = reentrantReadWriteLock.readLock();
 *   // ...
 *   // Somewhere else, the reference can be unlocked by thread 1 or 2;
 *   // doesn't matter as long as each has acquired a read-lock from the same
 *   // outer RRWL instance in the past
 *   SHAREABLE_API.unlock();
 *   // Why not go a couple of more times as long as we have acquired this many
 *   // locks in the past (it's just a counter!):
 *   SHAREABLE_API.unlock();
 *   SHAREABLE_API.unlock();
 *   SHAREABLE_API.unlock();
 * </pre>
 * 
 * Whereas the following reference can effectively only be unlocked <i>once</i>,
 * and <i>only</i> by the thread who made the acquisition:
 * <pre>
 *   var uniqueAndLocal = jvmPathLock.readLock(path, ...);
 *   // Only first call by the correct thread has an effect
 *   // (Best to use a try-with-resources statement!)
 *   uniqueAndLocal.close();
 *   // It doesn't matter if the same thread is holding multiple locks for the
 *   // same path. All this is NOP:
 *   uniqueAndLocal.close();
 *   uniqueAndLocal.close();
 *   uniqueAndLocal.close();
 * </pre>
 * 
 * If another thread where to call the {@code close} method on a lock it does
 * not own; the call will not succeed, nor will it inadvertently "unlock" a
 * <i>different</i> lock instance that may be held by the calling thread.<p>
 * 
 * A thread can close/unlock many held locks in any order.<p>
 * 
 * The methods to acquire a lock specifies two timeout arguments. Exceptionally,
 * the implementation is free to cap the timeout to {@link Long#MAX_VALUE}
 * nanoseconds (which is more than 292 years).<p>
 * 
 * This class is thread-safe.<p>
 * 
 * The implementation does not implement {@code hashCode()} and
 * {@code equals()}.
 * 
 * @apiNote
 * Java's {@link FileLock} is only suitable to co-ordinate file access across
 * "concurrently-running programs". JavaDoc of {@link FileChannel} further
 * states: "[FileLocks] are not suitable for controlling access to a file by
 * multiple threads within the same virtual machine".<p>
 * 
 * Indeed, one may be very surprised to find that attempting to get a shared
 * file lock within the same JVM will actually throw an
 * {@link OverlappingFileLockException}. The quote unquote "shared" lock is not
 * shared within the same JVM! Shame on the developer who attempts to share
 * file access within his own program lol (well, they must have a reason).
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
     * @param timeout  the time to wait for the lock
     * @param unit     the time unit of the timeout argument
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
        return acquire(ReentrantReadWriteLock::readLock, path, timeout, unit);
    }
    
    /**
     * Acquires a write-lock.<p>
     * 
     * The given path is allowed to be exclusively associated with only one
     * writer thread and only if no read locks are held. If a read-lock is held
     * by any other thread, then the operation will block until the locks are
     * released or timeout.
     * 
     * @param path     to associate the lock with
     * @param timeout  the time to wait for the lock
     * @param unit     the time unit of the timeout argument
     * 
     * @return the lock
     * 
     * @throws NullPointerException
     *             if any argument is {@code null}
     * @throws IllegalLockUpgradeException
     *             if the calling thread holds a read-lock for the same path
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
        return acquire(ReentrantReadWriteLock::writeLock, path, timeout, unit);
    }
    
    private static JvmPathLock acquire(
            Function<ReentrantReadWriteLock, Lock> impl,
            Path path, long timeout, TimeUnit unit)
            throws InterruptedException, TimeoutException
    {
        var start = Instant.now();
        var key = path.toAbsolutePath();
        requireNonNull(unit);
        var outer = RWL.computeIfAbsent(key, k -> new ReentrantReadWriteLock(true));
        var inner = impl.apply(outer);
        // Wish we could do this only on a false return from tryLock, but
        // tryLock's read-to-write would nonsensically block until timeout lol
        boolean isWrite = inner instanceof WriteLock;
        if (isWrite && outer.getReadHoldCount() > 0) {
            throw new IllegalLockUpgradeException();
        }
        if (tryLock(inner, timeout, unit, key, outer)) {
            var prev = RWL.putIfAbsent(key, outer);
            if (prev == outer || prev == null) {
                // Nothing happened (by concurrent thread), the expected case.
                // Or, mapping was removed, but is now reinstated (with lock held).
                return new JvmPathLock(key, outer, inner);
            } else {
                // Hmm, a different lock was installed.
                // Exceptional, but could happen. So we abandon it and start racing.
                return acquire(impl, path,
                           remaining(start, timeout, unit), NANOSECONDS);
            }
        } else {
            tryRemove(key, outer);
            throw new TimeoutException(format(
                "Wanted a %s lock for path: %s", isWrite ? "write" : "read", path));
        }
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
        // This does not care about the fairness setting (waiting threads)
        if (outer.writeLock().tryLock()) {
            try {
                RWL.remove(key, outer);
            } finally {
                outer.writeLock().unlock();
            }
        }
    }
    
    private static long remaining(Instant start, long timeout, TimeUnit unit) {
        var deadline = start.plus(timeout, unit.toChronoUnit());
        return toNanosOrMaxValue(Duration.between(start, deadline));
    }
    
    private final Path key;
    private final ReentrantReadWriteLock outer;
    private final Lock inner;
    private final Thread owner;
    
    private JvmPathLock(
            Path key,
            ReentrantReadWriteLock outer,
            Lock inner) {
        this.key = key;
        this.outer = outer;
        this.inner = inner;
        this.owner = Thread.currentThread();
    }
    
    private boolean closed;
    
    /**
     * Attempts to unlock this lock.<p>
     * 
     * Only the first invocation, by the owning thread, has an effect.
     * Subsequent invocations by the owning thread, are NOP.
     * 
     * @throws IllegalMonitorStateException
     *             if the calling thread is not the owner
     * 
     * @see JvmPathLock
     * @see Lock#unlock()
     */
    @Override
    public void close() {
        if (owner != Thread.currentThread()) {
            throw new IllegalMonitorStateException();
        }
        if (!closed) {
            closed = true;
            try {
                inner.unlock();
            } finally {
                tryRemove(key, outer);
            }
        }
    }
    
    /**
     * Returns the cache (unmodifiable).<p>
     * 
     * For tests only!
     * 
     * @return see JavaDoc
     */
    static Map<Path, ReentrantReadWriteLock> map() {
        return unmodifiableMap(RWL);
    }
}