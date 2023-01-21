package alpha.nomagichttp.event;

import java.time.Instant;

/**
 * Server stopped.<p>
 * 
 * The event is emitted exactly once when the server's listening port
 * closed.<p>
 * 
 * The event carries with it two attachments. The first is an {@link Instant}
 * when the server stopped, the second attachment is the {@link Instant} when
 * the server started.<p>
 * 
 * The event is signalled regardless if the close was successful and regardless
 * if there are lingering client connections still open.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public enum HttpServerStopped {
    /**
     * A singleton instance representing the event.
     */
    INSTANCE
}