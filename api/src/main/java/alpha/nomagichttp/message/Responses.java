package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.HttpConstants.StatusCode;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.util.CodeAndPhraseCache;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

import static alpha.nomagichttp.HttpConstants.HeaderName.CONNECTION;
import static alpha.nomagichttp.HttpConstants.HeaderName.CONTENT_TYPE;
import static alpha.nomagichttp.HttpConstants.HeaderName.UPGRADE;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.ACCEPTED;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.BAD_REQUEST;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.CONTINUE;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.ENTITY_TOO_LARGE;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.FORBIDDEN;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.HTTP_VERSION_NOT_SUPPORTED;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.IM_A_TEAPOT;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.INTERNAL_SERVER_ERROR;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.METHOD_NOT_ALLOWED;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.NOT_ACCEPTABLE;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.NOT_FOUND;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.NOT_IMPLEMENTED;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.NO_CONTENT;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.OK;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.PRECONDITION_FAILED;
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
import static alpha.nomagichttp.HttpConstants.StatusCode.FOUR_HUNDRED_EIGHTEEN;
import static alpha.nomagichttp.HttpConstants.StatusCode.FOUR_HUNDRED_FIFTEEN;
import static alpha.nomagichttp.HttpConstants.StatusCode.FOUR_HUNDRED_FIVE;
import static alpha.nomagichttp.HttpConstants.StatusCode.FOUR_HUNDRED_FOUR;
import static alpha.nomagichttp.HttpConstants.StatusCode.FOUR_HUNDRED_SIX;
import static alpha.nomagichttp.HttpConstants.StatusCode.FOUR_HUNDRED_THIRTEEN;
import static alpha.nomagichttp.HttpConstants.StatusCode.FOUR_HUNDRED_THREE;
import static alpha.nomagichttp.HttpConstants.StatusCode.FOUR_HUNDRED_TWELVE;
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
import static alpha.nomagichttp.util.ByteBufferIterables.ofString;
import static alpha.nomagichttp.util.CodeAndPhraseCache.build;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Locale.ROOT;

