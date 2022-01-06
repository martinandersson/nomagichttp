package alpha.nomagichttp.event;

import alpha.nomagichttp.message.HttpVersionTooNewException;
import alpha.nomagichttp.message.Response;

/**
 * A response has been successfully sent.<p>
 * 
 * The intended purpose of this event is to gather metrics. One metric in
 * particular that ought to be of interest should be how many 505 (HTTP Version
 * Not Supported) responses are sent (see {@link HttpVersionTooNewException}).
 * Too many of these and the application developer ought to file a NoMagicHTTP
 * GitHub issue.<p>
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
     * {@link System#nanoTime()} returned by {@link #nanoTimeOnStart()} is
     * pulled just before the first channel operation is initialized and
     * {@link #nanoTimeOnStop()} returns the nano time after the last channel
     * operation completes, just before the emission of the event. Time elapsed
     * between the two includes not only time that the response spent on the
     * wire, but also time spent waiting for new body bytebuffers to be
     * published.
     */
    public static final class Stats extends AbstractByteCountedStats
    {
        /**
         * Constructs this object.
         * 
         * @param start {@link System#nanoTime()} on start
         * @param stop {@link System#nanoTime()} on stop
         * @param bytes transferred
         */
        public Stats(long start, long stop, long bytes) {
            super(start, stop, bytes);
        }
        
        @Override
        public String toString() {
            return ResponseSent.class.getSimpleName() + '.' + Stats.class.getSimpleName() + "{" +
                    "start=" + nanoTimeOnStart() +
                    ", stop=" + nanoTimeOnStop() +
                    ", byteCount=" + byteCount() + '}';
        }
    }
}