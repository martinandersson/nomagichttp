package alpha.nomagichttp.message;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static alpha.nomagichttp.HttpConstants.HeaderKey.CONTENT_LENGTH;
import static alpha.nomagichttp.HttpConstants.HeaderKey.CONTENT_TYPE;
import static alpha.nomagichttp.message.MediaType.parse;
import static java.lang.Long.parseLong;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.empty;

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
        return cc != null ? cc : (this.cc = mkContentType(delegate));
    }
    
    private static Optional<MediaType> mkContentType(HttpHeaders headers) {
        final List<String> values = headers.allValues(CONTENT_TYPE);
        if (values.isEmpty()) {
            return empty();
        }
        else if (values.size() == 1) {
            try {
                return Optional.of(parse(values.get(0)));
            } catch (MediaTypeParseException e) {
                throw new BadHeaderException("Failed to parse " + CONTENT_TYPE + " header.", e);
            }
        }
        throw new BadHeaderException("Multiple " + CONTENT_TYPE + " values in request.");
    }
    
    private OptionalLong cl;
    
    @Override
    public final OptionalLong contentLength() {
        var cl = this.cl;
        return cl != null ? cl : (this.cl = mkContentLength(delegate));
    }
    
    private static OptionalLong mkContentLength(HttpHeaders headers) {
        final List<String> values = headers.allValues(CONTENT_LENGTH);
        
        if (values.isEmpty()) {
            return OptionalLong.empty();
        }
        
        if (values.size() == 1) {
            final String v = values.get(0);
            try {
                return OptionalLong.of(parseLong(v));
            } catch (NumberFormatException e) {
                throw new BadHeaderException(
                        "Can not parse " + CONTENT_LENGTH + " (\"" + v + "\") into a long.", e);
            }
        }
        
        throw new BadHeaderException(
                "Multiple " + CONTENT_LENGTH + " values in request.");
    }
}