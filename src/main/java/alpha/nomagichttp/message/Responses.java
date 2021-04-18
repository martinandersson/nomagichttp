package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.HttpConstants.ReasonPhrase;
import alpha.nomagichttp.HttpConstants.StatusCode;
import alpha.nomagichttp.util.BetterBodyPublishers;

import java.nio.ByteBuffer;
import java.util.concurrent.Flow;

import static alpha.nomagichttp.HttpConstants.HeaderKey.CONNECTION;
import static alpha.nomagichttp.HttpConstants.HeaderKey.CONTENT_LENGTH;
import static alpha.nomagichttp.HttpConstants.HeaderKey.CONTENT_TYPE;
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
import static alpha.nomagichttp.HttpConstants.StatusCode.ONE_HUNDRED;
import static alpha.nomagichttp.HttpConstants.StatusCode.ONE_HUNDRED_TWO;
import static alpha.nomagichttp.HttpConstants.StatusCode.TWO_HUNDRED;
import static alpha.nomagichttp.HttpConstants.StatusCode.TWO_HUNDRED_FOUR;
import static alpha.nomagichttp.HttpConstants.StatusCode.TWO_HUNDRED_TWO;
import static alpha.nomagichttp.message.MediaType.APPLICATION_OCTET_STREAM;
import static alpha.nomagichttp.message.MediaType.parse;
import static alpha.nomagichttp.message.Response.builder;
import static alpha.nomagichttp.util.BetterBodyPublishers.ofString;
import static java.net.http.HttpRequest.BodyPublisher;
import static java.net.http.HttpRequest.BodyPublishers;

