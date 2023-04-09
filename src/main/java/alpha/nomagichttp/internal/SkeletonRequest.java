package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.message.Attributes;
import alpha.nomagichttp.message.RawRequest;

/**
 * A thin version of a request.<p>
 * 
 * This class contains almost all components needed for a complete
 * {@link DefaultRequest}. The one thing missing is path parameters.
 * 
 * @author Martin Andersson (webmaster@martinandersson.com)
 */
final class SkeletonRequest {
    private final RawRequest.Head head;
    private final HttpConstants.Version httpVersion;
    private final SkeletonRequestTarget target;
    private final RequestBody body;
    private final Attributes attributes;
    
    SkeletonRequest(
            RawRequest.Head head,
            HttpConstants.Version httpVersion,
            SkeletonRequestTarget target,
            RequestBody body) {
        this.head = head;
        this.httpVersion = httpVersion;
        this.target = target;
        this.body = body;
        this.attributes = new DefaultAttributes();
    }
    
    RawRequest.Head head() {
        return head;
    }
    HttpConstants.Version httpVersion() {
        return httpVersion;
    }
    SkeletonRequestTarget target() {
        return target;
    }
    RequestBody body() {
        return body;
    }
    Attributes attributes() {
        return attributes;
    }
}