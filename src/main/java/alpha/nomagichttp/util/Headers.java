package alpha.nomagichttp.util;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.message.BadHeaderException;
import alpha.nomagichttp.message.BadMediaTypeSyntaxException;
import alpha.nomagichttp.message.MediaType;

import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static alpha.nomagichttp.HttpConstants.HeaderKey.ACCEPT;
import static alpha.nomagichttp.HttpConstants.HeaderKey.CONTENT_LENGTH;
import static alpha.nomagichttp.HttpConstants.HeaderKey.CONTENT_TYPE;
import static alpha.nomagichttp.util.Strings.split;
import static java.lang.Long.parseLong;
import static java.util.Arrays.stream;
import static java.util.Collections.emptyMap;

/**
 * Utility methods for {@link HttpHeaders}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class Headers
{
    private static final HttpHeaders EMPTY
            = HttpHeaders.of(emptyMap(), (ign,ored) -> false);
    
    private Headers() {
        // Empty
    }
    
    /**
     * Create headers out of a key-value pair array.<p>
     * 
     * All strings indexed with an even number is the header key. All strings
     * indexed with an odd number is the header value.<p>
     * 
     * Header values may be repeated, see {@link HttpHeaders}.<p>
     * 
     * @param keyValuePairs header entries
     * 
     * @return HttpHeaders
     * 
     * @throws IllegalArgumentException if {@code keyValuePairs.length} is not even
     */
    public static HttpHeaders of(String... keyValuePairs) {
        if (keyValuePairs.length == 0) {
            return EMPTY;
        }
        
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Please provide an even number of pairs.");
        }
        
        // Order from HttpHeaders.map() isn't specified, using LinkedHashMap as a "sweet bonus"
        Map<String, List<String>> map = new LinkedHashMap<>();
        
        for (int i = 0; i < keyValuePairs.length - 1; i += 2) {
            String k = keyValuePairs[i],
                   v = keyValuePairs[i + 1];
            
            map.computeIfAbsent(k, k0 -> new ArrayList<>(1)).add(v);
        }
        
        return HttpHeaders.of(map, (k, v) -> true);
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
     * @see HttpConstants.HeaderKey#ACCEPT
     */
    public static MediaType[] accept(HttpHeaders headers) {
        return headers.allValues(ACCEPT).stream()
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
     * @see HttpConstants.HeaderKey#CONTENT_TYPE
     */
    public static Optional<MediaType> contentType(HttpHeaders headers) {
        final List<String> values = headers.allValues(CONTENT_TYPE);
        
        if (values.isEmpty()) {
            return Optional.empty();
        }
        else if (values.size() == 1) {
            return Optional.of(MediaType.parse(values.get(0)));
        }
        
        throw new BadHeaderException("Multiple " + CONTENT_TYPE + " values in request.");
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
     * @see HttpConstants.HeaderKey#CONTENT_LENGTH
     */
    public static OptionalLong contentLength(HttpHeaders headers) {
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