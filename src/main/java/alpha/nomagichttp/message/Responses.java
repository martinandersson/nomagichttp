package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.HttpConstants.StatusCode;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.util.BetterBodyPublishers;
import alpha.nomagichttp.util.CodeAndPhraseCache;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

import static alpha.nomagichttp.HttpConstants.HeaderKey.CONNECTION;
import static alpha.nomagichttp.HttpConstants.HeaderKey.CONTENT_LENGTH;
import static alpha.nomagichttp.HttpConstants.HeaderKey.CONTENT_TYPE;
import static alpha.nomagichttp.HttpConstants.HeaderKey.UPGRADE;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.ACCEPTED;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.BAD_REQUEST;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.CONTINUE;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.ENTITY_TOO_LARGE;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.FORBIDDEN;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.HTTP_VERSION_NOT_SUPPORTED;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.INTERNAL_SERVER_ERROR;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.METHOD_NOT_ALLOWED;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.NOT_ACCEPTABLE;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.NOT_FOUND;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.NOT_IMPLEMENTED;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.NO_CONTENT;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.OK;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.PROCESSING;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.REQUEST_TIMEOUT;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.SERVICE_UNAVAILABLE;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.UNSUPPORTED_MEDIA_TYPE;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.UPGRADE_REQUIRED;
import static alpha.nomagichttp.HttpConstants.StatusCode.FIVE_HUNDRED;
import static alpha.nomagichttp.HttpConstants.StatusCode.FIVE_HUNDRED_FIVE;
import static alpha.nomagichttp.HttpConstants.StatusCode.FIVE_HUNDRED_ONE;
import static alpha.nomagichttp.HttpConstants.StatusCode.FIVE_HUNDRED_THREE;
import static alpha.nomagichttp.HttpConstants.StatusCode.FOUR_HUNDRED;
import static alpha.nomagichttp.HttpConstants.StatusCode.FOUR_HUNDRED_EIGHT;
import static alpha.nomagichttp.HttpConstants.StatusCode.FOUR_HUNDRED_FIFTEEN;
import static alpha.nomagichttp.HttpConstants.StatusCode.FOUR_HUNDRED_FIVE;
import static alpha.nomagichttp.HttpConstants.StatusCode.FOUR_HUNDRED_FOUR;
import static alpha.nomagichttp.HttpConstants.StatusCode.FOUR_HUNDRED_SIX;
import static alpha.nomagichttp.HttpConstants.StatusCode.FOUR_HUNDRED_THIRTEEN;
import static alpha.nomagichttp.HttpConstants.StatusCode.FOUR_HUNDRED_THREE;
import static alpha.nomagichttp.HttpConstants.StatusCode.FOUR_HUNDRED_TWENTY_SIX;
import static alpha.nomagichttp.HttpConstants.StatusCode.ONE_HUNDRED;
import static alpha.nomagichttp.HttpConstants.StatusCode.ONE_HUNDRED_TWO;
import static alpha.nomagichttp.HttpConstants.StatusCode.TWO_HUNDRED;
import static alpha.nomagichttp.HttpConstants.StatusCode.TWO_HUNDRED_FOUR;
import static alpha.nomagichttp.HttpConstants.StatusCode.TWO_HUNDRED_TWO;
import static alpha.nomagichttp.message.MediaType.APPLICATION_JSON_UTF8;
import static alpha.nomagichttp.message.MediaType.APPLICATION_OCTET_STREAM;
import static alpha.nomagichttp.message.MediaType.TEXT_HTML_UTF8;
import static alpha.nomagichttp.message.MediaType.TEXT_PLAIN_UTF8;
import static alpha.nomagichttp.util.BetterBodyPublishers.ofString;
import static alpha.nomagichttp.util.CodeAndPhraseCache.build;
import static java.net.http.HttpRequest.BodyPublisher;
import static java.net.http.HttpRequest.BodyPublishers;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Locale.ROOT;

