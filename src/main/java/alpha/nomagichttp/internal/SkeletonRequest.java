package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.Attributes;
import alpha.nomagichttp.message.RequestHead;

/**
 * A thin version of a request.
 * 
 * @author Martin Andersson (webmaster@martinandersson.com)
 * @see DefaultRequest
 */
record SkeletonRequest(
        RequestHead head,
        SkeletonRequestTarget target,
        RequestBody body,
        Attributes attributes)
{
    SkeletonRequest {
        assert head != null;
        assert target != null;
        assert body != null;
        assert attributes != null;
    }
}