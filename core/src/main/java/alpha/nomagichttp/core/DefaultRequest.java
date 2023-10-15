package alpha.nomagichttp.core;

import alpha.nomagichttp.HttpConstants.Version;
import alpha.nomagichttp.message.Attributes;
import alpha.nomagichttp.message.BetterHeaders;
import alpha.nomagichttp.message.Request;

import java.io.IOException;

import static alpha.nomagichttp.core.RequestTarget.requestTargetWithParams;
import static alpha.nomagichttp.core.RequestTarget.requestTargetWithoutParams;

/**
 * The default implementation of {@code Request}.<p>
 * 
 * A new instance of this class is created for each invoked entity as the path
 * parameters for said entity are unique; constructed and represented as a
 * {@link RequestTarget} available in this class, or may not even be available
 * as is the case for exception handlers.<p>
 * 
 * All other components of the request are shared throughout the HTTP exchange
 * as all created request objects will have a reference to the same underlying
 * {@link SkeletonRequest}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class DefaultRequest implements Request
{
    static Request requestWithParams(
            SkeletonRequest shared,
            Iterable<String> resourceSegments) {
        return new DefaultRequest(shared,
                requestTargetWithParams(shared.target(), resourceSegments));
    }
    
    static Request requestWithoutParams(
            SkeletonRequest shared) {
        return new DefaultRequest(shared,
                requestTargetWithoutParams(shared.target()));
    }
    
    private final SkeletonRequest shared;
    private final RequestTarget rt;
    
    private DefaultRequest(SkeletonRequest shared, RequestTarget rt) {
        this.shared = shared;
        this.rt = rt;
    }
    
    @Override
    public String method() {
        return shared.head().line().method();
    }
    
    @Override
    public RequestTarget target() {
        return rt;
    }
    
    @Override
    public Version httpVersion() {
        return shared.httpVersion();
    }
    
    @Override
    public Request.Headers headers() {
        return shared.head().headers();
    }
    
    @Override
    public Body body() {
        return shared.body();
    }
    
    @Override
    public BetterHeaders trailers() throws IOException {
        return shared.trailers();
    }
    
    @Override
    public Attributes attributes() {
        return shared.attributes();
    }
    
    @Override
    public String toString() {
        return "%s{head=%s, body.length=%s}".formatted(
                    DefaultRequest.class.getSimpleName(),
                    shared.head(),
                    body().length());
    }
}