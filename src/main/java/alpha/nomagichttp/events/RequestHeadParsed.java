package alpha.nomagichttp.events;

/**
 * A request head has been received and parsed by the HTTP server.<p>
 * 
 * The event is emitted before validation and processing of the request head has
 * begun. For example, it is possible that the request head is later rejected
 * for invalid token data or any other fault after the emission of the event.<p>
 * 
 * The intended purpose is for gathering metrics.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public enum RequestHeadParsed {
    /**
     * A singleton instance representing the event.
     */
    INSTANCE;
}