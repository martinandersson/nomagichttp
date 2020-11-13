package alpha.nomagichttp.message;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static alpha.nomagichttp.message.Strings.split;
import static java.lang.Long.parseLong;
import static java.util.Arrays.stream;

/**
 * Utility methods to parse String-lines of HTTP request headers into
 * higher-level Java types.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class Headers {
    private Headers() {
        // Empty
    }
    
    /**
     * Parses all "Accept" values from the specified headers.<p>
     * 
     * This header may be specified by clients that wish to retrieve a
     * particular resource representation.<p>
     * 
     * <i>All</i> accept-header keys are taken into account in order, splitting
     * the values by the comma character (",") - except for quoted values
     * (;param="quo,ted"), then feeding each token to {@link
     * MediaType#parse(CharSequence)}.
     * 
     * @param  headers source to parse from
     * @return parsed values (may be empty, but not {@code null})
     * 
     * @throws BadMediaTypeSyntaxException see {@link MediaType#parse(CharSequence)}}
     * 
     * @see <a href="https://tools.ietf.org/html/rfc7231#section-5.3.2">RFC 7231 §5.3.2</a>
     */
    public static MediaType[] accepts(HttpHeaders headers) {
        return headers.allValues("Accept").stream()
                .flatMap(v -> stream(split(v, ',', '"')))
                .map(MediaType::parse)
                .toArray(MediaType[]::new);
    }
    
    /**
     * Parses one "Content-Type" value into a media type.<p>
     * 
     * This header indicates the media type of the message body and should be
     * set by the sender if the message carries a body payload.<p>
     * 
     * TODO: Example.<p>
     * 
     * @param  headers source to parse from
     * @return parsed value (never {@code null})
     * 
     * @throws BadMediaTypeSyntaxException
     *           see {@link MediaType#parse(CharSequence)}}
     * 
     * @throws BadHeaderException
     *           if headers has multiple Content-Type keys
     * 
     * @see <a href="https://tools.ietf.org/html/rfc7231#section-3.1.1.5">RFC 7231 §3.1.1.5</a>
     */
    public static Optional<MediaType> contentType(HttpHeaders headers) {
        final List<String> values = headers.allValues("Content-Type");
        
        if (values.isEmpty()) {
            return Optional.empty();
        }
        else if (values.size() == 1) {
            return Optional.of(MediaType.parse(values.get(0)));
        }
        
        throw new BadHeaderException("Multiple Content-Type values in request.");
    }
    
    /**
     * Parses one "Content-Length" value into a long.<p>
     * 
     * This header is the message body length in bytes and should be set by the
     * sender if the message carries a body payload.<p>
     * 
     * TODO: Example.<p>
     * 
     * An empty optional is returned if the header is not present.<p>
     * 
     * The server may assume that there is no message body if the header is not
     * present or set to "0".<p>
     * 
     * @param  headers source to parse from
     * @return parsed value (never {@code null})
     * 
     * @throws BadHeaderException
     *             if header value can not be parsed, or
     *             the header has multiple Content-Length keys
     * 
     * @see <a href="https://tools.ietf.org/html/rfc7230#section-3.3.2">RFC 7230 §3.3.2</a>
     */
    public static OptionalLong contentLength(HttpHeaders headers) {
        final List<String> values = headers.allValues("Content-Length");
        
        if (values.isEmpty()) {
            return OptionalLong.empty();
        }
        
        if (values.size() == 1) {
            final String v = values.get(0);
            try {
                return OptionalLong.of(parseLong(v));
            } catch (NumberFormatException e) {
                throw new BadHeaderException(
                        "Can not parse Content-Length (\"" + v + "\") into a long.", e);
            }
        }
        
        throw new BadHeaderException(
                "Multiple Content-Length values in request.");
    }
}