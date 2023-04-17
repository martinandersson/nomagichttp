package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.message.Attributes;
import alpha.nomagichttp.message.BetterHeaders;
import alpha.nomagichttp.message.RawRequest;

import java.io.IOException;

import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.message.DefaultContentHeaders.empty;
import static alpha.nomagichttp.util.ScopedValues.httpServer;

/**
 * A thin version of a request.<p>
 * 
 * This class contains almost all components needed for a complete
 * {@link DefaultRequest}. The one thing missing is path parameters.
 * 
 * @author Martin Andersson (webmaster@martinandersson.com)
 */
final class SkeletonRequest
{
    private static final System.Logger
            LOG = System.getLogger(SkeletonRequest.class.getPackageName());
    
    private final RawRequest.Head head;
    private final HttpConstants.Version httpVersion;
    private final SkeletonRequestTarget target;
    private final RequestBody body;
    private final ChannelReader reader;
    private       BetterHeaders trailers;
    private final Attributes attributes;
    
    SkeletonRequest(
            RawRequest.Head head,
            HttpConstants.Version httpVersion,
            SkeletonRequestTarget target,
            RequestBody body,
            ChannelReader reader) {
        this.head = head;
        this.httpVersion = httpVersion;
        this.target = target;
        this.body = body;
        this.reader = reader;
        this.trailers = null;
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
    
    BetterHeaders trailers() throws IOException {
        var tr = trailers;
        return tr != null ? tr : (trailers = trailers0());
    }
    
    private BetterHeaders trailers0() throws IOException {
        // We could also require a chunk-encoded body,
        // but would not be compatible with HTTP/2?
        if (!body().isEmpty()) {
            throw new IllegalStateException("Consume the body first");
        }
        if (httpVersion().isLessThan(HTTP_1_1)) {
            return empty();
        }
        var maxLen = httpServer().getConfig().maxRequestTrailersSize();
        try {
            return ParserOf.trailers(reader, maxLen).parse();
        } finally {
            reader.limit(0);
        }
    }
    
    Attributes attributes() {
        return attributes;
    }
}