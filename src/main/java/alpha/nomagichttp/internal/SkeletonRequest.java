package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.message.Attributes;
import alpha.nomagichttp.message.RawRequest;

/**
 * A thin version of a request.<p>
 * 
 * This class has a raw request head, but it isn't so raw, after all. It carries
 * almost all the head-related data that the {@link DefaultRequest}
 * implementation returns, except for a parsed {@link HttpConstants.Version}
 * which is available in this class.<p>
 * 
 * Therefore, this class is just a hair away from being a complete request. The
 * one thing missing is the API for path parameters, which is provided by the
 * default request implementation.
 * 
 * @author Martin Andersson (webmaster@martinandersson.com)
 */
record SkeletonRequest(
        RawRequest.Head head,
        HttpConstants.Version httpVersion, // (as established by HTTP exchange)
        SkeletonRequestTarget target,
        RequestBody body,
        Attributes attributes)
{
    SkeletonRequest {
        assert head != null;
        assert httpVersion != null;
        assert target != null;
        assert body != null;
        assert attributes != null;
    }
}