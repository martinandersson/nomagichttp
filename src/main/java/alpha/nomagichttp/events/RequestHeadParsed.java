package alpha.nomagichttp.events;

import alpha.nomagichttp.message.RequestHead;

/**
 * A request head has been received and parsed by the HTTP server.<p>
 * 
 * The event is emitted before validation and processing of the request head has
 * begun. For example, it is possible that the request head is later rejected
 * for invalid token data or any other fault after the emission of the event.<p>
 * 
 * The intended purpose is for gathering metrics.<p>
 * 
 * The first attachment given to the listener is the {@link RequestHead}. The
 * second attachment will be {@code null}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public enum RequestHeadParsed {
    /**
     * A singleton instance representing the event.
     */
    INSTANCE;
}