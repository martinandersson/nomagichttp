package alpha.nomagichttp.message;

import alpha.nomagichttp.util.Strings;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.RandomAccess;
import java.util.stream.Stream;

import static alpha.nomagichttp.HttpConstants.HeaderKey.CONTENT_LENGTH;
import static alpha.nomagichttp.HttpConstants.HeaderKey.CONTENT_TYPE;
import static alpha.nomagichttp.HttpConstants.HeaderKey.TRANSFER_ENCODING;
import static alpha.nomagichttp.message.MediaType.parse;
import static java.lang.Long.parseLong;
import static java.text.MessageFormat.format;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.empty;
import static java.util.function.Predicate.not;

/**
 * Default implementation of {@link ContentHeaders}.<p>
 * 
 * Is a convenient base class with final implementations for
 * equals/hashCode/toString/allTokens/allTokensKeepQuotes. Also provides
 * accessors for non-public hop-by-hop headers.
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
        return cc != null ? cc : (this.cc = mkContentType());
    }
    
    private Optional<MediaType> mkContentType() {
        final var tkns = allTokens(CONTENT_TYPE).toArray(String[]::new);
        if (tkns.length == 0) {
            return empty();
        }
        if (tkns.length > 1) {
            throw new BadHeaderException(
                "Multiple " + CONTENT_TYPE + " values in request.");
        }
        try {
            return Optional.of(parse(tkns[0]));
        } catch (MediaTypeParseException e) {
            throw new BadHeaderException(
                "Failed to parse " + CONTENT_TYPE + " header.", e);
        }
    }
    
    private OptionalLong cl;
    
    @Override
    public final OptionalLong contentLength() {
        var cl = this.cl;
        return cl != null ? cl : (this.cl = mkContentLength());
    }
    
    private OptionalLong mkContentLength() {
        final var tkns = allTokens(CONTENT_LENGTH).toArray(String[]::new);
        if (tkns.length == 0) {
            return OptionalLong.empty();
        }
        if (tkns.length > 1) {
            throw new BadHeaderException(
                "Multiple " + CONTENT_LENGTH + " values in request.");
        }
        final long v;
        try {
            v = parseLong(tkns[0]);
        } catch (NumberFormatException e) {
            var msg = format(
                "Can not parse {0} (\"{1}\") into a long.",
                CONTENT_LENGTH, tkns[0]);
            throw new BadHeaderException(msg, e);
        }
        if (v < 0) {
            throw new BadHeaderException(format(
                "{0} is negative ({1})", CONTENT_LENGTH, v));
        }
        return OptionalLong.of(v);
    }
    
    @Override
    public Stream<String> allTokens(String name) {
        return allTokens0(name, false);
    }
    
    @Override
    public Stream<String> allTokensKeepQuotes(String name) {
        return allTokens0(name, true);
    }
    
    private Stream<String> allTokens0(String name, boolean keepQuotes) {
        // NPE not documented in JDK
        return jdk.allValues(requireNonNull(name))
                  .stream()
                  .<String>mapMulti((line, sink) -> {
                      if (keepQuotes) Strings.splitToSink(line, ',', '"', sink);
                      else Strings.splitToSink(line, ',', sink);})
                  .map(String::strip)
                  .filter(not(String::isEmpty));
    }
    
    private List<String> te;
    
    /**
     * Returns all Transfer-Encoding tokens.
     * 
     * @return all Transfer-Encoding tokens
     *         (list is unmodifiable, {@link RandomAccess}, never {@code null})
     */
    final List<String> transferEncoding() {
        var te = this.te;
        return te != null ? te :
                (this.te = allTokens(TRANSFER_ENCODING).toList());
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