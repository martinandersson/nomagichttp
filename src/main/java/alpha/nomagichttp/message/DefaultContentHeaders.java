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
 * Default implementation of {@link ContentHeaders}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class DefaultContentHeaders implements ContentHeaders {
    private final HttpHeaders jdk;
    
    /**
     * Constructs this object.
     * 
     * @param delegate backing values
     * @throws NullPointerException if {@code delegate} is {@code null}
     */
    public DefaultContentHeaders(HttpHeaders delegate) {
        this.jdk = requireNonNull(delegate);
    }
    
    @Override
    public final HttpHeaders delegate() {
        return jdk;
    }
    
    private Optional<MediaType> cc;
    
    @Override
    public final Optional<MediaType> contentType() {
        var cc = this.cc;
        return cc != null ? cc : (this.cc = mkContentType(jdk));
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
        return cl != null ? cl : (this.cl = mkContentLength(jdk));
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
    
    @Override
    public final boolean equals(Object obj) {
        if (obj == null || this.getClass() != obj.getClass()) {
            return false;
        }
        return this.delegate().equals(((ContentHeaders) obj).delegate());
    }
    
    @Override
    public final int hashCode() {
        return this.delegate().hashCode();
    }
    
    @Override
    public final String toString() {
        return this.delegate().toString();
    }
}