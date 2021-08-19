package alpha.nomagichttp.events;

import alpha.nomagichttp.message.RequestHead;

/**
 * A request head has been received by the HTTP server.<p>
 * 
 * The event is emitted before validation and parsing of the request head has
 * begun. For example, it is possible that the request head is later rejected
 * for invalid token data or any other fault after the emission of the event.<p>
 * 
 * The intended purpose of this event is to gather metrics.<p>
 * 
 * The first attachment given to the listener is the {@link RequestHead}. The
 * second attachment is an instance of {@link Stats}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
    /**
     * A singleton instance representing the event.
     */
    INSTANCE;
    
    /**
     * A container of statistics concerning a request head received.<p>
     * 
     * {@link System#nanoTime()} returned by {@link #nanoTimeOnStart()} is
     * pulled when the first byte of the request head was received by the
     * server's processor, and {@link #nanoTimeOnStop()} returns the nano time
     * after the last byte finished processing, just before the emission of the
     * event. The elapsed time may or may not include time spent on the wire to
     * any degree. For example, if all of the head was consumed by an upstream
     * channel buffer before processing the bytes started, then the elapsed time
     * will not measure the wire transfer at all.
     */
    public static final class Stats extends AbstractByteCountedStats
    {
        /**
         * Constructs this object.
         * 
         * @param start {@link System#nanoTime()} on start
         * @param stop  {@link System#nanoTime()} on stop
         * @param bytes processed
         */
        public Stats(long start, long stop, long bytes) {
            super(start, stop, bytes);
        }
        
        @Override
        public String toString() {
            return RequestHeadParsed.class.getSimpleName() + '.' + Stats.class.getSimpleName() + "{" +
                    "start=" + nanoTimeOnStart() +
                    ", stop=" + nanoTimeOnStop() +
                    ", bytes=" + bytes() + '}';
        }
    }
}