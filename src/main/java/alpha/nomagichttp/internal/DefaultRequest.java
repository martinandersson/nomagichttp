package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpConstants.Version;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.util.Attributes;

import java.net.http.HttpHeaders;

/**
 * The default implementation of {@code Request}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class DefaultRequest implements Request
{
    private final Version version;
    private final SkeletonRequest shared;
    private final Parameters params;
    
    /**
     * Constructs this object.
     * 
     * @param version of HTTP (as established by HTTP exchange)
     * @param shared request components
     * @param pathParams provider of path parameters
     * 
     * @throws NullPointerException if any argument is {@code null}
     */
    DefaultRequest(
            Version version,
            SkeletonRequest shared,
            ResourceMatch<?> pathParams)
    {
        assert version != null;
        assert shared != null;
        this.version = version;
        this.shared = shared;
        this.params = new DefaultParameters(pathParams, shared.target());
    }
    
    @Override
    public String method() {
        return shared.head().method();
    }
    
    @Override
    public String target() {
        return shared.head().requestTarget();
    }
    
    @Override
    public Version httpVersion() {
        return version;
    }
    
    @Override
    public Parameters parameters() {
        return params;
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