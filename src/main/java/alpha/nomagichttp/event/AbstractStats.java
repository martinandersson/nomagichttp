package alpha.nomagichttp.event;

import java.time.Duration;
import java.util.Arrays;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * A container of elapsed time of a task that was completed (successfully).<p>
 * 
 * This class contains no references to wall-clock time. The event is emitted
 * immediately after {@link #nanoTimeOnStop()}, and so, a wall-clock reference
 * to when the task started can be approximated:
 * <pre>
 *   var then = Instant.now().minusNanos({@link #elapsedNanos()});
 * </pre>
 * 
 * This class and all subclasses are thread-safe.<p>
 * 
 * This class and all subclasses implements {@code equals} and {@code hashCode}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public abstract class AbstractStats
{
    private final long start, stop;
    
    /**
     * Constructs this object.
     * 
     * @param start {@link System#nanoTime()} on start
     * @param stop {@link System#nanoTime()} on stop
     */
    public AbstractStats(long start, long stop) {
        this.start = start;
        this.stop  = stop;
    }
    
    /**
     * Returns the value from {@link System#nanoTime()} polled just before
     * the task begun.
     * 
     * @return the value from {@link System#nanoTime()} polled just before
     *         the task begun
     */
    public final long nanoTimeOnStart() {
        return start;
    }
    
    /**
     * Returns the value from {@link System#nanoTime()} polled just after
     * the task completed.
     * 
     * @return the value from {@link System#nanoTime()} polled just after
     * the task completed
     */
    public final long nanoTimeOnStop() {
        return stop;
    }
    
    /**
     * Returns nanos elapsed between {@link #nanoTimeOnStart()} and {@link
     * #nanoTimeOnStop()}.
     * 
     * @return nanos elapsed between {@link #nanoTimeOnStart()} and {@link
     *         #nanoTimeOnStop()}
     */
    public final long elapsedNanos() {
        return nanoTimeOnStop() - nanoTimeOnStart();
    }
    
    /**
     * Returns milliseconds elapsed between {@link #nanoTimeOnStart()} and
     * {@link #nanoTimeOnStop()}.
     * 
     * @return milliseconds elapsed between {@link #nanoTimeOnStart()} and
     *         {@link #nanoTimeOnStop()}
     */
    public final long elapsedMillis() {
        return NANOSECONDS.toMillis(elapsedNanos());
    }
    
    /**
     * Returns a {@code Duration} of {@link #elapsedNanos()}.
     * 
     * @return a {@code Duration} of {@link #elapsedNanos()}
     */
    public final Duration elapsedDuration() {
        return Duration.ofNanos(elapsedNanos());
    }
    
    private int hash;
    private boolean hashIsZero;
    
    @Override
    public int hashCode() {
        // Copy-paste from String.hashCode()
        int h = hash;
        if (h == 0 && !hashIsZero) {
            h = Arrays.hashCode(new long[]{start, stop});
            if (h == 0) {
                hashIsZero = true;
            } else {
                hash = h;
            }
        }
        return h;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null ||
            this.getClass() != obj.getClass()) {
            return false;
        }
        var other = (AbstractStats) obj;
        return this.start == other.start &&
               this.stop  == other.stop;
    }
}