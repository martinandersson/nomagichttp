package alpha.nomagichttp.events;

import alpha.nomagichttp.message.Response;

import java.time.Duration;
import java.util.Objects;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * A response has been successfully sent.<p>
 * 
 * The intended purpose for this event is to gather metrics.<p>
 * 
 * The first attachment given to the listener is the {@link Response} sent. The
 * second attachment will be an instance of {@link Stats}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public enum ResponseSent {
    /**
     * A singleton instance representing the event.
     */
    INSTANCE;
    
    /**
     * A thread-safe container of statistics concerning a response sent.<p>
     * 
     * This class contains no references to wall-clock time. The {@link
     * ResponseSent} event is emitted immediately after {@link
     * #nanoTimeOnStop()}, and so, a wall-clock reference to when the response
     * begun transmission can be approximated:
     * <pre>
     *   var then = Instant.now().minusNanos({@link #elapsedNanos()});
     * </pre>
     * 
     * This class implements {@code equals} and {@code hashCode}.
     */
    public static class Stats
    {
        private final long start, stop, bytes;
        
        /**
         * Constructs this object.
         * 
         * @param start {@link System#nanoTime()} on start
         * @param stop {@link System#nanoTime()} on stop
         * @param bytes transferred
         */
        public Stats(long start, long stop, long bytes) {
            this.start = start;
            this.stop  = stop;
            this.bytes = bytes;
        }
        
        /**
         * Returns the value from {@link System#nanoTime()} polled just before
         * the first channel operation was initialized.
         * 
         * @return the value from {@link System#nanoTime()} polled just before
         *         the first channel operation was initialized
         */
        public long nanoTimeOnStart() {
            return start;
        }
        
        /**
         * Returns the value from {@link System#nanoTime()} polled just after
         * the last channel operation completed.
         * 
         * @return the value from {@link System#nanoTime()} polled just after
         * the last channel operation completed
         */
        public long nanoTimeOnStop() {
            return stop;
        }
        
        /**
         * Returns nanos elapsed between {@link #nanoTimeOnStart()} and {@link
         * #nanoTimeOnStop()}.<p>
         * 
         * This is the time it took for the response to be sent out and includes
         * not only time spent on the wire, but also time spent waiting for new
         * body bytebuffers to be published.
         * 
         * @return nanos elapsed between {@link #nanoTimeOnStart()} and {@link
         *         #nanoTimeOnStop()}
         */
        public long elapsedNanos() {
            return nanoTimeOnStop() - nanoTimeOnStart();
        }
        
        /**
         * Returns milliseconds elapsed between {@link #nanoTimeOnStart()} and
         * {@link #nanoTimeOnStop()}.<p>
         * 
         * This is the time it took for the response to be sent out and includes
         * not only time spent on the wire, but also time spent waiting for new
         * body bytebuffers to be published.
         * 
         * @return milliseconds elapsed between {@link #nanoTimeOnStart()} and
         *         {@link #nanoTimeOnStop()}
         */
        public long elapsedMillis() {
            return NANOSECONDS.toMillis(elapsedNanos());
        }
        
        /**
         * Returns a {@code Duration} of {@link #elapsedNanos()}.
         * 
         * @return a {@code Duration} of {@link #elapsedNanos()}
         */
        public Duration elapsedDuration() {
            return Duration.ofNanos(elapsedNanos());
        }
        
        /**
         * Returns the number of bytes sent.
         * 
         * @return the number of bytes sent
         */
        public long bytes() {
            return bytes;
        }
        
        private int hash;
        private boolean hashIsZero;
        
        @Override
        public int hashCode() {
            // Copy-paste from String.hashCode()
            int h = hash;
            if (h == 0 && !hashIsZero) {
                h = Objects.hash(start, stop, bytes);
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
            if (obj == null || this.getClass() != obj.getClass()) {
                return false;
            }
            var other = (Stats) obj;
            return this.start == other.start &&
                   this.stop  == other.stop  &&
                   this.bytes == other.bytes;
        }
        
        @Override
        public String toString() {
            return ResponseSent.class.getSimpleName() + '.' + Stats.class.getSimpleName() + "{" +
                    "start=" + start +
                    ", stop=" + stop +
                    ", bytes=" + bytes + '}';
        }
    }
}