/**
 * Factories of {@code Response}s.<p>
 * 
 * Although {@link Response} has no mutating methods, modifications of any
 * response are possible by using {@code toBuilder()}.
 * 
 * <pre>
 *   Response update = Responses.processing() // 102 (Processing)
 *                              .toBuilder()
 *                              .setHeader("Progress", "45%")
 *                              .build();
 * </pre>
 * 
 * Many responses returned from this class are retrieved from an uber-fast
 * cache. Whether new or cached; this is documented per method.<p>
 * 
 * In the previous example, only {@code setHeader} and {@code build} returns
 * new objects. This is true even if one replaces {@code processing()} with the
 * more explicit {@code status(102, "Processing")}.<p>
 * 
 * However that may be, creating a new response object is very fast and nothing
 * that should raise concerns for a mature object-oriented language like
 * Java.
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
     * {@return a response with the specified status code}<p>
     * 
     * If the status code is a constant declared in {@link
     * HttpConstants.StatusCode}, then the returned reference is a cached
     * response. For any other status code, the response will be created anew
     * each time.
     * 
     * @param code HTTP status code
     * @see HttpConstants.StatusCode
     */
    public static Response status(int code) {
        var rsp = CACHE.get(code);
        return rsp != null ? rsp :
                DefaultResponse.DefaultBuilder.ROOT.statusCode(code).build();
    }
    
    /**
     * {@return a response with the specified status code and reason phrase}<p>
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
     * 
     * @throws NullPointerException
     *             if {@code phrase} is {@code null}
     * 
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
     * {@return a cached 100 (Continue) interim response}
     * 
     * @see StatusCode#ONE_HUNDRED
     */
    public static Response continue_() {
        return CACHE.get(ONE_HUNDRED, CONTINUE);
    }
    
    /**
     * {@return a cached 102 (Processing) interim response}
     *
     * @see StatusCode#ONE_HUNDRED_TWO
     */
    public static Response processing() {
        return CACHE.get(ONE_HUNDRED_TWO, PROCESSING);
    }
    
    /**
     * {@return a new 200 (OK) response with a text body}<p>
     * 
     * The content-type header will be set to "text/plain; charset=utf-8".
     * 
     * @param textPlain message body
     * 
     * @throws CharacterCodingException
     *             if input is malformed, or
     *             if a character is unmappable
     * 
     * @see StatusCode#TWO_HUNDRED
     */
    public static Response text(String textPlain)
            throws CharacterCodingException {
        return ok(ofString(textPlain), TEXT_PLAIN_UTF8);
    }
    
    /**
     * {@return a new 200 (OK) response with a text body}<p>
     * 
     * The content-type header will be set to "text/plain; charset=" + lower
     * cased canonical name of the given charset, e.g. "utf-8".
     * 
     * @param textPlain message body
     * @param charset for encoding
     * 
     * @throws CharacterCodingException
     *             if input is malformed, or
     *             if a character is unmappable
     * 
     * @see StatusCode#TWO_HUNDRED
     */
    public static Response text(String textPlain, Charset charset)
            throws CharacterCodingException {
        return create("text/plain", textPlain, charset);
    }
    
    /**
     * {@return a new 200 (OK) response with a text body}<p>
     * 
     * The content-type's type/subtype will be set to "text/plain".<p>
     * 
     * The charset used for encoding will be extracted from the given request
     * header's corresponding "Accept" header value - if present. If no charset
     * preference is given by the request, or the given charset is not supported
     * by the running JVM, or the charset does not support encoding, then UTF-8
     * will be used. If the request specifies multiple charsets of equal weight,
     * then intrinsic order is undefined.<p>
     * 
     * Suppose, for example, that the request has this header:
     * <pre>
     *   "Accept: text/plain; charset=utf-8; q=0.9, text/plain; charset=iso-8859-1
     * </pre>
     * 
     * The selected charset will be ISO-8859-1, because it has an implicit
     * q-value of 1.<p>
     * 
     * The charset used will be appended to the content-type, e.g.
     * "; charset=utf-8".
     * 
     * @param textPlain message body
     * @param charsetSource to extract charset from
     * 
     * @throws CharacterCodingException
     *             if input is malformed, or
     *             if a character is unmappable
     * 
     * @see StatusCode#TWO_HUNDRED
     * @see MediaRange
     */
    public static Response text(String textPlain, Request charsetSource)
            throws CharacterCodingException {
        return create("text", "plain", textPlain, charsetSource);
    }
    
    /**
     * {@return a new 200 (OK) response with a HTML body}<p>
     * 
     * The content-type header will be set to "text/html; charset=utf-8".
     * 
     * @param textHtml message body
     * 
     * @throws CharacterCodingException
     *             if input is malformed, or
     *             if a character is unmappable
     * 
     * @see StatusCode#TWO_HUNDRED
     */
    public static Response html(String textHtml)
            throws CharacterCodingException {
        return ok(ofString(textHtml), TEXT_HTML_UTF8);
    }
    
    /**
     * {@return a new 200 (OK) response with an HTML body}<p>
     * 
     * The content-type header will be set to "text/html; charset=" + lower
     * cased canonical name of the given charset, e.g. "utf-8".
     * 
     * @param textHtml message body
     * @param charset for encoding
     * 
     * @throws CharacterCodingException
     *             if input is malformed, or
     *             if a character is unmappable
     * 
     * @see StatusCode#TWO_HUNDRED
     */
    public static Response html(
            String textHtml, Charset charset) throws CharacterCodingException {
        return create("text/html", textHtml, charset);
    }
    
    /**
     * {@return a new 200 (OK) response with a HTML body}<p>
     * 
     * The content-type header will be set to "text/html".<p>
     * 
     * Encoding works the same as detailed in {@link #text(String, Request)}.
     * 
     * @param textHtml message body
     * @param charsetSource to extract charset from
     * 
     * @throws CharacterCodingException
     *             if input is malformed, or
     *             if a character is unmappable
     * 
     * @see StatusCode#TWO_HUNDRED
     */
    public static Response html(String textHtml, Request charsetSource)
            throws CharacterCodingException {
        return create("text", "html", textHtml, charsetSource);
    }
    
    /**
     * {@return a new 200 (OK) response with a JSON body}<p>
     * 
     * The content-type header will be set to "application/json;
     * charset=utf-8".
     * 
     * @param json message body
     * 
     * @throws CharacterCodingException
     *             if input is malformed, or
     *             if a character is unmappable
     * 
     * @see StatusCode#TWO_HUNDRED
     */
    public static Response json(String json) throws CharacterCodingException {
        return ok(ofString(json), APPLICATION_JSON_UTF8);
    }
    
    /**
     * {@return a new 200 (OK) response with a JSON body}<p>
     * 
     * The content-type header will be set to "application/json; charset=" +
     * lower cased canonical name of the given charset, e.g. "utf-8".
     * 
     * @param json message body
     * @param charset for encoding
     * 
     * @throws CharacterCodingException
     *             if input is malformed, or
     *             if a character is unmappable
     * 
     * @see StatusCode#TWO_HUNDRED
     */
    public static Response json(String json, Charset charset)
            throws CharacterCodingException {
        return create("application/json", json, charset);
    }
    
    /**
     * {@return a new 200 (OK) response with an HTML body}<p>
     * 
     * The content-type header will be set to "application/json".<p>
     * 
     * Encoding works the same as detailed in {@link #text(String, Request)}.
     * 
     * @param json message body
     * @param charsetSource to extract charset from
     * 
     * @throws CharacterCodingException
     *             if input is malformed, or
     *             if a character is unmappable
     * 
     * @see StatusCode#TWO_HUNDRED
     */
    public static Response json(String json, Request charsetSource)
            throws CharacterCodingException {
        return create("application", "json", json, charsetSource);
    }
    
    /**
     * {@return a new 200 (OK) response with the given body}<p>
     * 
     * The content-type will be set to "application/octet-stream".
     * 
     * @param body data
     *
     * @see StatusCode#TWO_HUNDRED
     */
    public static Response ok(ResourceByteBufferIterable body) {
        return ok(body, APPLICATION_OCTET_STREAM);
    }
    
    /**
     * {@return a new 200 (OK) response with the given body}<p>
     * 
     * The given content-type will not be validated. For validation, do
     * <pre>
     *   {@link #ok(ResourceByteBufferIterable, MediaType)
     *       ok}(body, MediaType.{@link MediaType#parse(String) parse}(contentType))
     * </pre>
     * 
     * @param body data
     * @param contentType header value
     * 
     * @see StatusCode#TWO_HUNDRED
     * @see HttpConstants.HeaderName#CONTENT_TYPE
     */
    public static Response ok(
            ResourceByteBufferIterable body, String contentType) {
        return CACHE.get(TWO_HUNDRED, OK).toBuilder()
                .setHeader(CONTENT_TYPE, contentType)
                .body(body)
                .build();
    }
    
    /**
     * {@return a new 200 (OK) response with the given body}
     * 
     * @param body data
     * @param contentType header value
     * 
     * @see StatusCode#TWO_HUNDRED
     * @see HttpConstants.HeaderName#CONTENT_TYPE
     */
    public static Response ok(
            ResourceByteBufferIterable body, MediaType contentType) {
        return ok(body, contentType.toString());
    }
    
    /**
     * {@return a cached 202 (Accepted) response without a body}
     * 
     * @see StatusCode#TWO_HUNDRED_TWO
     */
    public static Response accepted() {
        return CACHE.get(TWO_HUNDRED_TWO, ACCEPTED);
    }
    
    /**
     * {@return a cached 204 (No Content) response without a body}
     * 
     * @see StatusCode#TWO_HUNDRED_FOUR
     */
    public static Response noContent() {
        return CACHE.get(TWO_HUNDRED_FOUR, NO_CONTENT);
    }
    
    /**
     * {@return a cached 400 (Bad Request) response without a body}<p>
     * 
     * The returned response contains the header "Connection: close" which will
     * cause the connection to gracefully close (see {@link ClientChannel}).
     * 
     * @see StatusCode#FOUR_HUNDRED
     */
    public static Response badRequest() {
        return CACHE.get(FOUR_HUNDRED, BAD_REQUEST);
    }
    
    /**
     * {@return a cached 403 (Forbidden) response without a body}
     * 
     * @see StatusCode#FOUR_HUNDRED_THREE
     */
    public static Response forbidden() {
        return CACHE.get(FOUR_HUNDRED_THREE, FORBIDDEN);
    }
    
    /**
     * {@return a cached 404 (Not Found) response without a body}
     * 
     * @see StatusCode#FOUR_HUNDRED_FOUR
     */
    public static Response notFound() {
        return CACHE.get(FOUR_HUNDRED_FOUR, NOT_FOUND);
    }
    
    /**
     * {@return a cached 405 (Method Not Allowed) response without a body}
     * 
     * @see StatusCode#FOUR_HUNDRED_FIVE
     */
    public static Response methodNotAllowed() {
        return CACHE.get(FOUR_HUNDRED_FIVE, METHOD_NOT_ALLOWED);
    }
    
    /**
     * {@return a cached 406 (Not Acceptable) response without a body}
     * 
     * @see StatusCode#FOUR_HUNDRED_SIX
     */
    public static Response notAcceptable() {
        return CACHE.get(FOUR_HUNDRED_SIX, NOT_ACCEPTABLE);
    }

    /**
     * {@return a cached 412 (Precondition Failed) response without a body}
     * 
     * @see StatusCode#FOUR_HUNDRED_TWELVE
     */
    public static Response preconditionFailed() {
        return CACHE.get(FOUR_HUNDRED_TWELVE, PRECONDITION_FAILED);
    }
    
    /**
     * {@return a cached 408 (Request Timeout) response without a body}<p>
     * 
     * There's not much hope that following this response, the client software
     * would algorithmically make up a new request just for the fun of it.
     * Therefore, the returned response contains the header "Connection: close",
     * which will cause the connection to gracefully close (see
     * {@link ClientChannel}).
     * 
     * @see StatusCode#FOUR_HUNDRED_EIGHT
     */
    public static Response requestTimeout() {
        return CACHE.get(FOUR_HUNDRED_EIGHT, REQUEST_TIMEOUT);
    }
    
    /**
     * {@return a cached 413 (Entity Too Large) response without a body}<p>
     * 
     * The returned response contains the header "Connection: close" which will
     * cause the connection to gracefully close (see {@link ClientChannel}).
     * 
     * @see StatusCode#FOUR_HUNDRED_THIRTEEN
     */
    public static Response entityTooLarge() {
        return CACHE.get(FOUR_HUNDRED_THIRTEEN, ENTITY_TOO_LARGE);
    }
    
    /**
     * {@return a cached 418 (I'm a Teapot) response without a body}
     * 
     * @see StatusCode#FOUR_HUNDRED_THIRTEEN
     */
    public static Response teapot() {
        return CACHE.get(FOUR_HUNDRED_EIGHTEEN, IM_A_TEAPOT);
    }
    
    /**
     * {@return a cached 415 (Unsupported Media Type) response without a body}
     * 
     * @see StatusCode#FOUR_HUNDRED_FIFTEEN
     */
    public static Response unsupportedMediaType() {
        return CACHE.get(FOUR_HUNDRED_FIFTEEN, UNSUPPORTED_MEDIA_TYPE);
    }
    
    /**
     * {@return a new 426 (Upgrade Required) response without a body}
     * 
     * @param upgrade header value (proposition for new protocol version)
     * @see StatusCode#FOUR_HUNDRED_TWENTY_SIX
     */
    public static Response upgradeRequired(String upgrade) {
        return CACHE.get(FOUR_HUNDRED_TWENTY_SIX, UPGRADE_REQUIRED).toBuilder()
                .addHeaders(
                    UPGRADE, upgrade,
                    CONNECTION, "upgrade")
                .build();
        /*
         * The server may add a "close" token for a non-persistent connection,
         * which for a humanoid may result in a very weird message:
         * 
         *   Upgrade: HTTP/1.1
         *   Connection: upgrade, close
         * 
         * How can the connection upgrade at the same time we are closing it!?
         * The "upgrade" token is not a command. It simply marks the Upgrade
         * header as a hop-by-hop header, such that a proxy can remove or
         * replace it with his own connection options sent to his client. See
         * RFC 9110 section "7.6.1 Connection" and "7.8 Upgrade".
         */
    }
    
    /**
     * {@return a cached 500 (Internal Server Error) response without a body}
     * 
     * @see StatusCode#FIVE_HUNDRED
     */
    public static Response internalServerError() {
        return CACHE.get(FIVE_HUNDRED, INTERNAL_SERVER_ERROR);
    }
    
    /**
     * {@return a cached 501 (Not Implemented) response without a body}
     * 
     * @see StatusCode#FIVE_HUNDRED_ONE
     */
    public static Response notImplemented() {
        return CACHE.get(FIVE_HUNDRED_ONE, NOT_IMPLEMENTED);
    }
    
    /**
     * {@return a cached 503 (Service Unavailable) response without a body}<p>
     * 
     * The returned response contains the header "Connection: close" which will
     * cause the connection to gracefully close (see {@link ClientChannel}).
     * 
     * @see StatusCode#FIVE_HUNDRED_THREE
     */
    public static Response serviceUnavailable() {
        return CACHE.get(FIVE_HUNDRED_THREE, SERVICE_UNAVAILABLE);
    }
    
    /**
     * {@return a cached 505 (HTTP Version Not Supported) response without a
     * body}
     * 
     * @see StatusCode#FIVE_HUNDRED_FIVE
     */
    public static Response httpVersionNotSupported() {
        return CACHE.get(FIVE_HUNDRED_FIVE, HTTP_VERSION_NOT_SUPPORTED);
    }
    
    
    
    private static Response create(String mime, String body, Charset charset)
            throws CharacterCodingException {
        var bdy = ofString(body, charset);
        var cType = mime + "; charset=" + charset.name().toLowerCase(ROOT);
        return ok(bdy, cType);
    }
    
    private static Response create(
            String mimeType, String mimeSubtype, String body, Request charset)
            throws CharacterCodingException {
        var ch = getOrUTF8(charset, mimeType, mimeSubtype);
        var pub = ofString(body, ch);
        var cType = mimeType + "/" + mimeSubtype
                + "; charset=" + ch.name().toLowerCase(ROOT);
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
    
    private static final CodeAndPhraseCache<Response> CACHE
            = build(Responses::mk, Responses::mk);
    
    private static Response mk(int code) {
        return mk(code, null);
    }
    
    private static Response mk(int code, String phrase) {
        var b = DefaultResponse.DefaultBuilder.ROOT
                .statusCode(code);
        if (phrase != null) {
            b = b.reasonPhrase(phrase);
        }
        if (isClosingConnection(code)) {
            b = b.setHeader(CONNECTION, "close");
        }
        return b.build();
    }
    
    private static boolean isClosingConnection(int code) {
        return code == FOUR_HUNDRED          || // Bad Request
               code == FOUR_HUNDRED_EIGHT    || // Request Timeout
               code == FOUR_HUNDRED_THIRTEEN || // Payload Too Large
               code == FIVE_HUNDRED_THREE;      // Service Unavailable
    }
}