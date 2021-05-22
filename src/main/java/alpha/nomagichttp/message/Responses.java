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
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.ENTITY_TOO_LARGE;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.HTTP_VERSION_NOT_SUPPORTED;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.REQUEST_TIMEOUT;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.SERVICE_UNAVAILABLE;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.UPGRADE_REQUIRED;
import static alpha.nomagichttp.HttpConstants.StatusCode.FIVE_HUNDRED;
import static alpha.nomagichttp.HttpConstants.StatusCode.FIVE_HUNDRED_FIVE;
import static alpha.nomagichttp.HttpConstants.StatusCode.FIVE_HUNDRED_ONE;
import static alpha.nomagichttp.HttpConstants.StatusCode.FIVE_HUNDRED_THREE;
import static alpha.nomagichttp.HttpConstants.StatusCode.FOUR_HUNDRED;
import static alpha.nomagichttp.HttpConstants.StatusCode.FOUR_HUNDRED_EIGHT;
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
 *   Response status = Responses.processing() // 102 (Processing)
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
     * Returns a 100 (Continue) interim response.
     * 
     * @return a 100 (Continue) response
     * 
     * @see StatusCode#ONE_HUNDRED
     */
    public static Response continue_() {
        return ResponseCache.CONTINUE;
    }
    
    /**
     * Returns a 102 (Processing) interim response.
     *
     * @return a 102 (Processing) response
     *
     * @see StatusCode#ONE_HUNDRED_TWO
     */
    public static Response processing() {
        return ResponseCache.PROCESSING;
    }
    
    /**
     * Returns a 204 (No Content) response with no body.
     * 
     * @return a 204 (No Content) response
     * 
     * @see StatusCode#TWO_HUNDRED_FOUR
     */
    public static Response noContent() {
        return ResponseCache.NO_CONTENT;
    }
    
    /**
     * Returns a 200 (OK) response with a text body.<p>
     * 
     * The content-type header will be set to "text/plain; charset=utf-8".
     * 
     * @param   textPlain message body
     * @return  a 200 (OK) response
     * @see     StatusCode#TWO_HUNDRED
     */
    public static Response text(String textPlain) {
        return ok(ofString(textPlain), "text/plain; charset=utf-8");
    }
    
    /**
     * Returns a 200 (OK) response with a HTML body.<p>
     * 
     * The content-type header will be set to "text/html; charset=utf-8".
     * 
     * @param   textHtml message body
     * @return  a 200 (OK) response
     * @see     StatusCode#TWO_HUNDRED
     */
    public static Response html(String textHtml) {
        return ok(ofString(textHtml), "text/html; charset=utf-8");
    }
    
    /**
     * Returns a 200 (OK) response with a JSON body.<p>
     * 
     * The content-type header will be set to "application/json; charset=utf-8".
     * 
     * @param   json message body
     * @return  a 200 (OK) response
     * @see     StatusCode#TWO_HUNDRED
     */
    public static Response json(String json) {
        return ok(ofString(json), "application/json; charset=utf-8");
    }
    
    /**
     * Returns a 200 (OK) response with the given body.<p>
     * 
     * The content-type will be set to "application/octet-stream".
     * 
     * @param body data
     * 
     * @return a 200 (OK) response
     *
     * @see StatusCode#TWO_HUNDRED
     */
    public static Response ok(BodyPublisher body) {
        return ok(body, APPLICATION_OCTET_STREAM);
    }
    
    /**
     * Returns a 200 (OK) response with the given body.
     * 
     * @param body data
     * @param contentType header value
     * 
     * @return a 200 (OK) response
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
     * Returns a 200 (OK) response with the given body.
     * 
     * @param body data
     * @param contentType header value
     * 
     * @return a 200 (OK) response
     * 
     * @see StatusCode#TWO_HUNDRED
     * @see HttpConstants.HeaderKey#CONTENT_TYPE
     */
    public static Response ok(BodyPublisher body, MediaType contentType) {
        return BuilderCache.OK
                .header(CONTENT_TYPE, contentType.toString())
                .body(body)
                .build();
    }
    
    /**
     * Returns a 200 (OK) response with the given body.<p>
     * 
     * This method is equivalent to:
     * <pre>
     *   {@link #ok(Flow.Publisher, MediaType, long)
     *       ok}(body, MediaType.parse(contentType), contentLength)
     * </pre>
     * 
     * @param body data
     * @param contentType header value
     * @param contentLength header value
     * 
     * @return a 200 (OK) response
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
     * Returns a 200 (OK) response with the given body.<p>
     * 
     * The server subscribing to the response body does not limit his
     * subscription based on the given length value. The value should be equal
     * to the number of bytes emitted by the publisher, never greater.<p>
     * 
     * For an unknown body length, the length argument must be negative. For an
     * empty publisher, the length argument must be zero. Discrepancies has
     * unknown application behavior.
     * 
     * @param body data
     * @param contentType header value
     * @param contentLength header value
     * 
     * @return a 200 (OK) response
     * 
     * @see StatusCode#TWO_HUNDRED
     * @see Response.Builder#body(Flow.Publisher) 
     * @see HttpConstants.HeaderKey#CONTENT_TYPE
     * @see HttpConstants.HeaderKey#CONTENT_LENGTH
     */
    public static Response ok(Flow.Publisher<ByteBuffer> body, MediaType contentType, long contentLength) {
        Response.Builder b = BuilderCache.OK
                 .header(CONTENT_TYPE, contentType.toString());
        
        if (contentLength >= 0) {
            b = b.header(CONTENT_LENGTH, Long.toString(contentLength));
        } // else unknown length, ResponsePipeline will deal with it
        
        return b.body(body).build();
    }
    
    /**
     * Returns a 202 (Accepted) response with no body.
     * 
     * @return  a 202 (Accepted)
     * @see    StatusCode#TWO_HUNDRED_TWO
     */
    public static Response accepted() {
        return ResponseCache.ACCEPTED;
    }
    
    /**
     * Returns a 400 (Bad Request) response with no body.
     * 
     * @return  a 400 (Bad Request) response
     * @see     StatusCode#FOUR_HUNDRED
     */
    public static Response badRequest() {
        return ResponseCache.BAD_REQUEST;
    }
    
    /**
     * Returns a 404 (Not Found) response with no body.
     * 
     * @return a 404 (Not Found)
     * @see     StatusCode#FOUR_HUNDRED_FOUR
     */
    public static Response notFound() {
        return ResponseCache.NOT_FOUND;
    }
    
    /**
     * Returns a 413 (Entity Too Large) response with no body.<p>
     * 
     * The response will also {@linkplain Response#mustCloseAfterWrite()
     * close the client channel}.
     * 
     * @return  a 413 (Entity Too Large)
     * @see    StatusCode#FOUR_HUNDRED_THIRTEEN
     */
    public static Response entityTooLarge() {
        return Response.builder(FOUR_HUNDRED_THIRTEEN, ENTITY_TOO_LARGE)
                .mustCloseAfterWrite(true).build();
    }
    
    /**
     * Returns a 500 (Internal Server Error) response with no body.
     * 
     * @return  a 500 (Internal Server Error) response
     * @see     StatusCode#FIVE_HUNDRED
     */
    public static Response internalServerError() {
        return ResponseCache.INTERNAL_SERVER_ERROR;
    }
    
    /**
     * Returns a 501 (Not Implemented) response with no body.
     * 
     * @return  a 501 (Not Implemented) response
     * @see     StatusCode#FIVE_HUNDRED_ONE
     */
    public static Response notImplemented() {
        return ResponseCache.NOT_IMPLEMENTED;
    }
    
    /**
     * Returns a 426 (Upgrade Required) response with no body.<p>
     * 
     * @param   upgrade header value (proposition for new protocol version)
     * @return  a 426 (Upgrade Required) response
     * @see     StatusCode#FOUR_HUNDRED_TWENTY_SIX
     */
    public static Response upgradeRequired(String upgrade) {
        return builder(FOUR_HUNDRED_TWENTY_SIX, UPGRADE_REQUIRED)
                 .addHeaders(
                     UPGRADE, upgrade,
                     CONNECTION, UPGRADE,
                     CONTENT_LENGTH, "0")
                 .build();
    }
    
    /**
     * Returns a 505 (HTTP Version Not Supported) response with no body.<p>
     * 
     * The response will {@linkplain Response#mustCloseAfterWrite() close the
     * client channel}.
     * 
     * @return  a 505 (HTTP Version Not Supported) response
     * @see     StatusCode#FIVE_HUNDRED_FIVE
     */
    public static Response httpVersionNotSupported() {
        return builder(FIVE_HUNDRED_FIVE, HTTP_VERSION_NOT_SUPPORTED)
                 .header(CONTENT_LENGTH, "0")
                 .mustCloseAfterWrite(true)
                 .build();
    }
    
    /**
     * Returns a 408 (Request Timeout) response with no body.<p>
     * 
     * The header "Connection: close" will be set.
     * 
     * @return  a 408 (Request Timeout) response
     * @see     StatusCode#FOUR_HUNDRED_EIGHT
     */
    public static Response requestTimeout() {
        return builder(FOUR_HUNDRED_EIGHT, REQUEST_TIMEOUT)
                .header(CONTENT_LENGTH, "0")
                .header(CONNECTION, "close")
                .build();
    }
    
    /**
     * Returns a 503 (Service Unavailable) response with no body.<p>
     * 
     * The header "Connection: close" will be set.
     * 
     * @return  a 503 (Service Unavailable) response
     * @see     StatusCode#FIVE_HUNDRED_THREE
     */
    public static Response serviceUnavailable() {
        return builder(FIVE_HUNDRED_THREE, SERVICE_UNAVAILABLE)
                .header(CONTENT_LENGTH, "0")
                .header(CONNECTION, "close")
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
            CONTINUE              = respond(ONE_HUNDRED, ReasonPhrase.CONTINUE, false),
            PROCESSING            = respond(ONE_HUNDRED_TWO, ReasonPhrase.PROCESSING, false),
            ACCEPTED              = respond(TWO_HUNDRED_TWO, ReasonPhrase.ACCEPTED, true),
            NO_CONTENT            = respond(TWO_HUNDRED_FOUR, ReasonPhrase.NO_CONTENT, false),
            BAD_REQUEST           = respond(FOUR_HUNDRED, ReasonPhrase.BAD_REQUEST, true),
            NOT_FOUND             = respond(FOUR_HUNDRED_FOUR, ReasonPhrase.NOT_FOUND, true),
            INTERNAL_SERVER_ERROR = respond(FIVE_HUNDRED, ReasonPhrase.INTERNAL_SERVER_ERROR, true),
            NOT_IMPLEMENTED       = respond(FIVE_HUNDRED_ONE, ReasonPhrase.NOT_IMPLEMENTED, true);
        
        private static Response respond(int code, String phrase, boolean addContentLengthZero) {
            var b = builder(code, phrase);
            if (addContentLengthZero) {
                b = b.header(CONTENT_LENGTH, "0");
            }
            return b.build();
        }
    }
}