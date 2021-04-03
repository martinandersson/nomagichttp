package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.HttpConstants.ReasonPhrase;
import alpha.nomagichttp.HttpConstants.StatusCode;
import alpha.nomagichttp.util.BetterBodyPublishers;

import java.nio.ByteBuffer;
import java.util.concurrent.Flow;

import static alpha.nomagichttp.HttpConstants.HeaderKey.CONNECTION;
import static alpha.nomagichttp.HttpConstants.HeaderKey.CONTENT_LENGTH;
import static alpha.nomagichttp.HttpConstants.HeaderKey.UPGRADE;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.HTTP_VERSION_NOT_SUPPORTED;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.UPGRADE_REQUIRED;
import static alpha.nomagichttp.HttpConstants.StatusCode.FIVE_HUNDRED;
import static alpha.nomagichttp.HttpConstants.StatusCode.FIVE_HUNDRED_FIVE;
import static alpha.nomagichttp.HttpConstants.StatusCode.FIVE_HUNDRED_ONE;
import static alpha.nomagichttp.HttpConstants.StatusCode.FOUR_HUNDRED;
import static alpha.nomagichttp.HttpConstants.StatusCode.FOUR_HUNDRED_FOUR;
import static alpha.nomagichttp.HttpConstants.StatusCode.FOUR_HUNDRED_THIRTEEN;
import static alpha.nomagichttp.HttpConstants.StatusCode.FOUR_HUNDRED_TWENTY_SIX;
import static alpha.nomagichttp.util.BetterBodyPublishers.ofString;
import static java.net.http.HttpRequest.BodyPublisher;
import static java.net.http.HttpRequest.BodyPublishers;

