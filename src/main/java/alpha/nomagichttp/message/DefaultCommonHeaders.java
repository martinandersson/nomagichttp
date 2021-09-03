package alpha.nomagichttp.message;

import alpha.nomagichttp.util.Headers;

import java.net.http.HttpHeaders;
import java.util.Optional;
import java.util.OptionalLong;

import static java.util.Objects.requireNonNull;

/**
 * Default implementation of {@link CommonHeaders}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class DefaultCommonHeaders implements CommonHeaders {
    private final HttpHeaders delegate;
    
    /**
     * Constructs this object.
     * 
     * @param delegate backing values
     * @throws NullPointerException if {@code delegate} is {@code null}
     */
    public DefaultCommonHeaders(HttpHeaders delegate) {
        this.delegate = requireNonNull(delegate);
    }
    
    @Override
    public final HttpHeaders delegate() {
        return delegate;
    }
    
    private Optional<MediaType> cc;
    
    @Override
    public final Optional<MediaType> contentType() {
        var cc = this.cc;
        return cc != null ? cc : (this.cc = Headers.contentType(delegate));
    }
    
    private OptionalLong cl;
    
    @Override
    public final OptionalLong contentLength() {
        var cl = this.cl;
        return cl != null ? cl : (this.cl = Headers.contentLength(delegate));
    }
}