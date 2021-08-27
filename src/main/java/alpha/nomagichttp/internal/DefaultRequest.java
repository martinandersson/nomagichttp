package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpConstants.Version;
import alpha.nomagichttp.action.ActionRegistry;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.util.Attributes;

import java.net.http.HttpHeaders;

/**
 * The default implementation of {@code Request}.<p>
 * 
 * This class is constructed anew for each invocation target with invocation
 * target specific path parameters (see {@link ActionRegistry}).
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class DefaultRequest implements Request
{
    private final Version version;
    private final SkeletonRequest shared;
    private final RequestTarget rt;
    
    /**
     * Constructs this object.
     * 
     * @param version of HTTP (as established by HTTP exchange)
     * @param shared request components
     * @param resourceSegments resource segments
     */
    DefaultRequest(
            Version version,
            SkeletonRequest shared,
            Iterable<String> resourceSegments)
    {
        assert version != null;
        assert shared != null;
        assert resourceSegments != null;
        this.version = version;
        this.shared = shared;
        this.rt = new RequestTarget(shared.target(), resourceSegments);
    }
    
    @Override
    public String method() {
        return shared.head().method();
    }
    
    @Override
    public RequestTarget target() {
        return rt;
    }
    
    @Override
    public Version httpVersion() {
        return version;
    }
    
    @Override
    public HttpHeaders headers() {
        return shared.head().headers();
    }
    
    @Override
    public Body body() {
        return shared.body();
    }
    
    @Override
    public Attributes attributes() {
        return shared.attributes();
    }
    
    @Override
    public String toString() {
        return DefaultRequest.class.getSimpleName() +
                "{head=" + shared.head() + ", body=?}";
    }
}