/**
 * Factories of {@link Response}s.<p>
 * 
 * Even though this class produces ready-built responses, further modifications
 * of the response is easy to accomplish using {@code toBuilder()}.
 * 
 * <pre>
 *   Response update = Responses.processing() // 102 (Processing)
 *                              .toBuilder()
 *                              .header("Progress", "45%")
 *                              .build();
 * </pre>
 * 
 * Response objects may be created anew, or retrieved from an uber-fast cache.
 * This is documented on a per-method level. Creating a new response object is
 * very fast and normally nothing that should raise concern for a mature
 * object-oriented language, but using the cache will still be a better option
 * as it will reduce the memory footprint and GC pressure.<p>
 * 
 * In essence, responses (and therefore their builders as well) of all
 * well-known status codes and reason phrases are prebuilt and cached during
 * classloading. For instance, in the previous example, all steps before the
 * statement which sets the header traverses through cached entities. This is
 * true even if one replaces {@code processing()} with the more explicit {@code
 * status(102, "Processing")}. Pretty slick!<p>
 * 
 * All methods herein as well as the responses they return are thread-safe and
 * non-blocking.<p>
 * 
 * <strong>WARNING:</strong> Using an instance from {@link BodyPublishers} as a
 * response body may not be thread-safe where thread-safety matters or may block
 * the HTTP server thread. Consider using {@link BetterBodyPublishers} instead.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class Responses
{
    // Declaration of methods after status() follows ascending status-code order
    
    private Responses() {
        // Empty
    }
    
    /**
     * Returns a response with the specified status code.<p>
     * 
     * If the status code is a constant declared in {@link
     * HttpConstants.StatusCode}, then the returned reference is a cached
     * response. For any other status code, the response will be created anew
     * each time.
     * 
     * @param code HTTP status code
     * @return a response with the specified status code
     * @see HttpConstants.StatusCode
     */
    public static Response status(int code) {
        var rsp = CACHE.get(code);
        return rsp != null ? rsp :
                DefaultResponse.DefaultBuilder.ROOT.statusCode(code).build();
    }
    
    /**
     * Returns a response with the specified status code and reason phrase.<p>
     * 
     * If the code and phrase are a related pair as declared in {@link
     * HttpConstants.StatusCode} and {@link HttpConstants.ReasonPhrase} (object
     * equality for the phrase, i.e., case-sensitive), then the returned
     * reference is a cached response. For any other combination, the response
     * will be created anew each time.
     * <pre>
     *   // Components exists as constants and fit together
     *   Response cached = status(200, "OK");
     *   // Weird combo, was never pre-built!
     *   Response newGuy = status(200, "Not Found");
     * </pre>
     * 
     * @param code HTTP status code
     * @param phrase reason phrase
     * @return a response with the specified status code and reason phrase
     * @throws NullPointerException if {@code phrase} is {@code null}
     * @see HttpConstants.StatusCode
     * @see HttpConstants.ReasonPhrase
     */
    public static Response status(int code, String phrase) {
        var rsp = CACHE.get(code, phrase);
        return rsp != null ? rsp : DefaultResponse.DefaultBuilder.ROOT
                .statusCode(code)
                .reasonPhrase(phrase)
                .build();
    }
    
    /**
     * Retrieves a cached 100 (Continue) interim response.
     * 
     * @return a cached 100 (Continue) response
     * 
     * @see StatusCode#ONE_HUNDRED
     */
    public static Response continue_() {
        return CACHE.get(ONE_HUNDRED, CONTINUE);
    }
    
    /**
     * Retrieves a cached 102 (Processing) interim response.
     * 
     * @return a cached 102 (Processing) response
     *
     * @see StatusCode#ONE_HUNDRED_TWO
     */
    public static Response processing() {
        return CACHE.get(ONE_HUNDRED_TWO, PROCESSING);
    }
    
    /**
     * Creates a new 200 (OK) response with a text body.<p>
     * 
     * The content-type header will be set to "text/plain; charset=utf-8".
     * 
     * @param   textPlain message body
     * @return  a new 200 (OK) response
     * @see     StatusCode#TWO_HUNDRED
     */
    public static Response text(String textPlain) {
        return ok(ofString(textPlain), TEXT_PLAIN_UTF8);
    }
    
    /**
     * Creates a new 200 (OK) response with a text body.<p>
     * 
     * The content-type header will be set to "text/plain; charset=" + lower
     * cased canonical name of the given charset, e.g. "utf-8".
     * 
     * @param   textPlain message body
     * @param   charset for encoding
     * @return  a new 200 (OK) response
     * @see     StatusCode#TWO_HUNDRED
     */
    public static Response text(String textPlain, Charset charset) {
        return create("text/plain", textPlain, charset);
    }
    
    /**
     * Creates a new 200 (OK) response with a text body.<p>
     * 
     * The content-type's type/subtype will be set to "text/plain".<p>
     * 
     * The charset used for encoding will be extracted from the given request
     * header's corresponding "Accept" header value - if present. If no charset
     * preference is given by the request, or the given charset is not supported
     * by the running JVM, or the charset does not support encoding, then UTF-8
     * will be used. If the request specifies multiple charsets of equal weight,
     * then intrinsic order is undefined. The charset used will be appended to
     * the content-type, e.g. "; charset=utf-8".<p>
     * 
     * Suppose, for example, that the request has this header:
     * <pre>
     *   "Accept: text/plain; charset=utf-8; q=0.9, text/plain; charset=iso-8859-1
     * </pre>
     * 
     * The selected charset will be ISO-8859-1, because it has an implicit
     * q-value of 1.
     * 
     * @param   textPlain message body
     * @param   charsetSource to extract charset from
     * @return  a new 200 (OK) response
     * @see     StatusCode#TWO_HUNDRED
     * @see     MediaRange
     */
    public static Response text(String textPlain, Request charsetSource) {
        return create("text", "plain", textPlain, charsetSource);
    }
    
    /**
     * Creates a new 200 (OK) response with a HTML body.<p>
     * 
     * The content-type header will be set to "text/html; charset=utf-8".
     * 
     * @param   textHtml message body
     * @return  a new 200 (OK) response
     * @see     StatusCode#TWO_HUNDRED
     */
    public static Response html(String textHtml) {
        return ok(ofString(textHtml), TEXT_HTML_UTF8);
    }
    
    /**
     * Creates a new 200 (OK) response with a HTML body.<p>
     * 
     * The content-type header will be set to "text/html; charset=" + lower
     * cased canonical name of the given charset, e.g. "utf-8".
     * 
     * @param   textHtml message body
     * @param   charset for encoding
     * @return  a new 200 (OK) response
     * @see     StatusCode#TWO_HUNDRED
     */
    public static Response html(String textHtml, Charset charset) {
        return create("text/html", textHtml, charset);
    }
    
    /**
     * Creates a new 200 (OK) response with a HTML body.<p>
     * 
     * The content-type header will be set to "text/html".<p>
     * 
     * Encoding works the same as detailed in {@link #text(String, Request)}.
     * 
     * @param   textHtml message body
     * @param   charsetSource to extract charset from
     * @return  a new 200 (OK) response
     * @see     StatusCode#TWO_HUNDRED
     */
    public static Response html(String textHtml, Request charsetSource) {
        return create("text", "html", textHtml, charsetSource);
    }
    
    /**
     * Creates a new 200 (OK) response with a JSON body.<p>
     * 
     * The content-type header will be set to "application/json;
     * charset=utf-8".
     * 
     * @param   json message body
     * @return  a new 200 (OK) response
     * @see     StatusCode#TWO_HUNDRED
     */
    public static Response json(String json) {
        return ok(ofString(json), APPLICATION_JSON_UTF8);
    }
    
    /**
     * Creates a new 200 (OK) response with a JSON body.<p>
     * 
     * The content-type header will be set to "application/json; charset=" +
     * lower cased canonical name of the given charset, e.g. "utf-8".
     * 
     * @param   json message body
     * @param   charset for encoding
     * @return  a new 200 (OK) response
     * @see     StatusCode#TWO_HUNDRED
     */
    public static Response json(String json, Charset charset) {
        return create("application/json", json, charset);
    }
    
    
    /**
     * Creates a new 200 (OK) response with a HTML body.<p>
     * 
     * The content-type header will be set to "application/json".<p>
     * 
     * Encoding works the same as detailed in {@link #text(String, Request)}.
     * 
     * @param   json message body
     * @param   charsetSource to extract charset from
     * @return  a new 200 (OK) response
     * @see     StatusCode#TWO_HUNDRED
     */
    public static Response json(String json, Request charsetSource) {
        return create("application", "json", json, charsetSource);
    }
    
    /**
     * Creates a new 200 (OK) response with the given body.<p>
     * 
     * The content-type will be set to "application/octet-stream".
     * 
     * @param body data
     * 
     * @return a new 200 (OK) response
     *
     * @see StatusCode#TWO_HUNDRED
     */
    public static Response ok(BodyPublisher body) {
        return ok(body, APPLICATION_OCTET_STREAM);
    }
    
    /**
     * Creates a new 200 (OK) response with the given body.<p>
     * 
     * The given content-type will not be validated. For validation, do
     * <pre>
     *   {@link #ok(BodyPublisher, MediaType)
     *       ok}(body, MediaType.{@link MediaType#parse(String) parse}(contentType))
     * </pre>
     * 
     * @param body data
     * @param contentType header value
     * 
     * @return a new 200 (OK) response
     * 
     * @see StatusCode#TWO_HUNDRED
     * @see HttpConstants.HeaderKey#CONTENT_TYPE
     */
    public static Response ok(BodyPublisher body, String contentType) {
        return CACHE.get(TWO_HUNDRED, OK).toBuilder()
                .header(CONTENT_TYPE, contentType)
                .body(body)
                .build();
    }
    
    /**
     * Creates a new 200 (OK) response with the given body.
     * 
     * @param body data
     * @param contentType header value
     * 
     * @return a new 200 (OK) response
     * 
     * @see StatusCode#TWO_HUNDRED
     * @see HttpConstants.HeaderKey#CONTENT_TYPE
     */
    public static Response ok(BodyPublisher body, MediaType contentType) {
        return ok(body, contentType.toString());
    }
    
    /**
     * Creates a new 200 (OK) response with the given body.<p>
     * 
     * For an unknown body length, the length argument must be negative. For an
     * empty publisher, the length argument must be zero. Otherwise, the length
     * argument must be equal to the number of bytes emitted by the publisher.
     * A discrepancy has unknown application behavior.
     * 
     * The given content-type will not be validated. For validation, do
     * <pre>
     *   {@link #ok(Flow.Publisher, MediaType, long)
     *       ok}(body, MediaType.{@link MediaType#parse(String) parse}(contentType), long)
     * </pre>
     * 
     * @param body data
     * @param contentType header value
     * @param contentLength content length
     * 
     * @return a new 200 (OK) response
     * 
     * @see StatusCode#TWO_HUNDRED
     * @see Response.Builder#body(Flow.Publisher)
     * @see HttpConstants.HeaderKey#CONTENT_TYPE
     * @see HttpConstants.HeaderKey#CONTENT_LENGTH
     */
    public static Response ok(Flow.Publisher<ByteBuffer> body, String contentType, long contentLength) {
        Response.Builder b = CACHE.get(TWO_HUNDRED, OK).toBuilder()
                .header(CONTENT_TYPE, contentType);
        
        if (contentLength >= 0) {
            b = b.header(CONTENT_LENGTH, Long.toString(contentLength));
        } else {
            b = b.removeHeader(CONTENT_LENGTH);
        }
        
        return b.body(body).build();
    }
    
    /**
     * Creates a new 200 (OK) response with the given body.<p>
     * 
     * For an unknown body length, the length argument must be negative. For an
     * empty publisher, the length argument must be zero. Otherwise, the length
     * argument must be equal to the number of bytes emitted by the publisher.
     * Discrepancies has unknown application behavior.
     * 
     * @param body data
     * @param contentType header value
     * @param contentLength header value
     * 
     * @return a new 200 (OK) response
     * 
     * @see StatusCode#TWO_HUNDRED
     * @see Response.Builder#body(Flow.Publisher) 
     * @see HttpConstants.HeaderKey#CONTENT_TYPE
     * @see HttpConstants.HeaderKey#CONTENT_LENGTH
     */
    public static Response ok(Flow.Publisher<ByteBuffer> body, MediaType contentType, long contentLength) {
        return ok(body, contentType.toString(), contentLength);
    }
    
    /**
     * Retrieves a cached 202 (Accepted) response with no body.
     * 
     * @return  a cached 202 (Accepted)
     * @see    StatusCode#TWO_HUNDRED_TWO
     */
    public static Response accepted() {
        return CACHE.get(TWO_HUNDRED_TWO, ACCEPTED);
    }
    
    /**
     * Retrieves a cached 204 (No Content) response with no body.
     * 
     * @return a cached 204 (No Content) response
     * 
     * @see StatusCode#TWO_HUNDRED_FOUR
     */
    public static Response noContent() {
        return CACHE.get(TWO_HUNDRED_FOUR, NO_CONTENT);
    }
    
    /**
     * Retrieves a cached 400 (Bad Request) response with no body.
     * 
     * @return  a cached 400 (Bad Request) response
     * @see     StatusCode#FOUR_HUNDRED
     */
    public static Response badRequest() {
        return CACHE.get(FOUR_HUNDRED, BAD_REQUEST);
    }
    
    /**
     * Retrieves a cached 403 (Forbidden) response with no body.
     * 
     * @return  a cached 403 (Forbidden) response
     * @see     StatusCode#FOUR_HUNDRED_THREE
     */
    public static Response forbidden() {
        return CACHE.get(FOUR_HUNDRED_THREE, FORBIDDEN);
    }
    
    /**
     * Retrieves a cached 404 (Not Found) response with no body.
     * 
     * @return a cached 404 (Not Found)
     * @see     StatusCode#FOUR_HUNDRED_FOUR
     */
    public static Response notFound() {
        return CACHE.get(FOUR_HUNDRED_FOUR, NOT_FOUND);
    }
    
    /**
     * Retrieves a cached 405 (Method Not Allowed) response with no body.
     * 
     * @return  a cached 405 (Method Not Allowed) response
     * @see     StatusCode#FOUR_HUNDRED_FIVE
     */
    public static Response methodNotAllowed() {
        return CACHE.get(FOUR_HUNDRED_FIVE, METHOD_NOT_ALLOWED);
    }
    
    /**
     * Retrieves a cached 406 (Not Acceptable) response with no body.
     * 
     * @return  a cached 406 (Not Acceptable) response
     * @see     StatusCode#FOUR_HUNDRED_SIX
     */
    public static Response notAcceptable() {
        return CACHE.get(FOUR_HUNDRED_SIX, NOT_ACCEPTABLE);
    }
    
    /**
     * Creates a new 408 (Request Timeout) response with no body.<p>
     * 
     * The returned response contains the header "Connection: close" which will
     * cause the connection to gracefully close (see {@link ClientChannel}).
     * 
     * @return  a new 408 (Request Timeout) response
     * @see     StatusCode#FOUR_HUNDRED_EIGHT
     */
    public static Response requestTimeout() {
        return CACHE.get(FOUR_HUNDRED_EIGHT, REQUEST_TIMEOUT).toBuilder()
                .header(CONTENT_LENGTH, "0")
                .header(CONNECTION, "close")
                .build();
    }
    
    /**
     * Creates a new 413 (Entity Too Large) response with no body.<p>
     * 
     * The returned response contains the header "Connection: close" which will
     * cause the connection to gracefully close (see {@link ClientChannel}).
     * 
     * @return  a new 413 (Entity Too Large)
     * @see    StatusCode#FOUR_HUNDRED_THIRTEEN
     */
    public static Response entityTooLarge() {
        return CACHE.get(FOUR_HUNDRED_THIRTEEN, ENTITY_TOO_LARGE).toBuilder()
                .header(CONNECTION, "close").build();
    }
    
    /**
     * Retrieves a cached 415 (Unsupported Media Type) response with no body.
     * 
     * @return  a cached 415 (Unsupported Media Type) response
     * @see     StatusCode#FOUR_HUNDRED_FIFTEEN
     */
    public static Response unsupportedMediaType() {
        return CACHE.get(FOUR_HUNDRED_FIFTEEN, UNSUPPORTED_MEDIA_TYPE);
    }
    
    /**
     * Creates a new 426 (Upgrade Required) response with no body.
     * 
     * @param   upgrade header value (proposition for new protocol version)
     * @return  a new 426 (Upgrade Required) response
     * @see     StatusCode#FOUR_HUNDRED_TWENTY_SIX
     */
    public static Response upgradeRequired(String upgrade) {
        return CACHE.get(FOUR_HUNDRED_TWENTY_SIX, UPGRADE_REQUIRED).toBuilder()
                .addHeaders(
                        UPGRADE, upgrade,
                        CONNECTION, UPGRADE)
                .build();
    }
    
    /**
     * Retrieve a cached 500 (Internal Server Error) response with no body.
     * 
     * @return  a cached 500 (Internal Server Error) response
     * @see     StatusCode#FIVE_HUNDRED
     */
    public static Response internalServerError() {
        return CACHE.get(FIVE_HUNDRED, INTERNAL_SERVER_ERROR);
    }
    
    /**
     * Retrieves a cached 501 (Not Implemented) response with no body.
     * 
     * @return  a cached 501 (Not Implemented) response
     * @see     StatusCode#FIVE_HUNDRED_ONE
     */
    public static Response notImplemented() {
        return CACHE.get(FIVE_HUNDRED_ONE, NOT_IMPLEMENTED);
    }
    
    /**
     * Creates a new 503 (Service Unavailable) response with no body.<p>
     * 
     * The returned response contains the header "Connection: close" which will
     * cause the connection to gracefully close (see {@link ClientChannel}).
     * 
     * @return  a new 503 (Service Unavailable) response
     * @see     StatusCode#FIVE_HUNDRED_THREE
     */
    public static Response serviceUnavailable() {
        return CACHE.get(FIVE_HUNDRED_THREE, SERVICE_UNAVAILABLE).toBuilder()
                .header(CONTENT_LENGTH, "0")
                .header(CONNECTION, "close")
                .build();
    }
    
    /**
     * Creates a new 505 (HTTP Version Not Supported) response with no body.
     * 
     * @return a new 505 (HTTP Version Not Supported) response
     * @see    StatusCode#FIVE_HUNDRED_FIVE
     */
    public static Response httpVersionNotSupported() {
        return CACHE.get(FIVE_HUNDRED_FIVE, HTTP_VERSION_NOT_SUPPORTED).toBuilder()
                 .header(CONTENT_LENGTH, "0")
                 .build();
    }
    
    private static Response create(String mime, String body, Charset charset) {
        var pub = ofString(body, charset);
        var cType = mime + "; charset=" + charset.name().toLowerCase(ROOT);
        return ok(pub, cType);
    }
    
    private static Response create(String mimeType, String mimeSubtype, String body, Request charset) {
        var ch = getOrUTF8(charset, mimeType, mimeSubtype);
        var pub = ofString(body, ch);
        var cType = mimeType + "/" + mimeSubtype + "; charset=" + ch.name().toLowerCase(ROOT);
        return ok(pub, cType);
    }
    
    private static Charset getOrUTF8(Request req, String type, String subtype) {
        // Source
        final List<MediaType> mediaTypes = req.headers().accept();
        if (mediaTypes.isEmpty()) {
            return UTF_8;
        }
        
        // Stream modifiers
        Predicate<MediaType> correctType = mt ->
                type.equals(mt.type()) && subtype.equals(mt.subtype());
        ToDoubleFunction<MediaType> getQ = mt ->
                mt instanceof MediaRange ? ((MediaRange) mt).quality() : 1.;
        Comparator<MediaType> byQDesc = Comparator.comparingDouble(getQ).reversed();
        Function<MediaType, Charset> getCharset = mt -> {
            try {
                return Charset.forName(mt.parameters().get("charset"));
            } catch (IllegalArgumentException alsoForNPE) {
                return null;
            }
        };
        Predicate<Charset> discardNull = Objects::nonNull,
                           supportsEnc = Charset::canEncode;
        
        // Find it
        return mediaTypes.stream()
                         .filter(correctType)
                         .sorted(byQDesc)
                         .map(getCharset)
                         .filter(discardNull)
                         .filter(supportsEnc)
                         .findFirst()
                         .orElse(UTF_8);
    }
    
    private static final CodeAndPhraseCache<Response> CACHE = build(Responses::mk, Responses::mk);
    
    private static Response mk(int code) {
        return mk(code, null);
    }
    
    private static Response mk(int code, String phrase) {
        var b = DefaultResponse.DefaultBuilder.ROOT
                .statusCode(code);
        if (phrase != null) {
            b = b.reasonPhrase(phrase);
        }
        if (!isBodyForbidden(code)) {
            b = b.header(CONTENT_LENGTH, "0");
        }
        return b.build();
    }
    
    /**
     * Returns true if a response body is forbidden and therefore, a
     * Content-Length header must be excluded from the response. This is the
     * case for 1xx (Informational) and 204 (No Content) (RFC 7230 §3.3.2, top
     * of page 31).<p>
     * 
     * Same is also true for a 2xx (Successful) response to a CONNECT request -
     * although this is currently not implemented in any fashion by the library
     * (if any app ever implement CONNECT then they hopefully know what they're
     * doing lol).<p>
     * 
     * Otherwise (this method returns false), a "Content-Length: 0" header ought
     * to be set; indicating no payload body until a future modification decides
     * to add one.
     * 
     * @param code of status
     * @return see JavaDoc
     */
    private static boolean isBodyForbidden(int code) {
        return code >= 100 && code <= 199 || code == 204;
    }
}