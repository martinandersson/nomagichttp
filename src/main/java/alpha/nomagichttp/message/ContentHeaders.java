package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;

import java.util.Deque;
import java.util.Optional;
import java.util.OptionalLong;

import static alpha.nomagichttp.HttpConstants.HeaderName.TRANSFER_ENCODING;

/**
 * Extraction methods for content-related headers.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface ContentHeaders extends BetterHeaders
{
    /**
     * Parses one "Content-Type" value into a media type.<p>
     * 
     * This header indicates the media type of the message body and should be
     * set by the sender if the message carries a body payload.<p>
     * 
     * TODO: Example.
     * 
     * @return parsed value (never {@code null})
     * 
     * @throws BadHeaderException
     *           if the headers has multiple Content-Type values, or
     *           if parsing failed (cause set to {@link MediaTypeParseException})
     * 
     * @see HttpConstants.HeaderName#CONTENT_TYPE
     */
    Optional<MediaType> contentType();
    
    /**
     * Parses one "Content-Length" value into a zero or positive long.<p>
     * 
     * This header is the message body length in bytes and should be set by the
     * sender if the message carries a body payload.<p>
     * 
     * TODO: Example.<p>
     * 
     * An empty optional is returned if the header is not present.
     * 
     * @return parsed value (never {@code null})
     * 
     * @throws BadHeaderException
     *             if the headers has multiple Content-Length values, or
     *             if parsing failed (cause set to {@link NumberFormatException}), or
     *             if the parsed value is negative
     * 
     * @see HttpConstants.HeaderName#CONTENT_LENGTH
     */
    OptionalLong contentLength();
    
    /**
     * Returns all "Transfer-Encoding" tokens.<p>
     * 
     * The returned deque is modifiable, and consequently, not cached.
     * 
     * @apiNote
     * Will do modifiable and cache whenever
     * <a href="https://bugs.openjdk.java.net/browse/JDK-6407460">JDK-6407460</a>
     * is resolved.
     * 
     * @return all Transfer-Encoding tokens (never {@code null})
     * 
     * @throws BadHeaderException
     *       if the last token in the Transfer-Encoding header is not "chunked"
     * 
     * @see HttpConstants.HeaderName#TRANSFER_ENCODING
     */
    Deque<String> transferEncoding();
    
    /**
     * Returns whether the "Transfer-Encoding" header contains the value
     * "chunked".<p>
     * 
     * Both header and value is checked without regard to casing.
     * 
     * @implSpec
     * The default implementation is:
     * <pre>
     *     return {@link #contains(String, String) contains
     *     }("Transfer-Encoding", "chunked");
     * </pre>
     * 
     * @return see JavaDoc
     */
    default boolean isChunked() {
        return contains(TRANSFER_ENCODING, "chunked");
    }
}