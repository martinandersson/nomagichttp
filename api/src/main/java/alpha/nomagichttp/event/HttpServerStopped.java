package alpha.nomagichttp.event;

import java.time.Instant;

/**
 * Server stopped.<p>
 * 
 * The event is emitted exactly once after the server's listening port
 * closed.<p>
 * 
 * The event carries with it two attachments. The first is an {@link Instant}
 * when the server stopped, the second attachment is the {@link Instant} when
 * the server started.<p>
 * 
 * The event is signaled regardless if there are lingering client connections
 * still open.
 * 
 * @implNote
 * It has been observed that opening a connection to the server's listening
 * port, immediately <i>after</i> the same thread first closed the port,
 * actually succeeds; and so a new HTTP exchange begins. This is a clear
 * violation of {@code ServerSocketChannel.accept()}'s contract.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public enum HttpServerStopped {
    /**
     * A singleton instance representing the event.
     */
    INSTANCE
}