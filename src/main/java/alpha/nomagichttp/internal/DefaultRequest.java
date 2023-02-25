package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpConstants.Version;
import alpha.nomagichttp.message.Attributes;
import alpha.nomagichttp.message.BetterHeaders;
import alpha.nomagichttp.message.Request;

import java.io.IOException;

import static alpha.nomagichttp.HttpConstants.HeaderName.TRAILER;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.internal.RequestTarget.requestTargetWithParams;
import static alpha.nomagichttp.internal.RequestTarget.requestTargetWithoutParams;
import static alpha.nomagichttp.message.DefaultContentHeaders.empty;
import static alpha.nomagichttp.util.ScopedValues.httpServer;
import static java.lang.System.Logger.Level.WARNING;

/**
 * The default implementation of {@code Request}.<p>
 * 
 * A new instance of this class is created for each invoked entity as the path
 * parameters for said entity are unique; constructed and represented as a
 * {@link RequestTarget} available in this class, or may not even be available
 * as is the case for error handlers.<p>
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
            ChannelReader reader,
            SkeletonRequest shared,
            Iterable<String> resourceSegments) {
        return new DefaultRequest(reader, shared,
                requestTargetWithParams(shared.target(), resourceSegments));
    }
    
    static Request requestWithoutParams(
            ChannelReader reader,
            SkeletonRequest shared) {
        return new DefaultRequest(reader, shared,
                requestTargetWithoutParams(shared.target()));
    }
    
    private static final System.Logger
            LOG = System.getLogger(DefaultRequest.class.getPackageName());
    
    private final ChannelReader reader;
    private final SkeletonRequest shared;
    private final RequestTarget rt;
    
    /**
     * Constructs this object.
     * 
     * @param shared request components
     * param resourceSegments resource segments (must be effectively immutable)
     */
    private DefaultRequest(
            ChannelReader reader,
            SkeletonRequest shared,
            RequestTarget rt) {
        this.reader = reader;
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
        // We could also require a chunk-encoded body,
        // but would not be compatible with HTTP/2?
        if (!body().isEmpty()) {
            throw new IllegalStateException("Consume the body first");
        }
        if (httpVersion().isLessThan(HTTP_1_1)) {
            return empty();
        }
        if (LOG.isLoggable(WARNING) && !headers().contains(TRAILER)) {
            LOG.log(WARNING, """
                    No trailer header present, \
                    Request.trailers() may block until timeout""");
        }
        var maxLen = httpServer().getConfig().maxRequestTrailersSize();
        reader.reset();
        var tr = ParserOf.trailers(reader, maxLen).parse();
        reader.limit(0);
        return tr;
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