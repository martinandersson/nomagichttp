package alpha.nomagichttp.events;

import java.time.Instant;

/**
 * Server started.<p>
 * 
 * The event is emitted as soon as the server's listening port has opened. It
 * has one {@link Instant} attachment which is when the server started.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public enum HttpServerStarted {
    /**
     * A singleton instance representing the event.
     */
    INSTANCE;
}