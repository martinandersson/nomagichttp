package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpConstants.Version;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.RequestHead;
import alpha.nomagichttp.util.Attributes;

import java.net.http.HttpHeaders;

import static java.util.Objects.requireNonNull;

/**
 * The default implementation of {@code Request}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class DefaultRequest implements Request
{
    private final Version ver;
    private final RequestHead head;
    private final Body body;
    private final Parameters params;
    private final Attributes attr;
    
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
        this(version,
             shared.head(),
             shared.body(),
             new DefaultParameters(pathParams, shared.target()),
             shared.attributes());
    }
    
    private DefaultRequest(
            Version ver,
            RequestHead head,
            Body body,
            Parameters params,
            Attributes attr)
    {
        this.ver    = requireNonNull(ver);
        this.head   = head;
        this.body   = body;
        this.params = params;
        this.attr   = attr;
    }
    
    @Override
    public String method() {
        return head.method();
    }
    
    @Override
    public String target() {
        return head.requestTarget();
    }
    
    @Override
    public Version httpVersion() {
        return ver;
    }
    
    @Override
    public Parameters parameters() {
        return params;
    }
    
    @Override
    public HttpHeaders headers() {
        return head.headers();
    }
    
    @Override
    public Body body() {
        return body;
    }
    
    @Override
    public Attributes attributes() {
        return attr;
    }
    
    /**
     * Construct a new instance sharing all fields of this instance, except for
     * the given new parameters.
     * 
     * @param params new parameters
     * @return new instance
     */
    DefaultRequest withParams(Parameters params) {
        return new DefaultRequest(ver, head, body, params, attr);
    }
    
    @Override
    public String toString() {
        return DefaultRequest.class.getSimpleName() +
                "{head=" + head + ", body=?}";
    }
}