/**
 * Factories of {@link Response}s.<p>
 * 
 * Even though this class produces ready-built responses, further modifications
 * of the response is easy to accomplish using {@code toBuilder()}.
 * 
 * <pre>
 *   Response status = Responses.processing()
 *                              .toBuilder()
 *                              .header("Progress", "45%")
 *                              .build();
 * </pre>
 * 
 * <strong>WARNING:</strong> Using {@link BodyPublishers} to create the response
 * body may not be thread-safe where thread-safety matters or may block the HTTP
 * server thread. Consider using {@link BetterBodyPublishers} instead.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class Responses
{
    private Responses() {
        // Empty
    }
    
    /**
     * Returns a "100 Continue" interim response.
     *
     * @return a "100 Continue" response
     *
     * @see StatusCode#ONE_HUNDRED
     */
    public static Response continue_() {
        return ResponseCache.CONTINUE;
    }
    
    /**
     * Returns a "102 Processing" interim response.
     *
     * @return a "102 Processing" response
     *
     * @see StatusCode#ONE_HUNDRED_TWO
     */
    public static Response processing() {
        return ResponseCache.PROCESSING;
    }
    
    /**
     * Returns a "204 No Content"-response with no body.
     * 
     * @return a "204 No Content" response
     * 
     * @see StatusCode#TWO_HUNDRED_FOUR
     */
    public static Response noContent() {
        return ResponseCache.NO_CONTENT;
    }
    
    /**
     * Returns a "200 OK"-response with a text body.<p>
     * 
     * The content-type header will be set to "text/plain; charset=utf-8".
     * 
     * @param   textPlain message body
     * @return  a "200 OK"-response
     * @see     StatusCode#TWO_HUNDRED
     */
    public static Response text(String textPlain) {
        return ok(ofString(textPlain), "text/plain; charset=utf-8");
    }
    
    /**
     * Returns a "200 OK"-response with a HTML body.<p>
     * 
     * The content-type header will be set to "text/html; charset=utf-8".
     * 
     * @param   textHtml message body
     * @return  a "200 OK"-response
     * @see     StatusCode#TWO_HUNDRED
     */
    public static Response html(String textHtml) {
        return ok(ofString(textHtml), "text/html; charset=utf-8");
    }
    
    /**
     * Returns a "200 OK"-response with a JSON body.<p>
     * 
     * The content-type header will be set to "application/json; charset=utf-8".
     * 
     * @param   json message body
     * @return  a "200 OK"-response
     * @see     StatusCode#TWO_HUNDRED
     */
    public static Response json(String json) {
        return ok(ofString(json), "application/json; charset=utf-8");
    }
    
    /**
     * Returns a "200 OK"-response with the given body.<p>
     * 
     * The content-type will be set to "application/octet-stream".
     * 
     * @param body data
     * 
     * @return a "200 OK"-response
     *
     * @see StatusCode#TWO_HUNDRED
     */
    public static Response ok(BodyPublisher body) {
        return ok(body, APPLICATION_OCTET_STREAM);
    }
    
    /**
     * Returns a "200 OK"-response with the given body.<p>
     * 
     * @param body data
     * @param contentType header value
     * 
     * @return a "200 OK"-response
     * 
     * @throws MediaTypeParseException
     *             if content-type failed to {@linkplain MediaType#parse(CharSequence) parse}
     * 
     * @see StatusCode#TWO_HUNDRED
     * @see HttpConstants.HeaderKey#CONTENT_TYPE
     */
    public static Response ok(BodyPublisher body, String contentType) {
        return ok(body, parse(contentType));
    }
    
    /**
     * Returns a "200 OK"-response with the given body.<p>
     * 
     * @param body data
     * @param contentType header value
     * 
     * @return a "200 OK"-response
     * 
     * @see StatusCode#TWO_HUNDRED
     * @see HttpConstants.HeaderKey#CONTENT_TYPE
     */
    public static Response ok(BodyPublisher body, MediaType contentType) {
        // TODO: body.contentLength() may return < 0 for "unknown length"
        return ok(body, contentType, body.contentLength());
    }
    
    /**
     * Returns a "200 OK"-response with the given body.<p>
     * 
     * The server subscribing to the response body does not limit his
     * subscription based on the given length value. The value must be equal to
     * the number of bytes emitted by the publisher.
     * 
     * @param body data
     * @param contentType header value
     * @param contentLength header value
     * 
     * @return a "200 OK"-response
     * 
     * @throws MediaTypeParseException
     *             if content-type failed to {@linkplain MediaType#parse(CharSequence) parse}
     * 
     * @see StatusCode#TWO_HUNDRED
     * @see HttpConstants.HeaderKey#CONTENT_TYPE
     * @see HttpConstants.HeaderKey#CONTENT_LENGTH
     */
    public static Response ok(Flow.Publisher<ByteBuffer> body, String contentType, long contentLength) {
        return ok(body, parse(contentType), contentLength);
    }
    
    /**
     * Returns a "200 OK"-response with the given body.<p>
     * 
     * The server subscribing to the response body does not limit his
     * subscription based on the given length value. The value must be equal to
     * the number of bytes emitted by the publisher.
     * 
     * @param body data
     * @param contentType header value
     * @param contentLength header value
     * 
     * @return a "200 OK"-response
     * 
     * @see StatusCode#TWO_HUNDRED
     * @see HttpConstants.HeaderKey#CONTENT_TYPE
     * @see HttpConstants.HeaderKey#CONTENT_LENGTH
     */
    public static Response ok(Flow.Publisher<ByteBuffer> body, MediaType contentType, long contentLength) {
        return BuilderCache.OK
                 .header(CONTENT_TYPE,   contentType.toString())
                 .header(CONTENT_LENGTH, Long.toString(contentLength))
                 .body(body)
                 .build();
    }
    
    /**
     * Returns a "202 Accepted"-response with no body.
     * 
     * @return  a "202 Accepted"
     * @see    StatusCode#TWO_HUNDRED_TWO
     */
    public static Response accepted() {
        return ResponseCache.ACCEPTED;
    }
    
    /**
     * Returns a "400 Bad Request"-response with no body.<p>
     * 
     * The response will {@linkplain Response#mustCloseAfterWrite() close the
     * client channel} after having been sent.
     * 
     * @return  a "400 Bad Request"-response
     * @see     StatusCode#FOUR_HUNDRED
     */
    public static Response badRequest() {
        return ResponseCache.BAD_REQUEST;
    }
    
    /**
     * Returns a "404 Not Found"-response with no body.<p>
     * 
     * The response will {@linkplain Response#mustCloseAfterWrite() close the
     * client channel} after having been sent.
     * 
     * @return a "404 Not Found"
     * @see     StatusCode#FOUR_HUNDRED_FOUR
     */
    public static Response notFound() {
        return ResponseCache.NOT_FOUND;
    }
    
    /**
     * Returns a "413 Entity Too Large"-response with no body.<p>
     * 
     * The response will {@linkplain Response#mustCloseAfterWrite() close the
     * client channel} after having been sent.
     * 
     * @return  a "413 Entity Too Large"
     * @see    StatusCode#FOUR_HUNDRED_THIRTEEN
     */
    public static Response entityTooLarge() {
        return ResponseCache.ENTITY_TOO_LARGE;
    }
    
    /**
     * Returns a "500 Internal Server Error"-response with no body.<p>
     * 
     * The response will {@linkplain Response#mustCloseAfterWrite() close the
     * client channel} after having been sent.
     * 
     * @return  a "500 Internal Server Error"-response
     * @see     StatusCode#FIVE_HUNDRED
     */
    public static Response internalServerError() {
        return ResponseCache.INTERNAL_SERVER_ERROR;
    }
    
    /**
     * Returns a "501 Not Implemented"-response with no body.<p>
     * 
     * The response will {@linkplain Response#mustCloseAfterWrite() close the
     * client channel} after having been sent.
     * 
     * @return  a "501 Not Implemented"-response
     * @see     StatusCode#FIVE_HUNDRED_ONE
     */
    public static Response notImplemented() {
        return ResponseCache.NOT_IMPLEMENTED;
    }
    
    /**
     * Returns a "426 Upgrade Required"-response with no body.<p>
     * 
     * The response will {@linkplain Response#mustCloseAfterWrite() close the
     * client channel} after having been sent.
     * 
     * @param   upgrade header value (proposition for new protocol version)
     * @return  a "426 Upgrade Required"-response
     * @see     StatusCode#FOUR_HUNDRED_TWENTY_SIX
     */
    public static Response upgradeRequired(String upgrade) {
        return builder(FOUR_HUNDRED_TWENTY_SIX, UPGRADE_REQUIRED)
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
     * @return  a "505 HTTP Version Not Supported"-response
     * @see     StatusCode#FIVE_HUNDRED_FIVE
     */
    public static Response httpVersionNotSupported() {
        return builder(FIVE_HUNDRED_FIVE, HTTP_VERSION_NOT_SUPPORTED)
                 .header(CONTENT_LENGTH, "0")
                 .mustCloseAfterWrite(true)
                 .build();
    }
    
    /**
     * Pre-built builder objects.
     */
    private static final class BuilderCache {
        static final Response.Builder OK = builder(TWO_HUNDRED, ReasonPhrase.OK);
    }
    
    /**
     * Pre-built response objects with no payloads.
     */
    private static final class ResponseCache {
        static final Response
            CONTINUE              = builder(ONE_HUNDRED, ReasonPhrase.CONTINUE).build(),
            PROCESSING            = builder(ONE_HUNDRED_TWO, ReasonPhrase.PROCESSING).build(),
            ACCEPTED              = builder(TWO_HUNDRED_TWO, ReasonPhrase.ACCEPTED).header(CONTENT_LENGTH, "0").build(),
            NO_CONTENT            = builder(TWO_HUNDRED_FOUR, ReasonPhrase.NO_CONTENT).build(),
            BAD_REQUEST           = respondThenClose(FOUR_HUNDRED, ReasonPhrase.BAD_REQUEST),
            NOT_FOUND             = respondThenClose(FOUR_HUNDRED_FOUR, ReasonPhrase.NOT_FOUND),
            ENTITY_TOO_LARGE      = respondThenClose(FOUR_HUNDRED_THIRTEEN, ReasonPhrase.ENTITY_TOO_LARGE),
            INTERNAL_SERVER_ERROR = respondThenClose(FIVE_HUNDRED, ReasonPhrase.INTERNAL_SERVER_ERROR),
            NOT_IMPLEMENTED       = respondThenClose(FIVE_HUNDRED_ONE, ReasonPhrase.NOT_IMPLEMENTED);
        
        private static Response respondThenClose(int code, String phrase) {
            return builder(code, phrase)
                     .header(CONTENT_LENGTH, "0")
                     .mustCloseAfterWrite(true)
                     .build();
        }
    }
}