package alpha.nomagichttp;

import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.HttpVersionParseException;
import alpha.nomagichttp.message.HttpVersionRejectedException;
import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;

import java.util.OptionalInt;

import static java.lang.Integer.parseInt;
import static java.util.OptionalInt.empty;
import static java.util.OptionalInt.of;

/**
 * Namespace of constants related to the HTTP protocol.<p>
 * 
 * For values to use in a {@link HeaderKey#CONTENT_TYPE Content-Type} header,
 * see {@link MediaType}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class HttpConstants {
    private HttpConstants() {
        // Empty
    }
    
    /**
     * HTTP methods are included on the first line of a request and indicates
     * the desired action to be performed on a server-side resource. Methods are
     * also known as "verbs", although "OPTIONS" and "HEAD" are not verbs.<p>
     * 
     * Commonly used methods are registered in the
     * <a href="https://www.iana.org/assignments/http-methods">IANA method registry</a>
     * which also features link to RFCs where the methods are defined. The
     * method is required, but the value is a case-sensitive string and can be
     * anything. The method token was originally envisioned as the name of an
     * object method in the back-end (
     * <a href="https://tools.ietf.org/html/rfc7231#section-4.1">RFC 7231 §4.1</a>
     * ).<p>
     * 
     * Methods have characteristics. They are defined as being safe, idempotent
     * and/or cacheable.<p>
     * 
     * If it is <strong>safe</strong> (
     * <a href="https://tools.ietf.org/html/rfc7231#section-4.2.1">RFC 7231 §4.2.1</a>
     * ), then the request is likely used only for information retrieval and
     * should not have any noticeable side effects on the server (GET, HEAD,
     * OPTIONS and TRACE).<p>
     * 
     * If a method is <strong>idempotent</strong> (
     * <a href="https://tools.ietf.org/html/rfc7231#section-4.2.2">RFC 7231 §4.2.2</a>
     * ), then the request may be repeated with no change to the intended effect
     * of the first request (GET, HEAD, PUT, DELETE, OPTIONS and TRACE).<p>
     * 
     * Responses to <strong>cacheable</strong> (
     * <a href="https://tools.ietf.org/html/rfc7231#section-4.2.3">RFC 7231 §4.2.3</a>
     * ) requests may be stored for future reuse (GET, HEAD, and POST), given
     * some other conditions are true (
     * <a href="https://tools.ietf.org/html/rfc7234#section-3">RFC 7234 §3</a>).
     * POST has the additional criterion that it needs explicit freshness
     * information to be cached
     * (<a href="https://tools.ietf.org/html/rfc7231#section-4.3.3">RFC 7231 §4.3.3</a>
     * ). Most caches requires explicit freshness information for all responses
     * no matter the request method. Most caches also don't cache POST at all.
     */
    public static final class Method {
        private Method() {
            // Private
        }
        
        /**
         * Used to retrieve a server resource. The response is usually 200 (OK)
         * with a representation of the resource attached as message body.<p>
         * 
         * The request doesn't normally have a body.<p>
         * 
         * Safe? Yes. Idempotent? Yes. Response cacheable? Yes.
         * 
         * @see Method
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-4.3.1">RFC 7231 §4.3.1</a>
         */
        public static final String GET = "GET";
        
        /**
         * Same as {@link #GET}, except the response must exclude the message
         * body. Used by clients who only have an interest in the response
         * headers, i.e. the resource metadata. For example, to learn when the
         * resource was last modified.<p>
         * 
         * Including a body is not only uninteresting to the client, but it
         * would also effectively kill message framing within the connection
         * since all response headers actually applies to the fictitious
         * would-be response, including headers such as {@code Content-Length}
         * and {@code Transfer-Encoding: chunked}.<p>
         * 
         * Safe? Yes. Idempotent? Yes. Response cacheable? Yes.
         * 
         * @see Method
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-4.3.2">RFC 7231 §4.3.2</a>
         */
        public static final String HEAD = "HEAD";
        
        /**
         * Generally, the POST request is the preferred method of transmitting
         * data to a target processor on the server. Often, the request payload
         * represents a new resource to create and the server responds with a
         * {@link HeaderKey#LOCATION Location} header set which identifies the
         * new resource.<p>
         * 
         * For example, posting an element to a collection. In this case, if the
         * request is repeated the collection grows by 1 element each time.
         * 
         * <pre>
         *   --{@literal >}
         *   POST /user HTTP/1.1
         *   Host: www.example.com
         *   Content-Type: application/json
         *   Content-Length: 31
         *   {"name": "John Doe", "age": 47}
         *   
         *   {@literal <}--
         *   HTTP/1.1 201 Created
         *   Location: /user/123
         *   Content-Length: 0
         * </pre>
         * 
         * Failure to create the resource should probably result in a 4XX
         * (Client Error) or 5XX (Server Error).<p>
         * 
         * It's worthwhile stressing that POST does not have to create a new
         * resource. POST is the general method to use when submitting data
         * in the request body. The data could just as well be appended to an
         * existing resource, submitted as input to a processing algorithm, and
         * so forth.<p>
         * 
         * If the client decides what resource identifier a new resource will
         * get, or the client wish to replace all of an existing [known]
         * resource, use {@link #PUT} instead. If the client wish to replace
         * only parts of an existing [known] resource, use {@link #PATCH}.<p>
         * 
         * Safe? No. Idempotent? No. Response cacheable? Yes.
         * 
         * @see Method
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-4.3.3">RFC 7231 §4.3.3</a>
         */
        public static final String POST = "POST";
        
        /**
         * PUT is used by clients to create or replace the state of an already
         * known resource with that of the message payload (body).<p>
         * 
         * For example, replacing an element in a specific collection. Repeating
         * the request will not grow the collection.<p>
         * 
         * This HTTP exchange example replaces the user created by the example
         * from {@link #POST}:
         * 
         * <pre>
         *   --{@literal >}
         *   PUT /user/123 HTTP/1.1
         *   Host: www.example.com
         *   Content-Type: application/json
         *   Content-Length: 31
         *   {"name": "New Name", "age": 99}
         * 
         *   {@literal <}--
         *   HTTP/1.1 204 No Content
         *   Content-Length: 0
         * </pre>
         * 
         * If the effect of the request created a new resource which did not
         * exist before, then the server should respond 201 (Created).<p>
         * 
         * If the request wish to update only selected parts of a resource, use
         * {@link #PATCH} instead.<p>
         * 
         * Safe? No. Idempotent? Yes. Response cacheable? No.
         * 
         * @see Method
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-4.3.4">RFC 7231 §4.3.4</a>
         */
        public static final String PUT = "PUT";
        
        /**
         * Similar to {@link #PUT}, the PATCH request also updates a known
         * resource except the request body contains a partial update - PUT in
         * contrast, contains the entire modified resource. Another difference
         * is that PATCH is not by definition idempotent, unless the request is
         * made conditional (for example by adding a {@link
         * HeaderKey#IF_MATCH If-Match} header).<p>
         * 
         * Depending on the server endpoint's semantics, a PATCH may just like
         * PUT create a new resource if it didn't already exist. This is
         * probably not very commonly implemented and may from the client's
         * perspective be a confusing and unintended side effect.<p>
         * 
         * The PATCH method is defined in
         * <a href="https://tools.ietf.org/html/rfc5789">RFC 5789</a> which
         * requires the server to execute the patch atomically, but does not
         * specify the syntax of the patch document payload, i.e. how to apply
         * the patch. Two commonly used patch documents are
         * <a href="https://tools.ietf.org/html/rfc6902">RFC 6902 (JSON Patch)</a> and
         * <a href="https://tools.ietf.org/html/rfc7386">RFC 7396 (JSON Merge Patch)</a>
         * . The former being more powerful with embedded operators, the latter
         * being less complex and more easily understood as well as
         * implemented.<p>
         * 
         * Suppose we have a record in our relational database which was created
         * and subsequently edited twice already (version = 3):
         * 
         * <table>
         *   <caption style="display:none">Default Handlers</caption>
         *   <thead>
         *     <tr>
         *       <th>ID</th>
         *       <th>Name</th>
         *       <th>Age</th>
         *       <th>Version</th>
         *     </tr>
         *   </thead>
         *   <tbody>
         *     <tr>
         *       <td>123</td>
         *       <td>John Doe</td>
         *       <td>47</td>
         *       <td>3</td>
         *     </tr>
         *   </tbody>
         * </table><p>
         * 
         * The following example of an HTTP exchange uses JSON Merge Patch to
         * change John's name:
         * 
         * <pre>
         *   --{@literal >}
         *   PATCH /user/123 HTTP/1.1
         *   Host: www.example.com
         *   If-Match: "3"
         *   Content-Type: application/merge-patch+json
         *   Content-Length: 20
         *   {"name": "New Name"}
         * 
         *   {@literal <}--
         *   HTTP/1.1 204 No Content
         *   Content-Length: 0
         * </pre>
         * 
         * The JSON Patch equivalent request could skip the {@code If-Match}
         * header and test the name value instead:
         * 
         * <pre>
         *   PATCH /user/123 HTTP/1.1
         *   Host: www.example.com
         *   Content-Type: application/json-patch+json
         *   Content-Length: 121
         *   [
         *     { "op": "test", "path": "/name", "value": "John Doe" },
         *     { "op": "replace", "path": "/name", "value": "New Name" }
         *   ]
         * </pre>
         * 
         * Safe? No. Idempotent? No. Response cacheable? No.
         * 
         * @see Method
         * @see HeaderKey#ACCEPT_PATCH
         * @see <a href="https://tools.ietf.org/html/rfc5789">RFC 5789</a>
         */
        public static final String PATCH = "PATCH";
        
        /**
         * Used by clients to remove [the public visibility of-] or delete (like
         * permanently; reclaim disk space and shit) a server-side resource.<p>
         * 
         * The request body doesn't normally have contents. The contents of the
         * response body is very dependent on the nature of the resource and
         * operation. For example, <i>remove</i> is probably more likely than
         * <i>delete</i> to return the resource, perhaps limited by resource
         * size.<p>
         * 
         * It is not defined how the operation cascade to multiple
         * representations/content-types.<p>
         * 
         * Safe? No. Idempotent? Yes. Response cacheable? No.
         * 
         * @see Method
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-4.3.5">RFC 7231 §4.3.5</a>
         */
        public static final String DELETE = "DELETE";
        
        /**
         * Used by client-to-proxy or proxy-to-proxy for establishing a tunnel.
         * Most origin servers have no use of this method and doesn't implement
         * it.<p>
         * 
         * The request doesn't normally have a body. The response body must not,
         * because the tunnel connection is supposed to begin after the response
         * headers.<p>
         * 
         * Safe? No. Idempotent? No. Response cacheable? No.
         * 
         * @see Method
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-4.3.6">RFC 7231 §4.3.6</a>
         */
        public static final String CONNECT = "CONNECT";
        
        /**
         * Used by client to probe the capabilities of a server. The request
         * target may be an asterisk ("*") and applies then to the entire server
         * in general. The request and response may or may not have body
         * contents, this depends on the implementation.<p>
         * 
         * In the following HTTP exchange, the client discovers what methods are
         * allowed for the user identified as 123:
         * 
         * <pre>
         *   --{@literal >}
         *   OPTIONS /user/123 HTTP/1.1
         *   Host: www.example.com
         *   Content-Length: 0
         * 
         *   {@literal <}--
         *   HTTP/1.1 204 No Content
         *   Allow: OPTIONS, GET, HEAD, PATCH
         *   Content-Length: 0
         * </pre>
         * 
         * Future work is scheduled to have the NoMagicHTTP server automate the
         * discovery of allowed methods.<p>
         * 
         * Ping-pong example:
         * 
         * <pre>
         *   --{@literal >}
         *   OPTIONS * HTTP/1.1
         *   Host: www.example.com
         *   Content-Length: 0
         * 
         *   {@literal <}--
         *   HTTP/1.1 200 OK
         *   Content-Length: 4
         *   PONG
         * </pre>
         * 
         * Safe? Yes. Idempotent? Yes. Response cacheable? No.
         * 
         * @see Method
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-4.3.7">RFC 7231 §4.3.7</a>
         */
        public static final String OPTIONS = "OPTIONS";
        
        /**
         * This method echoes the request headers back to the client in a
         * response body with {@code Content-Type: message/http}. Hence it
         * doesn't allow the request itself to contain a request body as this
         * wouldn't serve a purpose.<p>
         * 
         * Clients using TRACE are usually interested in tracing the request
         * chain, of particular interest to this effect is the echoed {@link
         * HeaderKey#VIA Via} header as it will list all intermediaries.
         * 
         * Both client and server should exclude passing sensitive data, such as
         * cookies with user credentials. Due to the inherit security risk of
         * leaking such sensitive data, some HTTP applications disable the TRACE
         * method. Future work is scheduled to have this option available in the
         * NoMagicHTTP's configuration as well. The NoMagicHTTP server certainly
         * has no automatic response behavior related to this method and most
         * likely never will.<p>
         * 
         * Safe? Yes. Idempotent? Yes. Response cacheable? No.
         * 
         * @see Method
         * @see HeaderKey#MAX_FORWARDS
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-4.3.8">RFC 7231 §4.3.8</a>
         */
        public static final String TRACE = "TRACE";
    }
    
    /**
     * A status code is a three-digit integer value on the response status line,
     * giving the result of the processed request. They are classified into five
     * groups, as indicated by the first digit:
     * 
     * <ul>
     *   <li>1XX (Informational): Alo known a interim responses, are sent before
     *     the final response. May be used as a heart-beat or status-update
     *     mechanism by servers processing lengthy requests.</li>
     *   <li>2XX (Successful): The request was successfully received and
     *     accepted.</li>
     *   <li>3XX (Redirection): The resource is available using a different
     *     request-target or does not need to be re-transmitted.</li>
     *   <li>4XX (Client Error): The client fuct up. Likely due to request
     *     syntax error or the request was rejected for whatever else
     *     reason.</li>
     *   <li>5XX (Server Error): The server fuct up. Likely due to inadequate
     *     software quality.</li>
     * </ul>
     * 
     * Clients are only required to understand the class of a status code,
     * leaving the server free to select any subcode it so desires. IANA
     * maintains a
     * <a href="https://www.iana.org/assignments/http-status-codes/http-status-codes.xhtml">registry</a>
     * with status codes. The constants provided in {@code StatusCode} is
     * derived from
     * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_status_codes">Wikipedia</a>
     * and includes unofficial codes such as 418 (I'm a Teapot).<p>
     * 
     * A status code that is defined as being cacheable may be cached without
     * explicit freshness information whereas status codes not defined as
     * cacheable may still be cached, but requires explicit freshness
     * information. In addition, some other conditions must also be true (
     * <a href="https://tools.ietf.org/html/rfc7234#section-3">RFC 7234 §3</a>
     * ). Codes defined by
     * <a href="https://tools.ietf.org/html/rfc7231#section-6.1">RFC 7231 §6.1</a>
     * as cacheable: 200, 203, 204, 206, 300, 301, 404, 405, 410, 414, and
     * 501. Other status codes may well also be defined as cacheable, the
     * current JavaDoc author simply gave up trying to find out.<p>
     * 
     * {@link Response.Builder#build()} requires a status code to have been set,
     * but as far as the NoMagicHTTP server and API are concerned, the value may
     * be any integer, even if it does not belong to a known group or does not
     * contain exactly three digits.<p>
     * 
     * Most applications will not need to set a status code explicitly, as it
     * will be set by {@link Responses response factory methods}.
     */
    public static final class StatusCode {
        private StatusCode() {
            // Private
        }
        
        /**
         * {@value} {@value ReasonPhrase#CONTINUE}.<p>
         * 
         * The server has received the request headers and accepted them, the
         * client may proceed to transmit the request body. Used by clients to
         * invoke an artificial pause in order to avoid transmitting data in
         * case the request would have been rejected anyways. The client must
         * signal his pause by including an {@link HeaderKey#EXPECT Expect:
         * 100-continue} header in the request.<p>
         * 
         * Currently, the NoMagicHTTP server does not auto-respond a 101 interim
         * response to a waiting client, although future work is planned to
         * arrange for this in a smart, transparent and configurable way.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.2.1">RFC 7231 §6.2.1</a>
         */
        public static final int ONE_HUNDRED = 100;
        
        /**
         * {@value} {@value ReasonPhrase#SWITCHING_PROTOCOLS}.<p>
         * 
         * The server accepted a protocol upgrade request from the client and
         * should also include in the {@link HeaderKey#UPGRADE Upgrade} header
         * which protocol will be used immediately after the empty line that
         * terminates the 101 interim response.<p>
         * 
         * This example is an upgrade sequence for HTTP/2:
         * 
         * <pre>
         *   --{@literal >}
         *   GET /index.html HTTP/1.1
         *   Host: www.example.com
         *   Connection: Upgrade, HTTP2-Settings
         *   Upgrade: h2c
         *   HTTP2-Settings: {@literal <}base64url encoding of HTTP/2 SETTINGS payload{@literal >}
         *   
         *   {@literal <}--
         *   HTTP/1.1 101 Switching Protocols
         *   Connection: Upgrade
         *   Upgrade: h2c
         *   
         *   [ HTTP/2 connection ...
         * </pre>
         * 
         * Currently, the NoMagicHTTP server ignores the {@code Upgrade} header
         * and supports only HTTP/1.0 and HTTP/1.1. Planned future work will
         * also support other protocols, such as HTTP/2.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.2.2">RFC 7231 §6.2.2</a>
         */
        public static final int ONE_HUNDRED_ONE = 101;
        
        /**
         * {@value} {@value ReasonPhrase#PROCESSING}.<p>
         * 
         * Sent as one or many interim responses for when processing of a fully
         * received request takes time. Effectively stops a client from timing
         * out and assuming that the request was lost. Note that for the reason
         * of stopping timeouts alone, there is no reason to send the client a
         * processing update while the request is in-flight.<p>
         * 
         * The NoMagicHTTP server does not send processing interim responses and
         * it isn't likely to ever be supported through global configuration.
         * A server that indiscriminately send such updates would effectively
         * kill timeout mechanisms put in various points of the HTTP chain for a
         * reason. A request handler that lingers and who is alive, you know,
         * doing well, ought to update the client accordingly. However, the API
         * will be extended in the future to support discovering/creating
         * processing responses.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc2518#section-10.1">RFC 2518 §10.1</a>
         */
        public static final int ONE_HUNDRED_TWO = 102;
        
        /**
         * {@value} {@value ReasonPhrase#EARLY_HINTS}.<p>
         * 
         * Used to pre-flight response headers to the client before the final
         * response. Headers on this interim response applies only to the final
         * response, not to the interim response itself or any subsequent
         * interim responses (of which some may be even more early hints).<p>
         * 
         * The following example hints the client (or an intermediary cache) to
         * pre-load some resources that the server knows will have to be
         * requested by the client in order to render the final view:
         * 
         * <pre>
         *   --{@literal >}
         *   GET /slow/page.html HTTP/1.1
         *   Host: www.example.com
         *   
         *   {@literal <}--
         *   HTTP/1.1 103 Early Hints
         *   Link: {@literal <}/style.css{@literal >}; rel=preload; as=style
         *   Link: {@literal <}/script.js{@literal >}; rel=preload; as=script
         *   
         *   HTTP/1.1 200 OK
         *   Date: Fri, 26 May 2017 10:02:11 GMT
         *   Content-Length: 1234
         *   Content-Type: text/html; charset=utf-8
         *   Link: {@literal <}/style.css{@literal >}; rel=preload; as=style
         *   Link: {@literal <}/script.js{@literal >}; rel=preload; as=script
         *   
         *   {@literal <}!doctype html{@literal >}
         *   [ ...
         * </pre>
         * 
         * As <i>hints</i>, the pre-flight headers may or may not apply to the
         * final response and they may or may not be repeated in the final
         * response.<p> 
         * 
         * HTTP/2 Server Push does not obsolete early hints. In fact, early
         * hints may even be a better option as Server Push only works for
         * resources which the server itself has access to. Also, Server Push
         * will force the transmission of those resources while early hints is
         * just a hint; a client who already has the resource is not forced to
         * request it again.<p>
         * 
         * There's no need to use early hints should the final response already
         * be immediately available.
         * 
         * @see HeaderKey#LINK
         * @see <a href="https://tools.ietf.org/html/rfc8297">RFC 8297</a>
         */
        public static final int ONE_HUNDRED_THREE = 103;
        
        /**
         * {@value} {@value ReasonPhrase#OK}.<p>
         * 
         * Standard code to use for successful HTTP requests.<p>
         * 
         * The response typically has a body; whatever was requested by {@link
         * Method#GET}. If the response does not contain a body, consider using
         * {@value #TWO_HUNDRED_FOUR}.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.3.1">RFC 7231 §6.3.1</a>
         */
        public static final int TWO_HUNDRED = 200;
        
        /**
         * {@value} {@value ReasonPhrase#CREATED}.<p>
         * 
         * A new resource was created.<p>
         * 
         * The response typically has a body containing the new resource. A good
         * reason for not including the body would be if the request already
         * contained the entire resource.
         * 
         * @see HttpConstants.Method#POST
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.3.2">RFC 7231 §6.3.2</a>
         */
        public static final int TWO_HUNDRED_ONE = 201;
        
        /**
         * {@value} {@value ReasonPhrase#ACCEPTED}.<p>
         * 
         * The server accepted the request for processing, but processing is
         * still ongoing or has not yet begun.<p>
         * 
         * This status code is often used when a client has submitted a lengthy
         * task or the submitted task will execute at some point in the future;
         * decoupled from the initiating request.<p>
         * 
         * The client ought to have other means by which to track the task
         * status- and progress. The response body is a good candidate for
         * passing such metadata to the client.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.3.3">RFC 7231 §6.3.3</a>
         */
        public static final int TWO_HUNDRED_TWO = 202;
        
        /**
         * {@value} {@value ReasonPhrase#NON_AUTHORITATIVE_INFORMATION}.<p>
         * 
         * Complicated. TODO.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.3.4">RFC 7231 §6.3.4</a>
         */
        public static final int TWO_HUNDRED_THREE = 203;
        
        /**
         * {@value} {@value ReasonPhrase#NO_CONTENT}.<p>
         * 
         * Standard code to use for successful HTTP requests, when the response
         * does <i>not</i> have a body. Also used as response to a {@code PUT}
         * request if the request modified an already existing resource.<p>
         * 
         * If the response should contain a body, consider using
         * {@link #TWO_HUNDRED}.
         * 
         * @see HttpConstants.Method#PUT
         * @see HttpConstants.Method#PATCH
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.3.5">RFC 7231 §6.3.5</a>
         */
        public static final int TWO_HUNDRED_FOUR = 204;
        
        /**
         * {@value} {@value ReasonPhrase#RESET_CONTENT}.<p>
         *
         * "The 205 (Reset Content) status code indicates that the server has
         * fulfilled the request and desires that the user agent reset the
         * 'document view', which caused the request to be sent, to its original
         * state as received from the origin server." (
         * <a href="https://tools.ietf.org/html/rfc7231#section-6.3.6">RFC 7231 §6.3.6</a>
         * )
         */
        public static final int TWO_HUNDRED_FIVE = 205;
        
        /**
         * {@value} {@value ReasonPhrase#PARTIAL_CONTENT}.<p>
         * 
         * Used to serve a byte range; often resumed file downloads. The
         * NoMagicHTTP API currently has no first-class support for byte
         * serving. Future planned work will add this.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7233#section-4.1">RFC 7233 §4.1</a>
         */
        public static final int TWO_HUNDRED_SIX = 206;
        
        /**
         * {@value} {@value ReasonPhrase#MULTI_STATUS}.<p>
         * 
         * Can be used to respond the status for multiple resources, as embedded
         * in the XML representation.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc4918#section-13">RFC 4918 §13</a>
         */
        public static final int TWO_HUNDRED_SEVEN = 207;
        
        /**
         * {@value} {@value ReasonPhrase#ALREADY_REPORTED}.<p>
         * 
         * Complicated. TODO.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc5842#section-7.1">RFC 5842 §7.1</a>
         */
        public static final int TWO_HUNDRED_EIGHT = 208;
        
        /**
         * {@value} {@value ReasonPhrase#IM_USED}.<p>
         * 
         * Complicated. TODO.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc3229#section-10.4.1">RFC 3229 §10.4.1</a>
         */
        public static final int TWO_HUNDRED_TWENTY_SIX = 226;
        
        /**
         * {@value} {@value ReasonPhrase#MULTIPLE_CHOICES}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.4.1">RFC 7231 §6.4.1</a>
         */
        public static final int THREE_HUNDRED = 300;
        
        /**
         * {@value} {@value ReasonPhrase#MOVED_PERMANENTLY}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.4.2">RFC 7231 §6.4.2</a>
         */
        public static final int THREE_HUNDRED_ONE = 301;
        
        /**
         * {@value} {@value ReasonPhrase#FOUND}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.4.3">RFC 7231 §6.4.3</a>
         */
        public static final int THREE_HUNDRED_TWO = 302;
        
        /**
         * {@value} {@value ReasonPhrase#SEE_OTHER}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.4.4">RFC 7231 §6.4.4</a>
         */
        public static final int THREE_HUNDRED_THREE = 303;
        
        /**
         * {@value} {@value ReasonPhrase#NOT_MODIFIED}.<p>
         * 
         * TODO: write something
         * 
         * @see HeaderKey#IF_MODIFIED_SINCE
         * @see <a href="https://tools.ietf.org/html/rfc7232#section-4.1">RFC 7232 §4.1</a>
         */
        public static final int THREE_HUNDRED_FOUR = 304;
        
        /**
         * {@value} {@value ReasonPhrase#USE_PROXY}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.4.5">RFC 7231 §6.4.5</a>
         */
        public static final int THREE_HUNDRED_FIVE = 305;
        
        /**
         * {@value} {@value ReasonPhrase#TEMPORARY_REDIRECT}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.4.7">RFC 7231 §6.4.7</a>
         */
        public static final int THREE_HUNDRED_SEVEN = 307;
        
        /**
         * {@value} {@value ReasonPhrase#PERMANENT_REDIRECT}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7538#section-3">RFC 7538 §3</a>
         */
        public static final int THREE_HUNDRED_EIGHT = 308;
        
        /**
         * {@value} {@value ReasonPhrase#BAD_REQUEST}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.5.1">RFC 7531 §6.5.1</a>
         */
        public static final int FOUR_HUNDRED = 400;
        
        /**
         * {@value} {@value ReasonPhrase#UNAUTHORIZED}.<p>
         * 
         * TODO: write something
         * 
         * @see HeaderKey#WWW_AUTHENTICATE
         * @see <a href="https://tools.ietf.org/html/rfc7235#section-3.1">RFC 7235 §3.1</a>
         */
        public static final int FOUR_HUNDRED_ONE = 401;
        
        /**
         * {@value} {@value ReasonPhrase#PAYMENT_REQUIRED}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.5.2">RFC 7231 §6.5.2</a>
         */
        public static final int FOUR_HUNDRED_TWO = 402;
        
        /**
         * {@value} {@value ReasonPhrase#FORBIDDEN}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.5.3">RFC 7231 §6.5.3</a>
         */
        public static final int FOUR_HUNDRED_THREE = 403;
        
        /**
         * {@value} {@value ReasonPhrase#NOT_FOUND}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.5.4">RFC 7231 §6.5.4</a>
         */
        public static final int FOUR_HUNDRED_FOUR = 404;
        
        /**
         * {@value} {@value ReasonPhrase#METHOD_NOT_ALLOWED}.<p>
         * 
         * TODO: write something
         * 
         * @see HeaderKey#ALLOW
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.5.5">RFC 7231 §6.5.5</a>
         */
        public static final int FOUR_HUNDRED_FIVE = 405;
        
        /**
         * {@value} {@value ReasonPhrase#NOT_ACCEPTABLE}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.5.6">RFC 7231 §6.5.6</a>
         */
        public static final int FOUR_HUNDRED_SIX = 406;
        
        /**
         * {@value} {@value ReasonPhrase#PROXY_AUTHENTICATION_REQUIRED}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7235#section-3.2">RFC 7235 §3.2</a>
         */
        public static final int FOUR_HUNDRED_SEVEN = 407;
        
        /**
         * {@value} {@value ReasonPhrase#REQUEST_TIMEOUT}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.5.7">RFC 7231 §6.5.7</a>
         */
        public static final int FOUR_HUNDRED_EIGHT = 408;
        
        /**
         * {@value} {@value ReasonPhrase#CONFLICT}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.5.8">RFC 7231 §6.5.8</a>
         */
        public static final int FOUR_HUNDRED_NINE = 409;
        
        /**
         * {@value} {@value ReasonPhrase#GONE}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.5.9">RFC 7231 §6.5.9</a>
         */
        public static final int FOUR_HUNDRED_TEN = 410;
        
        /**
         * {@value} {@value ReasonPhrase#LENGTH_REQUIRED}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.5.10">RFC 7231 §6.5.10</a>
         */
        public static final int FOUR_HUNDRED_ELEVEN = 411;
        
        /**
         * {@value} {@value ReasonPhrase#PRECONDITION_FAILED}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7232#section-4.2">RFC 7232 §4.2</a>
         */
        public static final int FOUR_HUNDRED_TWELVE = 412;
        
        /**
         * {@value} {@value ReasonPhrase#PAYLOAD_TOO_LARGE}.<p>
         * 
         * May also alternatively be used with phrase {@value
         * ReasonPhrase#ENTITY_TOO_LARGE}, which many servers chose to do as to
         * not specifically single out the message body as being the offending
         * part.<p>
         * 
         * TODO: write more
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.5.11">RFC 7231 §6.5.11</a>
         */
        public static final int FOUR_HUNDRED_THIRTEEN = 413;
        
        /**
         * {@value} {@value ReasonPhrase#URI_TOO_LONG}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.5.12">RFC 7231 §6.5.12</a>
         */
        public static final int FOUR_HUNDRED_FOURTEEN = 414;
        
        /**
         * {@value} {@value ReasonPhrase#UNSUPPORTED_MEDIA_TYPE}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.5.13">RFC 7231 §6.5.13</a>
         */
        public static final int FOUR_HUNDRED_FIFTEEN = 415;
        
        /**
         * {@value} {@value ReasonPhrase#RANGE_NOT_SATISFIABLE}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7233#section-4.4">RFC 7233 §4.4</a>
         */
        public static final int FOUR_HUNDRED_SIXTEEN = 416;
        
        /**
         * {@value} {@value ReasonPhrase#EXPECTATION_FAILED}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.5.14">RFC 7231 §6.5.14</a>
         */
        public static final int FOUR_HUNDRED_SEVENTEEN = 417;
        
        /**
         * {@value} {@value ReasonPhrase#IM_A_TEAPOT}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7168#section-2.3.3">RFC 7168 §2.3.3</a>
         */
        public static final int FOUR_HUNDRED_EIGHTEEN = 418;
        
        /**
         * {@value} {@value ReasonPhrase#MISDIRECTED_REQUEST}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7540#section-9.1.2">RFC 7540 §9.1.2</a>
         */
        public static final int FOUR_HUNDRED_TWENTY_ONE = 421;
        
        /**
         * {@value} {@value ReasonPhrase#UNPROCESSABLE_ENTITY}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc4918#section-11.2">RFC 4918 §11.2</a>
         */
        public static final int FOUR_HUNDRED_TWENTY_TWO = 422;
        
        /**
         * {@value} {@value ReasonPhrase#LOCKED}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc4918#section-11.3">RFC 4918 §11.3</a>
         */
        public static final int FOUR_HUNDRED_TWENTY_THREE = 423;
        
        /**
         * {@value} {@value ReasonPhrase#FAILED_DEPENDENCY}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc4918#section-11.4">RFC 4918 §11.4</a>
         */
        public static final int FOUR_HUNDRED_TWENTY_FOUR = 424;
        
        /**
         * {@value} {@value ReasonPhrase#TOO_EARLY}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc8470#section-5.2">RFC 8470 §5.2</a>
         */
        public static final int FOUR_HUNDRED_TWENTY_FIVE = 425;
        
        /**
         * {@value} {@value ReasonPhrase#UPGRADE_REQUIRED}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.5.15">RFC 7231 §6.5.15</a>
         */
        public static final int FOUR_HUNDRED_TWENTY_SIX = 426;
        
        /**
         * {@value} {@value ReasonPhrase#PRECONDITION_REQUIRED}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc6585#section-3">RFC 6585 §3</a>
         */
        public static final int FOUR_HUNDRED_TWENTY_EIGHT = 428;
        
        /**
         * {@value} {@value ReasonPhrase#TOO_MANY_REQUESTS}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc6585#section-4">RFC 6585 §4</a>
         */
        public static final int FOUR_HUNDRED_TWENTY_NINE = 429;
        
        /**
         * {@value} {@value ReasonPhrase#REQUEST_HEADER_FIELDS_TOO_LARGE}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc6585#section-5">RFC 6585 §5</a>
         */
        public static final int FOUR_HUNDRED_THIRTY_ONE = 431;
        
        /**
         * {@value} {@value ReasonPhrase#UNAVAILABLE_FOR_LEGAL_REASONS}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7725#section-3">RFC 7725 §3</a>
         */
        public static final int FOUR_HUNDRED_FIFTY_ONE = 451;
        
        /**
         * {@value} {@value ReasonPhrase#INTERNAL_SERVER_ERROR}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.6.1">RFC 7731 §6.6.1</a>
         */
        public static final int FIVE_HUNDRED = 500;
        
        /**
         * {@value} {@value ReasonPhrase#NOT_IMPLEMENTED}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.6.2">RFC 7731 §6.6.2</a>
         */
        public static final int FIVE_HUNDRED_ONE = 501;
        
        /**
         * {@value} {@value ReasonPhrase#BAD_GATEWAY}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.6.3">RFC 7731 §6.6.3</a>
         */
        public static final int FIVE_HUNDRED_TWO = 502;
        
        /**
         * {@value} {@value ReasonPhrase#SERVICE_UNAVAILABLE}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.6.4">RFC 7731 §6.6.4</a>
         */
        public static final int FIVE_HUNDRED_THREE = 503;
        
        /**
         * {@value} {@value ReasonPhrase#GATEWAY_TIMEOUT}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.6.5">RFC 7731 §6.6.5</a>
         */
        public static final int FIVE_HUNDRED_FOUR = 504;
        
        /**
         * {@value} {@value ReasonPhrase#HTTP_VERSION_NOT_SUPPORTED}.<p>
         *
         * TODO: write something
         *
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-6.6.6">RFC 7731 §6.6.6</a>
         */
        public static final int FIVE_HUNDRED_FIVE = 505;
        
        /**
         * {@value} {@value ReasonPhrase#VARIANT_ALSO_NEGOTIATES}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc2295#section-8.1">RFC 2295 §8.1</a>
         */
        public static final int FIVE_HUNDRED_SIX = 506;
        
        /**
         * {@value} {@value ReasonPhrase#INSUFFICIENT_STORAGE}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc4918#section-11.5">RFC 4918 §11.5</a>
         */
        public static final int FIVE_HUNDRED_SEVEN = 507;
        
        /**
         * {@value} {@value ReasonPhrase#LOOP_DETECTED}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc5842#section-7.2">RFC 5842 §7.2</a>
         */
        public static final int FIVE_HUNDRED_EIGHT = 508;
        
        /**
         * {@value} {@value ReasonPhrase#NOT_EXTENDED}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc2774#section-7">RFC 2774 §7</a>
         */
        public static final int FIVE_HUNDRED_TEN = 510;
        
        /**
         * {@value} {@value ReasonPhrase#NETWORK_AUTHENTICATION_REQUIRED}.<p>
         * 
         * TODO: write something
         * 
         * @see <a href="https://tools.ietf.org/html/rfc6585#section-6">RFC 6585 §6</a>
         */
        public static final int FIVE_HUNDRED_ELEVEN = 511;
    }
    
    /**
     * "The reason-phrase element exists for the sole purpose of providing a
     * textual description associated with the numeric status code, mostly out
     * of deference to earlier Internet application protocols that were more
     * frequently used with interactive text clients.  A client SHOULD ignore
     * the reason-phrase content." (
     * <a href="https://tools.ietf.org/html/rfc7230#section-3.1.2">RFC 7230 §3.1.2</a>
     * )<p>
     * 
     * Most applications will not need to set a reason phrase explicitly, as it
     * will be set implicitly by {@link Responses response factory methods}.
     */
    public static final class ReasonPhrase {
        private ReasonPhrase() {
            // Private
        }
        
        /** {@value} is the default used by NoMagicHTTP when no other phrase has been given. */
        public static final String UNKNOWN = "Unknown";
        
        /** Goes with status code {@value StatusCode#ONE_HUNDRED}. */
        public static final String CONTINUE = "Continue";
        
        /** Goes with status code {@value StatusCode#ONE_HUNDRED_ONE}. */
        public static final String SWITCHING_PROTOCOLS = "Switching Protocols";
        
        /** Goes with status code {@value StatusCode#ONE_HUNDRED_TWO}. */
        public static final String PROCESSING = "Processing";
        
        /** Goes with status code {@value StatusCode#ONE_HUNDRED_THREE}. */
        public static final String EARLY_HINTS = "Early Hints";
        
        /** Goes with status code {@value StatusCode#TWO_HUNDRED}. */
        public static final String OK = "OK";
        
        /** Goes with status code {@value StatusCode#TWO_HUNDRED_ONE}. */
        public static final String CREATED = "Created";
        
        /** Goes with status code {@value StatusCode#TWO_HUNDRED_TWO}. */
        public static final String ACCEPTED = "Accepted";
        
        /** Goes with status code {@value StatusCode#TWO_HUNDRED_THREE}. */
        public static final String NON_AUTHORITATIVE_INFORMATION = "Non-Authoritative Information";
        
        /** Goes with status code {@value StatusCode#TWO_HUNDRED_FOUR}. */
        public static final String NO_CONTENT = "No Content";
        
        /** Goes with status code {@value StatusCode#TWO_HUNDRED_FIVE}. */
        public static final String RESET_CONTENT = "Reset Content";
        
        /** Goes with status code {@value StatusCode#TWO_HUNDRED_SIX}. */
        public static final String PARTIAL_CONTENT = "Partial Content";
        
        /** Goes with status code {@value StatusCode#TWO_HUNDRED_SEVEN}. */
        public static final String MULTI_STATUS = "Multi-Status";
        
        /** Goes with status code {@value StatusCode#TWO_HUNDRED_EIGHT}. */
        public static final String ALREADY_REPORTED = "Already Reported";
        
        /** Goes with status code {@value StatusCode#TWO_HUNDRED_TWENTY_SIX}. */
        public static final String IM_USED = "IM Used";
        
        /** Goes with status code {@value StatusCode#THREE_HUNDRED}. */
        public static final String MULTIPLE_CHOICES = "Multiple Choices";
        
        /** Goes with status code {@value StatusCode#THREE_HUNDRED_ONE}. */
        public static final String MOVED_PERMANENTLY = "Moved Permanently";
        
        /** Goes with status code {@value StatusCode#THREE_HUNDRED_TWO}. */
        public static final String FOUND = "Found";
        
        /** Goes with status code {@value StatusCode#THREE_HUNDRED_THREE}. */
        public static final String SEE_OTHER = "See Other";
        
        /** Goes with status code {@value StatusCode#THREE_HUNDRED_FOUR}. */
        public static final String NOT_MODIFIED = "Not Modified";
        
        /** Goes with status code {@value StatusCode#THREE_HUNDRED_FIVE}. */
        public static final String USE_PROXY = "Use Proxy";
        
        /** Goes with status code {@value StatusCode#THREE_HUNDRED_SEVEN}. */
        public static final String TEMPORARY_REDIRECT = "Temporary Redirect";
        
        /** Goes with status code {@value StatusCode#THREE_HUNDRED_EIGHT}. */
        public static final String PERMANENT_REDIRECT = "Permanent Redirect";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED}. */
        public static final String BAD_REQUEST = "Bad Request";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_ONE}. */
        public static final String UNAUTHORIZED = "Unauthorized";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_TWO}. */
        public static final String PAYMENT_REQUIRED = "Payment Required";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_THREE}. */
        public static final String FORBIDDEN = "Forbidden";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_FOUR}. */
        public static final String NOT_FOUND = "Not Found";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_FIVE}. */
        public static final String METHOD_NOT_ALLOWED = "Method Not Allowed";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_SIX}. */
        public static final String NOT_ACCEPTABLE = "Not Acceptable";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_SEVEN}. */
        public static final String PROXY_AUTHENTICATION_REQUIRED = "Proxy Authentication Required";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_EIGHT}. */
        public static final String REQUEST_TIMEOUT = "Request Timeout";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_NINE}. */
        public static final String CONFLICT = "Conflict";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_TEN}. */
        public static final String GONE = "Gone";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_ELEVEN}. */
        public static final String LENGTH_REQUIRED = "Length Required";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_TWELVE}. */
        public static final String PRECONDITION_FAILED = "Precondition Failed";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_THIRTEEN}. */
        public static final String PAYLOAD_TOO_LARGE = "Payload Too Large";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_THIRTEEN}. */
        public static final String ENTITY_TOO_LARGE = "Entity Too Large";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_FOURTEEN}. */
        public static final String URI_TOO_LONG = "URI Too Long";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_FIFTEEN}. */
        public static final String UNSUPPORTED_MEDIA_TYPE = "Unsupported Media Type";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_SIXTEEN}. */
        public static final String RANGE_NOT_SATISFIABLE = "Range Not Satisfiable";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_SEVENTEEN}. */
        public static final String EXPECTATION_FAILED = "Expectation Failed";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_EIGHTEEN}. */
        public static final String IM_A_TEAPOT = "I'm a Teapot";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_TWENTY_ONE}. */
        public static final String MISDIRECTED_REQUEST = "Misdirected Request";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_TWENTY_TWO}. */
        public static final String UNPROCESSABLE_ENTITY = "Unprocessable Entity";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_TWENTY_THREE}. */
        public static final String LOCKED = "Locked";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_TWENTY_FOUR}. */
        public static final String FAILED_DEPENDENCY = "Failed Dependency";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_TWENTY_FIVE}. */
        public static final String TOO_EARLY = "Too Early";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_TWENTY_SIX}. */
        public static final String UPGRADE_REQUIRED = "Upgrade Required";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_TWENTY_EIGHT}. */
        public static final String PRECONDITION_REQUIRED = "Precondition Required";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_TWENTY_NINE}. */
        public static final String TOO_MANY_REQUESTS = "Too Many Requests";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_THIRTY_ONE}. */
        public static final String REQUEST_HEADER_FIELDS_TOO_LARGE = "Request Header Fields Too Large";
        
        /** Goes with status code {@value StatusCode#FOUR_HUNDRED_FIFTY_ONE}. */
        public static final String UNAVAILABLE_FOR_LEGAL_REASONS = "Unavailable For Legal Reasons";
        
        /** Goes with status code {@value StatusCode#FIVE_HUNDRED}. */
        public static final String INTERNAL_SERVER_ERROR = "Internal Server Error";
        
        /** Goes with status code {@value StatusCode#FIVE_HUNDRED_ONE}. */
        public static final String NOT_IMPLEMENTED = "Not Implemented";
        
        /** Goes with status code {@value StatusCode#FIVE_HUNDRED_TWO}. */
        public static final String BAD_GATEWAY = "Bad Gateway";
        
        /** Goes with status code {@value StatusCode#FIVE_HUNDRED_THREE}. */
        public static final String SERVICE_UNAVAILABLE = "Service Unavailable";
        
        /** Goes with status code {@value StatusCode#FIVE_HUNDRED_FOUR}. */
        public static final String GATEWAY_TIMEOUT = "Gateway Timeout";
        
        /** Goes with status code {@value StatusCode#FIVE_HUNDRED_FIVE}. */
        public static final String HTTP_VERSION_NOT_SUPPORTED = "HTTP Version Not Supported";
        
        /** Goes with status code {@value StatusCode#FIVE_HUNDRED_SIX}. */
        public static final String VARIANT_ALSO_NEGOTIATES = "Variant Also Negotiates";
        
        /** Goes with status code {@value StatusCode#FIVE_HUNDRED_SEVEN}. */
        public static final String INSUFFICIENT_STORAGE = "Insufficient Storage";
        
        /** Goes with status code {@value StatusCode#FIVE_HUNDRED_EIGHT}. */
        public static final String LOOP_DETECTED = "Loop Detected";
        
        /** Goes with status code {@value StatusCode#FIVE_HUNDRED_TEN}. */
        public static final String NOT_EXTENDED = "Not Extended";
        
        /** Goes with status code {@value StatusCode#FIVE_HUNDRED_ELEVEN}. */
        public static final String NETWORK_AUTHENTICATION_REQUIRED = "Network Authentication Required";
    }
    
    /**
     * Constants for header keys (also known as header field names).<p>
     * 
     * May be useful when reading headers {@linkplain Request#headers() from a
     * request} or {@linkplain Response.Builder building a response}.<p>
     * 
     * The constants provided in this class are mostly derived from
     * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>.
     * 
     * @see <a href="https://tools.ietf.org/html/rfc7230#section-3.2">RFC 7230 §3.2</a>
     */
    public static final class HeaderKey {
        private HeaderKey() {
            // Private
        }
        
        /**
         * "Acceptable instance-manipulations for the request." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Example: {@code A-IM: feed}<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc3229#section-10.5.3">RFC 3229 §10.5.3</a>
         */
        public static final String A_IM = "A-IM";
        
        /**
         * Used by client when driving content/representation negotiation.<p>
         * 
         * Specifies what media type(s) the client accepts in response, and
         * possibly, an ordered preference.
         * 
         * Example: {@code Accept: text/html}
         * 
         * @see RequestHandler
         * @see #VARY
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-5.3.2">RFC 7231 §5.3.2</a>
         */
        public static final String ACCEPT = "Accept";
        
        /**
         * "Specifies which patch document formats this server supports" (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Applies to the response, not request.<p>
         * 
         * Example: {@code Accept-Patch: text/example;charset=utf-8}<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         * 
         * @see Method#PATCH
         * @see <a href="https://tools.ietf.org/html/rfc5789#section-3.1">RFC 5789 §3.1</a>
         */
        public static final String ACCEPT_PATCH = "Accept-Patch";
        
        /**
         * "What partial content range types this server supports via byte
         * serving" (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Applies to the response, not request.<p>
         * 
         * Example: {@code Accept-Ranges: bytes}<p>
         * 
         * @see #RANGE
         * @see <a href="https://tools.ietf.org/html/rfc7233#section-2.3">RFC 7233 §2.3</a>
         */
        public static final String ACCEPT_RANGES = "Accept-Ranges";
        
        /**
         * Used by client when driving content/representation negotiation.<p>
         * 
         * Specifies the acceptable media type charset, which is also possible
         * to do with the {@link #ACCEPT} header - so, the applicability of this
         * header is slightly confusing and not many servers bother about the
         * {@code Accept-Charset} header, including NoMagicHTTP.
         * 
         * Example: {@code Accept-Charset: utf-8}
         * 
         * @see RequestHandler
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-5.3.3">RFC 7231 §5.3.3</a>
         * @see <a href="https://stackoverflow.com/questions/7055849/accept-and-accept-charset-which-is-superior">StackOverflow</a>
         */
        public static final String ACCEPT_CHARSET = "Accept-Charset";
        
        /**
         * "Acceptable version in time." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Example: {@code Accept-Datetime: Thu, 31 May 2007 20:35:00 GMT}<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7089#section-2.1.1">RFC 7089 §2.1.1</a>
         */
        public static final String ACCEPT_DATETIME = "Accept-Datetime";
        
        /**
         * "List of acceptable encodings." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Applies to the request, not response.<p>
         * 
         * Example: {@code Accept-Encoding: gzip, deflate}<p>
         * 
         * Currently, the NoMagicHTTP server has no first-class support for
         * compression. Work is planned to add support.
         * 
         * @see #CONTENT_ENCODING
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-5.3.4">RFC 7231 §5.3.4</a>
         */
        public static final String ACCEPT_ENCODING = "Accept-Encoding";
        
        /**
         * "List of acceptable human languages for response." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Used by client when driving content/representation negotiation.<p>
         * 
         * Example: {@code Accept-Language: en-US}<p>
         * 
         * Although content negotiation is largely supported by the NoMagicHTTP
         * server, the language is not. The request handler may react to the
         * presence of this header if it so chooses. Please note that the
         * specified language should never rule out a response completely, as an
         * alternative language is better than no response at all, often solved
         * on the client's end by means of translation software.
         * 
         * @see RequestHandler
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-5.3.5">RFC 7231 §5.3.5</a>
         */
        public static final String ACCEPT_LANGUAGE = "Accept-Language";
        
        /**
         * Used by client in a CORS-preflight request.<p>
         *
         * Example: {@code Access-Control-Request-Method: GET}<p>
         *
         * @see #ORIGIN
         * @see <a href="https://fetch.spec.whatwg.org/#http-requests">WHATWG</a>
         */
        public static final String ACCESS_CONTROL_REQUEST_METHOD = "Access-Control-Request-Method";
        
        /**
         * Used by client in a CORS-preflight request.<p>
         *
         * @see #ORIGIN
         * @see <a href="https://fetch.spec.whatwg.org/#http-requests">WHATWG</a>
         */
        public static final String ACCESS_CONTROL_REQUEST_HEADERS = "Access-Control-Request-Headers";
        
        /**
         * "Indicates whether the response can be shared, via returning the
         * literal value of the {@code Origin} request header (which can be
         * {@code null}) or [@code *} in a response." (
         * <a href="https://fetch.spec.whatwg.org/#http-responses">WHATWG</a>
         * )<p>
         *
         * Used in a response to a CORS request.
         *
         * Example: {@code Access-Control-Allow-Origin: *}
         *
         * @see #ORIGIN
         */
        public static final String ACCESS_CONTROL_ALLOW_ORIGIN = "Access-Control-Allow-Origin";
        
        /**
         * "Indicates whether the response can be shared when request's
         * credentials mode is 'include'." (
         * <a href="https://fetch.spec.whatwg.org/#http-responses">WHATWG</a>
         * )<p>
         * 
         * Used in a response to a CORS request.
         * 
         * @see #ORIGIN
         */
        public static final String ACCESS_CONTROL_ALLOW_CREDENTIALS = "Access-Control-Allow-Credentials";
        
        /**
         * "Indicates which methods are supported by the response's URL for the
         * purposes of the CORS protocol" (
         * <a href="https://fetch.spec.whatwg.org/#http-responses">WHATWG</a>
         * )<p>
         * 
         * Used in a response to a CORS-preflight request.
         * 
         * @see #ORIGIN
         */
        public static final String ACCESS_CONTROL_ALLOW_METHODS = "Access-Control-Allow-Methods";
        
        /**
         * "Indicates which headers are supported by the response's URL for the
         * purposes of the CORS protocol" (
         * <a href="https://fetch.spec.whatwg.org/#http-responses">WHATWG</a>
         * )<p>
         * 
         * Used in a response to a CORS-preflight request.
         * 
         * @see #ORIGIN
         */
        public static final String ACCESS_CONTROL_ALLOW_HEADERS = "Access-Control-Allow-Headers";
        
        /**
         * "Indicates the number of seconds (5 by default) the information
         * provided by the {@code Access-Control-Allow-Methods} and {@code
         * Access-Control-Allow-Headers} headers can be cached." (
         * <a href="https://fetch.spec.whatwg.org/#http-responses">WHATWG</a>
         * )<p>
         * 
         * Used in a response to a CORS-preflight request.
         * 
         * @see #ORIGIN
         */
        public static final String ACCESS_CONTROL_MAX_AGE = "Access-Control-Max-Age";
        
        /**
         * "Indicates which headers can be exposed as part of the response by
         * listing their names." (
         * <a href="https://fetch.spec.whatwg.org/#http-responses">WHATWG</a>
         * )<p>
         * 
         * Used in a response to a CORS request that is not a CORS-preflight
         * request.
         * 
         * @see #ORIGIN
         */
        public static final String ACCESS_CONTROL_EXPOSE_HEADERS = "Access-Control-Expose-Headers";
        
        /**
         * "The age the object has been in a proxy cache in seconds" (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Applies to the response, not request.<p>
         * 
         * Example: {@code Age: 12}<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7234#section-5.1">RFC 7234 §5.1</a>
         */
        public static final String AGE = "Age";
        
        /**
         * "Valid methods for a specified resource." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Applies to the response, not request.<p>
         * 
         * Example: {@code Allow: GET, HEAD}<p>
         * 
         * Currently, this header key is not used by the NoMagicHTTP server.
         * Planned future work will make use of this header in a response to
         * {@value StatusCode#FOUR_HUNDRED_FIVE} (Method Not Allowed).
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-7.4.1">RFC 7231 §7.4.1</a>
         */
        public static final String ALLOW = "Allow";
        
        /**
         * "A server uses "Alt-Svc" header (meaning Alternative Services) to
         * indicate that its resources can also be accessed at a different
         * network location (host or port) or using a different protocol" (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Applies to the response, not request.<p>
         * 
         * Example: {@code Alt-Svc: http/1.1="http2.example.com:8001"; ma=7200}<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7838#section-3">RFC 7838 §3</a>
         */
        public static final String ALT_SVC = "Alt-Svc";
        
        /**
         * "Authentication credentials for HTTP authentication." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Applies to the request, not response.<p>
         * 
         * Example: {@code Authorization: Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==}<p>
         * 
         * Currently, the NoMagicHTTP server has no first-class support for
         * HTTP authentication. Work is planned to add limited support.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7235#section-4.2">RFC 7235 §4.2</a>
         */
        public static final String AUTHORIZATION = "Authorization";
        
        /**
         * "Used to specify directives that must be obeyed by all caching
         * mechanisms along the request-response chain." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * May be used in both request and response.<p>
         * 
         * Example: {@code Cache-Control: no-cache}<p>
         * 
         * Currently, the NoMagicHTTP server has no first-class support for
         * constructing {@code Cache-Control} directives. This will likely be
         * added in the near future.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7234#section-5.2">RFC 7234 §5.2</a>
         */
        public static final String CACHE_CONTROL = "Cache-Control";
        
        /**
         * "Control options for the current connection and list of hop-by-hop
         * request fields." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * May be used in both request and response. For example, by client to
         * upgrade the connection and by server to signal an upcoming closure of
         * the connection.<p>
         * 
         * Example: {@code Connection: keep-alive}<p>
         * 
         * The header is used internally by the NoMagicHTTP server and the
         * application should have no need to use it explicitly.
         * 
         * @see StatusCode#ONE_HUNDRED_ONE 101}
         * @see <a href="https://tools.ietf.org/html/rfc7230#section-6.1">RFC 7230 §6.1</a>
         */
        public static final String CONNECTION = "Connection";
        
        /**
         * "An opportunity to raise a 'File Download' dialogue box for a known
         * MIME type with binary format or suggest a filename for dynamic
         * content." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * May be used in responses and {@code multipart/form-data} requests.<p>
         * 
         * Example: {@code Content-Disposition: attachment; filename="fname.ext"}<p>
         * 
         * This header key is not currently used by the NoMagicHTTP server.
         * Planned support for improving file serving, API extensions and body
         * decoding will make use of this header.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc6266#section-4">RFC 6266 §4</a>
         * @see <a href="https://tools.ietf.org/html/rfc2388#section-3">RFC 2388 §3</a>
         */
        public static final String CONTENT_DISPOSITION = "Content-Disposition";
        
        /**
         * The type of encoding that has been applied to the
         * content/representation (body). The recipient must decode the content
         * in order to get at the specified {@link #CONTENT_TYPE}.
         * 
         * May be used in both request and response.<p>
         * 
         * Example: {@code Content-Encoding: gzip}<p>
         * 
         * Currently, the NoMagicHTTP server has no first-class support for
         * compression. Work is planned to add support.
         * 
         * @see #ACCEPT_ENCODING
         * @see #TRANSFER_ENCODING
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-3.1.2.2">RFC 7231 §3.1.2.2</a>
         */
        public static final String CONTENT_ENCODING = "Content-Encoding";
        
        /**
         * "The natural language or languages of the intended audience for the
         * enclosed content" (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Mostly used in responses, but may be used in a request as well.<p>
         * 
         * Example: {@code Content-Language: da}<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-3.1.3.2">RFC 7231 §3.1.3.2</a>
         */
        public static final String CONTENT_LANGUAGE = "Content-Language";
        
        /**
         * "The length of the request body in octets (8-bit bytes)." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * May be used in both request and response.<p>
         * 
         * Example: {@code Content-Length: 348}<p>
         * 
         * The header is used internally by the NoMagicHTTP server and the
         * application should rarely have a need to use it explicitly.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7230#section-3.3.2">RFC 7230 §3.3.2</a>
         */
        public static final String CONTENT_LENGTH = "Content-Length";
        
        /**
         * An alternate location for the representation in the message
         * payload.<p>
         * 
         * Mostly used in responses to indicate the URL of a resource
         * transmitted as the result of content negotiation, but may be used in
         * a request as well.<p>
         * 
         * Example: {@code Content-Location: /index.html}<p>
         * 
         * "Location and Content-Location are different. Location indicates the
         * URL of a redirect, while Content-Location indicates the direct URL to
         * use to access the resource, without further content negotiation in
         * the future. Location is a header associated with the response, while
         * Content-Location is associated with the data returned." (
         * <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Content-Location">MDN Web Docs</a>
         * )<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         * 
         * @see #LOCATION
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-3.1.4.2">RFC 7231 §3.1.4.2</a>
         */
        public static final String CONTENT_LOCATION = "Content-Location";
        
        /**
         * "A Base64-encoded binary MD5 sum of the content" (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Example: {@code Content-MD5: Q2hlY2sgSW50ZWdyaXR5IQ==}<p>
         * 
         * This header is never used by the NoMagicHTTP server, nor should you,
         * as it has been removed (
         * <a href="https://tools.ietf.org/html/rfc7231#appendix-B">RFC 7231 Appendix B</a>
         * ). Use {@linkplain #DIGEST Digest} instead.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc2616#section-14.15">RFC 2616 §14.15</a>
         */
        public static final String CONTENT_MD5 = "Content-MD5";
        
        /**
         * "Where in a full body message this partial message belongs" (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Intended to be used in responses, not requests.<p>
         * 
         * Example: {@code Content-Range: bytes 21010-47021/47022}<p>
         * 
         * @see #RANGE
         * @see <a href="https://tools.ietf.org/html/rfc7233#section-4.2">RFC 7233 §4.2</a>
         */
        public static final String CONTENT_RANGE = "Content-Range";
        
        /**
         * "The HTTP Content-Security-Policy response header allows web site
         * administrators to control resources the user agent is allowed to load
         * for a given page. With a few exceptions, policies mostly involve
         * specifying server origins and script endpoints. This helps guard
         * against cross-site scripting attacks (XSS)." (
         * <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Content-Security-Policy">MDN Web Docs</a>
         * )<p>
         * 
         * Applies to the response, not request.<p>
         * 
         * Currently, the NoMagicHTTP server does not use this header key.
         * Future work might add this support.
         */
        public static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";
        
        /**
         * Specifies the media type of the representation in the message
         * body.<p>
         * 
         * May be used in both request and response.<p>
         * 
         * Example: {@code Content-Type: text/html; charset=utf-8}<p>
         * 
         * If a body is present, but no media type has been specified, then the
         * default is binary; "application/octet-stream".<p>
         * 
         * Codings may be applied on top of the representation, as indicated by
         * {@link #CONTENT_ENCODING} (end-to-end) and {@link #TRANSFER_ENCODING}
         * (hop-by-hop).<p>
         * 
         * The header is used extensively by the NoMagicHTTP API when decoding
         * and encoding HTTP messages.
         * 
         * @see MediaType
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-3.1.1.5">RFC 7231 §3.1.1.5</a>
         */
        public static final String CONTENT_TYPE = "Content-Type";
        
        /**
         * An HTTP cookie previously sent by the server with {@link
         * #SET_COOKIE}.<p>
         * 
         * Applies to the request, not response.<p>
         * 
         * Example: {@code Cookie: $Version=1; Skin=new;}<p>
         * 
         * Currently, the NoMagicHTTP server has no first-class API support for
         * cookies. Work is planned to add support.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc6265#section-5.4">RFC 6265 §5.4</a>
         */
        public static final String COOKIE = "Cookie";
        
        /**
         * The date and time at which the message was originated.<p>
         * 
         * May be used in both request and response.<p>
         * 
         * Example: {@code Date: Tue, 15 Nov 1994 08:12:31 GMT}<p>
         * 
         * Currently, the NoMagicHTTP server has no first-class API support for
         * the {@code Date} header. Support will likely be added.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-7.1.1.2">RFC 7231 §7.1.1.2</a>
         */
        public static final String DATE = "Date";
        
        /**
         * "Specifies the delta-encoding entity tag of the response." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Example: {@code Delta-Base: "abc"}<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc3229#section-10.5.1">RFC 3229 §10.5.1</a>
         */
        public static final String DELTA_BASE = "Delta-Base";
        
        /**
         * A digest of the message content.
         * 
         * <pre>
         *   --{@literal >}
         *   GET /items/123 HTTP/1.1
         *   Host: www.example.com
         *   Want-Digest: sha-256, sha-512
         *   
         *   {@literal <}--
         *   HTTP/1.1 200 OK
         *   Content-Type: application/json
         *   Digest: sha-256=X48E9qOokqqrvdts8nOJRJN3OWDUoyWxBf7kbu9DBPE=
         *   
         *   {"hello": "world"}
         * </pre>
         * 
         * The NoMagicHTTP server does not currently use these headers. Support
         * may be added in the future.<p>
         * 
         * @see #WANT_DIGEST
         * @see #CONTENT_MD5
         * @see <a href="https://stackoverflow.com/a/63466700/1268003">StackOverflow</a>
         * @see <a href="https://datatracker.ietf.org/doc/html/draft-ietf-httpbis-digest-headers-04#section-3">IEFT Digest Draft §3</a>
         */
        public static final String DIGEST = "Digest";
        
        /**
         * "Requests a web application to disable their tracking of a user." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Applies to the request, not response.<p>
         * 
         * Example: {@code DNT: 1}<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         * 
         * @see #TK
         */
        public static final String DNT = "DNT";
        
        /**
         * "An identifier for a specific version of a resource, often a message
         * digest" (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Applies to the response, not request.<p>
         * 
         * Example: {@code ETag: "737060cd8c284d8af7ad3082f209582d"}<p>
         * 
         * Currently, the NoMagicHTTP server does not generate or read this
         * header key. It will be used in the future as part of added support
         * for HTTP compression and an improved file serving API.
         * 
         * @see #IF_MATCH
         * @see <a href="https://tools.ietf.org/html/rfc7232#section-2.3">RFC 7232 §2.3</a>
         */
        public static final String ETAG = "ETag";
        
        /**
         * "Indicates that particular server behaviors are required by the
         * client." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Applies to the request, not response.<p>
         * 
         * Example: {@code Expect: 100-continue}
         * 
         * @see StatusCode#ONE_HUNDRED
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-5.1.1">RFC 7231 §5.1.1</a>
         */
        public static final String EXPECT = "Expect";
        
        /**
         * "Gives the date/time after which the response is considered stale" (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Applies to the response, not request.<p>
         * 
         * Example: {@code Expires: Thu, 01 Dec 1994 16:00:00 GMT}<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7234#section-5.3">RFC 7234 §5.3</a>
         */
        public static final String EXPIRES = "Expires";
        
        /**
         * "Disclose original information of a client connecting to a web server
         * through an HTTP proxy." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Applies to the request, not response.<p>
         * 
         * Supersedes {@code X-Forwarded-For}, {@code X-Forwarded-Host} and
         * {@code X-Forwarded-Proto}.<p>
         * 
         * Example: {@code Forwarded: for=192.0.2.60;proto=http;by=203.0.113.43}<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7239#section-4">RFC 7239 §4</a>
         */
        public static final String FORWARDED = "Forwarded";
        
        /**
         * "The email address of the user making the request." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Example: {@code From: user@example.com}<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-5.5.1">RFC 7231 §5.5.1</a>
         */
        public static final String FROM = "From";
        
        /**
         * Is the domain name of the server, allowing for virtual hosting on a
         * shared IP address (application-level routing mechanism).<p>
         * 
         * Example: {@code Host: en.wikipedia.org:8080}<p>
         * 
         * This is the only required header (since HTTP/1.1) and applies only to
         * requests, not responses.<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7230#section-5.4">RFC 7230 §5.4</a>
         */
        public static final String HOST = "Host";
        
        /**
         * "A request that upgrades from HTTP/1.1 to HTTP/2 MUST include exactly
         * one HTTP2-Setting header field. The HTTP2-Settings header field is a
         * connection-specific header field that includes parameters that govern
         * the HTTP/2 connection, provided in anticipation of the server
         * accepting the request to upgrade." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Example: {@code HTTP2-Settings: token64}<p>
         * 
         * Applies to the request, not response.<p>
         * 
         * Currently, the NoMagicHTTP server does not support HTTP/2, but work
         * is planned to add support.
         * 
         * @see StatusCode#ONE_HUNDRED_ONE
         * @see <a href="https://tools.ietf.org/html/rfc7540#section-3.2.1">RFC 7540 §3.2.1</a>
         */
        public static final String HTTP2_SETTINGS = "HTTP2-Settings";
        
        /**
         * "Only perform the action if the client supplied entity matches the
         * same entity on the server. This is mainly for methods like PUT to
         * only update a resource if it has not been modified since the user
         * last updated it." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Example: {@code If-Match: "737060cd8c284d8af7ad3082f209582d"}<p>
         * 
         * Applies to the request, not response.<p>
         * 
         * Currently, the NoMagicHTTP server has no first-class API support for
         * conditional requests. This will be added in the near future through
         * an extended API that serves static files.
         * 
         * @see Method#PATCH
         * @see #ETAG
         * @see <a href="https://tools.ietf.org/html/rfc7232#section-3.1">RFC 7232 §3.1</a>
         */
        public static final String IF_MATCH = "If-Match";
        
        /**
         * "Allows a {@value StatusCode#THREE_HUNDRED_FOUR} Not Modified to be
         * returned if content is unchanged." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Example: {@code If-Match: "737060cd8c284d8af7ad3082f209582d"}<p>
         * 
         * Applies to the request, not response.<p>
         * 
         * @see #IF_MATCH
         * @see <a href="https://tools.ietf.org/html/rfc7232#section-3.3">RFC 7232 §3.3</a>
         */
        public static final String IF_MODIFIED_SINCE = "If-Modified-Since";
        
        /**
         * Allows a {@value StatusCode#THREE_HUNDRED_FOUR} Not Modified to be
         * returned if content is unchanged.<p>
         * 
         * Example: {@code If-None-Match: "737060cd8c284d8af7ad3082f209582d"}<p>
         * 
         * Applies to the request, not response.<p>
         * 
         * @see #IF_MATCH
         * @see <a href="https://tools.ietf.org/html/rfc7232#section-3.2">RFC 7232 §3.2</a>
         */
        public static final String IF_NONE_MATCH = "If-None-Match";
        
        /**
         * "If the entity is unchanged, send me the part(s) that I am missing;
         * otherwise, send me the entire new entity." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Example: {@code If-Range: "737060cd8c284d8af7ad3082f209582d"}<p>
         * 
         * Applies to the request, not response.<p>
         * 
         * Currently, the NoMagicHTTP server has no first-class API support for
         * conditional requests or byte serving. Both will be added in the near
         * future through an extended API that serves static files.
         * 
         * @see #RANGE
         * @see <a href="https://tools.ietf.org/html/rfc7233#section-3.2">RFC 7233 §3.2</a>
         */
        public static final String IF_RANGE = "If-Range";
        
        /**
         * "Only send the response if the entity has not been modified since a
         * specific time." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Example: {@code If-Unmodified-Since: Sat, 29 Oct 1994 19:43:31 GMT}<p>
         * 
         * Applies to the request, not response.<p>
         * 
         * @see #IF_MATCH
         * @see #LAST_MODIFIED
         * @see <a href="https://tools.ietf.org/html/rfc7232#section-3.4">RFC 7232 §3.4</a>
         */
        public static final String IF_UNMODIFIED_SINCE = "If-Unmodified-Since";
        
        /**
         * "Instance-manipulations applied to the response." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Example: {@code IM: feed}<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc3229#section-10.5.2">RFC 3229 §10.5.2</a>
         */
        public static final String IM = "IM";
        
        /**
         * "The last modified date for the requested object" (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Applies to the response, not request.<p>
         * 
         * Example: {@code Last-Modified: Tue, 15 Nov 1994 12:45:26 GMT}<p>
         * 
         * Currently, the NoMagicHTTP server does not use this header key.
         * Planned work for improved file serving will make use of it.
         * 
         * @see #IF_MODIFIED_SINCE
         * @see <a href="https://tools.ietf.org/html/rfc7232#section-2.2">RFC 7232 §2.2</a>
         */
        public static final String LAST_MODIFIED = "Last-Modified";
        
        /**
         * Provides a means for serialising one or more links in HTTP headers.
         * It is semantically equivalent to the HTML
         * <a href="https://developer.mozilla.org/en-US/docs/Web/HTML/Element/link">{@literal <}link{@literal >}</a>
         * element, albeit with a slightly different
         * <a href="https://tools.ietf.org/html/rfc8288#section-3">syntax</a>
         * .<p>
         * 
         * Applies to the response, not request.<p>
         * 
         * Example: {@code Link: </feed>; rel="alternate"}<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         * 
         * @see StatusCode#ONE_HUNDRED_THREE
         */
        public static final String LINK = "Link";
        
        /**
         * "Used in redirection, or when a new resource has been created." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Applies to the response, not request.<p>
         * 
         * Example: {@code Location: http://www.w3.org/pub/WWW/People.html}<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         * 
         * @see Method#POST
         * @see #CONTENT_LOCATION
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-7.1.2">RFC 7231 §7.1.2</a>
         */
        public static final String LOCATION = "Location";
        
        /**
         * "Limit the number of times the message can be forwarded through
         * proxies or gateways." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Example: {@code Max-Forwards: 10}<p>
         * 
         * Applies to the request, not response.<p>
         * 
         * Often used by client with a {@link Method#TRACE} method to limit
         * number of hops traced.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-5.1.2">RFC 7231 §5.1.2</a>
         */
        public static final String MAX_FORWARDS = "Max-Forwards";
        
        /**
         * Initiates a request for CORS.<p>
         * 
         * Example: {@code Origin: http://www.example-social-network.com}<p>
         * 
         * Currently, the NoMagicHTTP server has no first-class support for
         * CORS. Future work is planned to investigate what can be done about
         * it.
         * 
         * @see <a href="https://fetch.spec.whatwg.org/#origin-header">WHATWG</a>
         */
        public static final String ORIGIN = "Origin";
        
        /**
         * "Implementation-specific fields that may have various effects
         * anywhere along the request-response chain." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Example: {@code Pragma: no-cache}<p>
         * 
         * Applies to the request, not response.<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7234#section-5.4">RFC 7234 §5.4</a>
         */
        public static final String PRAGMA = "Pragma";
        
        /**
         * "Allows client to request that certain behaviors be employed by a
         * server while processing a request." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Example: {@code Prefer: return=representation}<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7240#section-2">RFC 7240 §2</a>
         */
        public static final String PREFER = "Prefer";
        
        /**
         * "Indicates which Prefer tokens were honored by the server and applied
         * to the processing of the request." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Applies to the response, not request.<p>
         * 
         * Example: {@code Preference-Applied: return=representation}<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7240#section-3">RFC 7240 §3</a>
         */
        public static final String PREFERENCE_APPLIED = "Preference-Applied";
        
        /**
         * "Request authentication to access the proxy." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Applies to the request, not response.<p>
         * 
         * Example: {@code Proxy-Authenticate: Basic}<p>
         * 
         * @see #AUTHORIZATION
         * @see <a href="https://tools.ietf.org/html/rfc7235#section-4.3">RFC 7235 §4.3</a>
         */
        public static final String PROXY_AUTHENTICATE = "Proxy-Authenticate";
        
        /**
         * "Authorization credentials for connecting to a proxy." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Applies to the request, not response.<p>
         * 
         * Example: {@code Proxy-Authorization: Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==}<p>
         * 
         * @see #AUTHORIZATION
         * @see <a href="https://tools.ietf.org/html/rfc7235#section-4.4">RFC 7235 §4.4</a>
         */
        public static final String PROXY_AUTHORIZATION = "Proxy-Authorization";
        
        /**
         * "Request only part of an entity." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Applies to the request, not response.<p>
         * 
         * Example: {@code Range: bytes=500-999}<p>
         * 
         * Currently, the NoMagicHTTP server has no first-class API support for
         * byte serving. It will be added in the near future through an extended
         * API that serves static files.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7233#section-3.1">RFC 7233 §3.1</a>
         */
        public static final String RANGE = "Range";
        
        /**
         * "This is the address of the previous web page from which a link to
         * the currently requested page was followed. (The word 'referrer' has
         * been misspelled in the RFC as well as in most implementations to the
         * point that it has become standard usage and is considered correct
         * terminology)" (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Applies to the request, not response.<p>
         * 
         * Example: {@code Referer: http://en.wikipedia.org/wiki/Main_Page}<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-5.5.2">RFC 7231 §5.5.2</a>
         */
        public static final String REFERER = "Referer";
        
        /**
         * "Used in redirection, or when a new resource has been created. This
         * refresh redirects after 5 seconds. Header extension introduced by
         * Netscape and supported by most web browsers." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Applies to the response, not request.<p>
         * 
         * Example: {@code Refresh: 5; url=http://www.w3.org/pub/WWW/People.html}<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         */
        public static final String REFRESH = "Refresh";
        
        /**
         * "If an entity is temporarily unavailable, this instructs the client
         * to try again later. Value could be a specified period of time (in
         * seconds) or a HTTP-date." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Applies to the response, not request.<p>
         * 
         * Example: {@code Retry-After: 120}<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-7.1.3">RFC 7231 §7.1.3</a>
         */
        public static final String RETRY_AFTER = "Retry-After";
        
        /**
         * A name for the server.<p>
         * 
         * Applies to the response, not request.<p>
         * 
         * Example: {@code Server: Apache/2.4.1 (Unix)}<p>
         * 
         * Currently, the NoMagicHTTP server does not set this header. Planned
         * future work will add this as a configuration option.
         * 
         * @see #USER_AGENT
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-7.4.2">RFC 7231 §7.4.2</a>
         */
        public static final String SERVER = "Server";
        
        /**
         * Is used to send cookies from the server to the client.<p>
         * 
         * Applies to the response, not request.<p>
         * 
         * Example: {@code Set-Cookie: UserID=JohnDoe; Max-Age=3600; Version=1}<p>
         * 
         * @see #COOKIE
         * @see <a href="https://tools.ietf.org/html/rfc6265#section-4.1">RFC 6265 §4.1</a>
         */
        public static final String SET_COOKIE = "Set-Cookie";
        
        /**
         * Lets a server tell clients that "it should only be accessed using
         * HTTPS, instead of using HTTP". (
         * <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Strict-Transport-Security">MDN Web Docs</a>
         * )<p>
         * 
         * Applies to the response, not request.<p>
         * 
         * Example: {@code Strict-Transport-Security: max-age=16070400; includeSubDomains}<p>
         * 
         * Currently, the NoMagicHTTP server has no native support for HTTPS and
         * does not use this header key. Future work is planned to add this
         * support.<p>
         * 
         * @see <a href="https://tools.ietf.org/html/rfc6797#section-6.1">RFC 6797 §6.1</a>
         */
        public static final String STRICT_TRANSPORT_SECURITY = "Strict-Transport-Security";
        
        /**
         * Put in a request to indicate "what transfer codings, besides chunked,
         * the client is willing to accept in response, and whether or not the
         * client is willing to accept trailer fields in a chunked transfer
         * coding." (
         * <a href="https://tools.ietf.org/html/rfc7230#section-4.3">RFC 7230 §4.3</a>
         * )<p>
         * 
         * Applies to the request, not response.<p>
         * 
         * Example: {@code TE: trailers, deflate}<p>
         * 
         * Currently, the NoMagicHTTP server does not apply chunked encoding and
         * consequently ignores this key. Work is planned to add this support.
         * HTTP/2 has it's own streaming mechanism and supports only {@code
         * trailers}.
         * 
         * @see #TRAILER
         * @see <a href="https://tools.ietf.org/html/rfc7230#section-4.3">RFC 7230 §4.3</a>
         */
        public static final String TE = "TE";
        
        /**
         * "Tracking Status header, value suggested to be sent in response to a
         * DNT(do-not-track)" (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Applies to the response, not request.<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         * 
         * @see #DNT
         */
        public static final String TK = "Tk";
        
        /**
         * The trailer header "allows the sender to include additional fields at
         * the end of chunked messages in order to supply metadata that might be
         * dynamically generated while the message body is sent, such as a
         * message integrity check, digital signature, or post-processing
         * status." (
         * <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Trailer">MDN Web Docs</a>
         * )<p>
         * 
         * Most often set on chunked response, but may also be set on a chunked
         * request.<p>
         * 
         * Example: {@code Trailer: header-name}<p>
         * 
         * Currently, the NoMagicHTTP server does not support chunked encoding.
         * Work is planned to add this support.
         * 
         * @see #TE
         * @see <a href="https://tools.ietf.org/html/rfc7230#section-4.4">RFC 7230 §4.4</a>
         */
        public static final String TRAILER = "Trailer";
        
        /**
         * Hop-by-hop transport encoding. {@link #CONTENT_ENCODING} applies
         * end-to-end.<p>
         * 
         * May be used in both request and response.<p>
         * 
         * Example: {@code Transfer-Encoding: gzip, chunked}<p>
         * 
         * Currently, the NoMagicHTTP server has no first-class support for
         * compression. Work is planned to add support.
         * 
         * @see #CONTENT_TYPE
         * @see <a href="https://tools.ietf.org/html/rfc7230#section-3.3.1">RFC 7230 §3.3.1</a>
         */
        public static final String TRANSFER_ENCODING = "Transfer-Encoding";
        
        /**
         * "The user agent string of the user agent." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Applies to the request, not response.<p>
         * 
         * Example: {@code User-Agent: Mozilla/5.0 (X11; Linux x86_64; rv:12.0) Gecko/20100101 Firefox/12.0}<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         * 
         * @see #SERVER
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-5.5.3">RFC 7231 §5.5.3</a>
         */
        public static final String USER_AGENT = "User-Agent";
        
        /**
         * "Ask the server to upgrade to another protocol." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Applies to the request, not response.<p>
         * 
         * Example: {@code Upgrade: h2c, HTTPS/1.3, IRC/6.9, RTA/x11, websocket}<p>
         * 
         * @see StatusCode#ONE_HUNDRED_ONE
         * @see <a href="https://tools.ietf.org/html/rfc7230#section-6.7">RFC 7230 §6.7</a>
         */
        public static final String UPGRADE = "Upgrade";
        
        /**
         * "The 'Vary' header field in a response describes what parts of a
         * request message, aside from the method, Host header field, and
         * request target, might influence the origin server's process for
         * selecting and representing this response." (
         * <a href="https://tools.ietf.org/html/rfc7231#section-7.1.4">RFC 7231 §7.1.4</a>
         * )<p>
         * 
         * Example: {@code Vary: accept-encoding, accept-language}<p>
         * 
         * Currently, the NoMagicHTTP server does not set this header. Planned
         * future work may add this support for 200 and 304 responses.
         * 
         * @see #ACCEPT
         * @see <a href="https://www.smashingmagazine.com/2017/11/understanding-vary-header/">Smashing Magazine</a>
         */
        public static final String VARY = "Vary";
        
        /**
         * "Informs the server of proxies through which the request was sent." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Applies to the request, not response.<p>
         * 
         * Example: {@code Via: 1.0 fred, 1.1 example.com (Apache/1.1)}<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         * 
         * @see Method#TRACE
         * @see <a href="https://tools.ietf.org/html/rfc7230#section-5.7.1">RFC 7230 §5.7.1</a>
         */
        public static final String VIA = "Via";
        
        /**
         * Indicates the sender's desire to receive a representation digest.<p>
         * 
         * @see #DIGEST
         * @see <a href="https://datatracker.ietf.org/doc/html/draft-ietf-httpbis-digest-headers-04#section-4">IEFT Digest Draft §4</a>
         */
        public static final String WANT_DIGEST = "Want-Digest";
        
        /**
         * "A general warning about possible problems with the entity body." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Mostly used in responses, but may also be set on a request.<p>
         * 
         * Example: {@code Warning: 199 Miscellaneous warning}<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7234#section-5.5">RFC 7234 §5.5</a>
         */
        public static final String WARNING = "Warning";
        
        /**
         * "Indicates the authentication scheme that should be used to access
         * the requested entity." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Applies to the response, not request.<p>
         * 
         * Example: {@code WWW-Authenticate: Basic}
         * 
         * @see #AUTHORIZATION
         * @see StatusCode#FOUR_HUNDRED_ONE
         * @see <a href="https://tools.ietf.org/html/rfc7235#section-4.1">RFC 7235 §4.1</a>
         */
        public static final String WWW_AUTHENTICATE = "WWW-Authenticate";
        
        /**
         * "Mainly used to identify Ajax requests (most JavaScript frameworks
         * send this field with value of XMLHttpRequest); also identifies
         * Android apps using WebView." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Example: {@code X-Requested-With: XMLHttpRequest}<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         * 
         * @see <a href="https://www.stoutner.com/the-x-requested-with-header/">STOUTNER</a>
         */
        public static final String X_REQUESTED_WITH = "X-Requested-With";
        
        /**
         * "Requests a web application to override the method specified in the
         * request (typically POST) with the method given in the header field
         * (typically PUT or DELETE). This can be used when a user agent or
         * firewall prevents PUT or DELETE methods from being sent directly
         * (note that this is either a bug in the software component, which
         * ought to be fixed, or an intentional configuration, in which case
         * bypassing it may be the wrong thing to do)." (
         * <a href="https://en.wikipedia.org/wiki/List_of_HTTP_header_fields">Wikipedia</a>
         * )<p>
         * 
         * Example: {@code X-HTTP-Method-Override: DELETE}<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         */
        public static final String X_HTTP_METHOD_OVERRIDE = "X-Http-Method-Override";
        
        /**
         * Commonly used as a request identifier.<p>
         * 
         * Example: {@code X-Request-ID: f058ebd6-02f7-4d3f-942e-904344e8cde5}<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         * 
         * @see #X_CORRELATION_ID
         */
        public static final String X_REQUEST_ID = "X-Request-ID";
        
        /**
         * Commonly used as a transaction identifier.<p>
         * 
         * Example: {@code X-Correlation-ID: f058ebd6-02f7-4d3f-942e-904344e8cde5}<p>
         * 
         * This header key is never used by the NoMagicHTTP server.
         * 
         * @see #X_REQUEST_ID
         */
        public static final String X_CORRELATION_ID = "X-Correlation-ID";
    }
    
    /**
     * Constants for HTTP version.
     * 
     * @see <a href="https://tools.ietf.org/html/rfc7230#section-2.6">RFC 7230 §2.6</a>
     */
    public enum Version
    {
        /**
         * HTTP/0.9<p>
         * 
         * HTTP/0.9 was never standardized and is not supported by the
         * NoMagicHTTP server. Future work will reject clients using this
         * protocol version.
         */
        HTTP_0_9 (0, of(9)),
        
        /**
         * HTTP/1.0
         * 
         * @see <a href="https://tools.ietf.org/html/rfc1945">RFC 1945</a>
         */
        HTTP_1_0 (1, of(0)),
        
        /**
         * HTTP/1.1
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7230">RFC 7230</a>
         * @see <a href="https://tools.ietf.org/html/rfc7231">RFC 7231</a>
         */
        HTTP_1_1 (1, of(1)),
        
        /**
         * HTTP/2
         * 
         * @see <a href="https://tools.ietf.org/html/rfc7540">RFC 7540</a>
         */
        HTTP_2 (2, empty()),
        
        /**
         * HTTP/3
         * 
         * @see <a href="https://quicwg.org/base-drafts/draft-ietf-quic-http.html">QUIC Draft</a>
         */
        HTTP_3 (3, empty());
        
        /**
         * Parse a version.
         * 
         * @param str to parse
         * 
         * @return a version
         * 
         * @throws NullPointerException if {@code str} is {@code null}
         * 
         * @throws HttpVersionParseException if parsing failed
         */
        public static Version parse(String str) {
            int slash = str.indexOf("/");
            
            if (slash == -1) {
                throw new HttpVersionParseException(str, "No forward slash.");
            }
            
            if (!str.subSequence(0, slash).equals("HTTP")) {
                throw new HttpVersionParseException(str,
                        "HTTP-name \"" + str.subSequence(0, slash) + "\" is not \"HTTP\".");
            }
            
            int dot = str.indexOf(".", slash + 1);
            
            final String major, minor;
            
            if (dot == -1) {
                major = str.substring(slash + 1);
                minor = null;
            } else {
                major = str.substring(slash + 1, dot);
                minor = str.substring(dot + 1);
            }
            
            try {
                return valueOf(str, major, minor);
            } catch (NumberFormatException e) {
                throw new HttpVersionParseException(str, e);
            }
        }
        
        private static Version valueOf(String str, String majorStr, String minorStr) {
            final int major;
            switch (major = parseInt(majorStr)) {
                case 0:
                    int m1 = parseMinor(str, minorStr);
                    if (m1 != 9) {
                        throw newParseExcForUnsupportedMinor(str, m1);
                    }
                    return HTTP_0_9;
                case 1:
                    int m2 = parseMinor(str, minorStr);
                    if (m2 == 0) {
                        return HTTP_1_0;
                    }
                    if (m2 == 1) {
                        return HTTP_1_1;
                    }
                    throw newParseExcForUnsupportedMinor(str, m2);
                case 2:
                    reqNoMinor(str, minorStr);
                    return HTTP_2;
                case 3:
                    reqNoMinor(str, minorStr);
                    return HTTP_3;
                default:
                    throw new HttpVersionParseException(str,
                            "Have no literal for major version \"" + major + "\".");
            }
        }
        
        private static int parseMinor(String str, String minor) {
            if (minor == null) {
                throw new HttpVersionParseException(str,
                        "No minor version provided when one was expected.");
            }
            return parseInt(minor);
        }
        
        private static HttpVersionParseException newParseExcForUnsupportedMinor(String str, int minor) {
            return new HttpVersionParseException(str,
                    "Have no literal for minor version \"" + minor + "\".");
        }
        
        private static void reqNoMinor(String str, String minor) {
            if (minor != null) {
                throw new HttpVersionParseException(str,
                        "Minor version provided when none was expected.");
            }
        }
        
        private final int major;
        private final OptionalInt minor;
        
        Version(int major, OptionalInt minor) {
            this.major = major;
            this.minor = minor;
        }
        
        /**
         * Returns the major protocol version.
         * 
         * @return the major protocol version
         */
        public int major() {
            return major;
        }
        
        /**
         * Returns the minor protocol version.
         * 
         * Only HTTP/0.9, HTTP/1.0 and HTTP/1.1 use the minor version. Since
         * HTTP/2, the minor version has been dropped.
         * 
         * @return the minor protocol version
         */
        public OptionalInt minor() {
            return minor;
        }
        
        /**
         * Returns the HTTP-version field value.<p>
         * 
         * I.e., one of "HTTP/0.9", "HTTP/1.0", "HTTP/1.1", "HTTP/2" or "HTTP/3".
         * 
         * @return the HTTP-version field value
         */
        @Override
        public String toString() {
            return "HTTP/" + major() + (minor().isEmpty() ? "" : "." + minor().getAsInt()); 
        }
    }
}