/**
 * Utility methods for building complete {@link Response}s.<p>
 * 
 * For a more fine-grained control, use {@link Response#builder()} or
 * semi-populated builders such as {@link Response.Builder#ok()} and {@link
 * Response.Builder#accepted()}.<p>
 * 
 * <strong>WARNING:</strong> Using {@link BodyPublishers} to create the response
 * body may not be thread-safe where thread-safety matters or may block the HTTP
 * server thread. Consider using {@link BetterBodyPublishers} instead. See
 * {@link Response.Builder#body(Flow.Publisher)}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class Responses
{
    private Responses() {
        // Empty
    }
    
    /**
     * Returns a "204 OK"-response with no body.
     * 
     * @return  a "204 OK"-response with no body
     * @see     StatusCode#TWO_HUNDRED_FOUR
     */
    public static Response noContent() {
        return Cache.NO_CONTENT;
    }
    
    /**
     * Returns a "200 OK"-response with a text body.<p>
     * 
     * The Content-Type header will be set to "text/plain; charset=utf-8".
     * 
     * @param   textPlain message body
     * @return  a "200 OK"-response with a text body
     * @see     StatusCode#TWO_HUNDRED
     */
    public static Response text(String textPlain) {
        return ok("text/plain; charset=utf-8", ofString(textPlain));
    }
    
    /**
     * Returns a "200 OK"-response with an arbitrary body.<p>
     * 
     * @param contentType header value
     * @param body publisher with content length
     * 
     * @return a "200 OK"-response with an arbitrary body
     * 
     * @see StatusCode#TWO_HUNDRED
     * @see HttpConstants.HeaderKey#CONTENT_TYPE
     */
    public static Response ok(String contentType, BodyPublisher body) {
        return ok(MediaType.parse(contentType), body, body.contentLength());
    }
    
    /**
     * Returns a "200 OK"-response with an arbitrary body.<p>
     * 
     * @param contentType header value
     * @param body publisher with content length
     * 
     * @return a "200 OK"-response with an arbitrary body
     * 
     * @see StatusCode#TWO_HUNDRED
     */
    public static Response ok(MediaType contentType, BodyPublisher body) {
        return ok(contentType, body, body.contentLength());
    }
    
    /**
     * Returns a "200 OK"-response with an arbitrary body.<p>
     * 
     * The server subscribing to the response body does not limit his
     * subscription based on the given length value. The value must be equal to
     * the number of bytes emitted by the publisher.
     * 
     * @param contentType header value
     * @param body publisher
     * @param length will be set as "Content-Length" header value
     * 
     * @return a "200 OK"-response with an arbitrary body
     * 
     * @see StatusCode#TWO_HUNDRED
     * @see HttpConstants.HeaderKey#CONTENT_TYPE
     * @see HttpConstants.HeaderKey#CONTENT_LENGTH
     */
    public static Response ok(String contentType, Flow.Publisher<ByteBuffer> body, long length) {
        return ok(MediaType.parse(contentType), body, length);
    }
    
    /**
     * Returns a "200 OK"-response with an arbitrary body.<p>
     * 
     * The server subscribing to the response body does not limit his
     * subscription based on the given length value. The value must be equal to
     * the number of bytes emitted by the publisher.
     * 
     * @param contentType header value
     * @param body publisher
     * @param length of body (will be set as "Content-Length" header value)
     * 
     * @return a "200 OK"-response with an arbitrary body
     * 
     * @see StatusCode#TWO_HUNDRED
     * @see HttpConstants.HeaderKey#CONTENT_TYPE
     * @see HttpConstants.HeaderKey#CONTENT_LENGTH
     */
    public static Response ok(MediaType contentType, Flow.Publisher<ByteBuffer> body, long length) {
        return Response.Builder.ok()
                .contentType(contentType)
                .contentLenght(length)
                .body(body)
                .build();
    }
    
    /**
     * Returns a "202 Accepted"-response with no body.
     * 
     * @return  a "202 Accepted"-response with no body
     * @see     StatusCode#TWO_HUNDRED_TWO
     */
    public static Response accepted() {
        return Cache.ACCEPTED;
    }
    
    /**
     * Returns a "400 Bad Request"-response with no body.<p>
     * 
     * The response will {@linkplain Response#mustCloseAfterWrite() close the
     * client channel} after having been sent.
     * 
     * @return  a "400 Bad Request"-response with no body
     * @see     StatusCode#FOUR_HUNDRED
     */
    public static Response badRequest() {
        return Cache.BAD_REQUEST;
    }
    
    /**
     * Returns a "404 Not Found"-response with no body.<p>
     * 
     * The response will {@linkplain Response#mustCloseAfterWrite() close the
     * client channel} after having been sent.
     * 
     * @return a "404 Not Found"-response with no body
     * @see     StatusCode#FOUR_HUNDRED_FOUR
     */
    public static Response notFound() {
        return Cache.NOT_FOUND;
    }
    
    /**
     * Returns a "413 Entity Too Large"-response with no body.<p>
     * 
     * The response will {@linkplain Response#mustCloseAfterWrite() close the
     * client channel} after having been sent.
     * 
     * @return  a "413 Entity Too Large"-response with no body
     * @see    StatusCode#FOUR_HUNDRED_THIRTEEN
     */
    public static Response entityTooLarge() {
        return Cache.ENTITY_TOO_LARGE;
    }
    
    /**
     * Returns a "500 Internal Server Error"-response with no body.<p>
     * 
     * The response will {@linkplain Response#mustCloseAfterWrite() close the
     * client channel} after having been sent.
     * 
     * @return  a "500 Internal Server Error"-response with no body
     * @see     StatusCode#FIVE_HUNDRED
     */
    public static Response internalServerError() {
        return Cache.INTERNAL_SERVER_ERROR;
    }
    
    /**
     * Returns a "501 Not Implemented"-response with no body.<p>
     * 
     * The response will {@linkplain Response#mustCloseAfterWrite() close the
     * client channel} after having been sent.
     * 
     * @return  a "501 Not Implemented"-response with no body
     * @see     StatusCode#FIVE_HUNDRED_ONE
     */
    public static Response notImplemented() {
        return Cache.NOT_IMPLEMENTED;
    }
    
    /**
     * Returns a "426 Upgrade Required"-response with no body.<p>
     * 
     * The response will {@linkplain Response#mustCloseAfterWrite() close the
     * client channel} after having been sent.
     * 
     * @param   upgrade header value (proposition for new protocol version)
     * @return  a "426 Upgrade Required"-response with no body
     * @see     StatusCode#FOUR_HUNDRED_TWENTY_SIX
     */
    public static Response upgradeRequired(String upgrade) {
        return Response.builder()
                .statusCode(FOUR_HUNDRED_TWENTY_SIX)
                .reasonPhrase(UPGRADE_REQUIRED)
                .addHeaders(
                    UPGRADE, upgrade,
                    CONNECTION, UPGRADE,
                    CONTENT_LENGTH, "0")
                .mustCloseAfterWrite(true)
                .build();
    }
    
    /**
     * Returns a "505 HTTP Version Not Supported"-response with no body.<p>
     * 
     * The response will {@linkplain Response#mustCloseAfterWrite() close the
     * client channel} after having been sent.
     * 
     * @return  a "505 HTTP Version Not Supported"-response with no body
     * @see     StatusCode#FIVE_HUNDRED_FIVE
     */
    public static Response httpVersionNotSupported() {
        return Response.builder()
                .statusCode(FIVE_HUNDRED_FIVE)
                .reasonPhrase(HTTP_VERSION_NOT_SUPPORTED)
                .contentLenght(0)
                .mustCloseAfterWrite(true)
                .build();
    }
    
    /**
     * Pre-built response objects with no payloads (message body).
     */
    private static final class Cache {
        static final Response
                ACCEPTED              = Response.Builder.accepted().contentLenght(0).build(),
                NO_CONTENT            = Response.Builder.noContent().build(),
                BAD_REQUEST           = respondThenClose(FOUR_HUNDRED, ReasonPhrase.BAD_REQUEST),
                NOT_FOUND             = respondThenClose(FOUR_HUNDRED_FOUR, ReasonPhrase.NOT_FOUND),
                ENTITY_TOO_LARGE      = respondThenClose(FOUR_HUNDRED_THIRTEEN, ReasonPhrase.ENTITY_TOO_LARGE),
                INTERNAL_SERVER_ERROR = respondThenClose(FIVE_HUNDRED, ReasonPhrase.INTERNAL_SERVER_ERROR),
                NOT_IMPLEMENTED       = respondThenClose(FIVE_HUNDRED_ONE, ReasonPhrase.NOT_IMPLEMENTED);
        
        private static Response respondThenClose(int code, String phrase) {
            return Response.builder()
                    .statusCode(code)
                    .reasonPhrase(phrase)
                    .header(CONTENT_LENGTH, "0")
                    .mustCloseAfterWrite(true)
                    .build();
        }
    }
}