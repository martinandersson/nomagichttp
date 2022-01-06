package alpha.nomagichttp.message;

import alpha.nomagichttp.event.RequestHeadReceived;

/**
 * A request head.<p>
 * 
 * This type is emitted as an attachment to the {@link RequestHeadReceived}
 * event. An API for accessing a parsed and <i>accepted</i> request head is
 * embedded in the API of {@link Request}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public record RequestHead(RawRequestLine line, Request.Headers headers)
        implements HeaderHolder {
    // Empty
}