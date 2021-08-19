package alpha.nomagichttp.events;

import alpha.nomagichttp.message.Response;

/**
 * A response has been successfully sent.<p>
 * 
 * The intended purpose of this event is to gather metrics.<p>
 * 
 * The first attachment given to the listener is the {@link Response} sent. The
 * second attachment is an instance of {@link Stats}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public enum ResponseSent {
    /**
     * A singleton instance representing the event.
     */
    INSTANCE;
    
    /**
     * A container of statistics concerning a response sent.<p>
     * 
     * {@link System#nanoTime()} is first pulled just before the first channel
     * operation is initialized and last pulled after the last channel operation
     * completes, just before the emission of the event. Time elapsed includes
     * not only time that the response spent on the wire, but also time spent
     * waiting for new body bytebuffers to be published.
     */
    public static final class Stats extends AbstractStats
    {
        private final long bytes;
        
        /**
         * Constructs this object.
         * 
         * @param start {@link System#nanoTime()} on start
         * @param stop {@link System#nanoTime()} on stop
         * @param bytes transferred
         */
        public Stats(long start, long stop, long bytes) {
            super(start, stop);
            this.bytes = bytes;
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
                h = Long.hashCode(bytes) + super.hashCode();
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
            var other = (Stats) obj;
            return this.bytes == other.bytes &&
                   this.nanoTimeOnStart() == other.nanoTimeOnStart() &&
                   this.nanoTimeOnStop()  == other.nanoTimeOnStop();
        }
        
        @Override
        public String toString() {
            return ResponseSent.class.getSimpleName() + '.' + Stats.class.getSimpleName() + "{" +
                    "start=" + nanoTimeOnStart() +
                    ", stop=" + nanoTimeOnStop() +
                    ", bytes=" + bytes + '}';
        }
    }
}