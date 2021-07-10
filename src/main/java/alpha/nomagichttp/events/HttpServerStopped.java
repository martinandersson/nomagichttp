package alpha.nomagichttp.events;

import alpha.nomagichttp.HttpServer;

import java.time.Instant;

/**
 * Server stopped.<p>
 * 
 * The event is emitted as soon as the server's listening port has stopped,
 * which is roughly just before the method {@link HttpServer#stop()}/
 * {@link HttpServer#stopNow()} returns.<p>
 * 
 * The event carries with it two attachments. The first is an {@link Instant}
 * when the server stopped, the second attachment is the {@link Instant} when
 * the server started.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public enum HttpServerStopped {
    /**
     * A singleton instance representing the event.
     */
    INSTANCE;
}