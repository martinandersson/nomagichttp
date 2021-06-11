package alpha.nomagichttp.testutil;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.util.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.ResponseBody;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.core5.http.ProtocolVersion;
import org.eclipse.jetty.client.api.ContentResponse;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufMono;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClientResponse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.net.http.HttpClient.Version;
import static java.net.http.HttpRequest.BodyPublisher;
import static java.net.http.HttpRequest.BodyPublishers;
import static java.net.http.HttpResponse.BodyHandlers;
import static java.util.Arrays.stream;
import static java.util.Locale.ROOT;
import static java.util.Objects.deepEquals;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.eclipse.jetty.http.HttpMethod.GET;

/**
 * A HTTP client API that delegates to another {@link Implementation
 * implementation}.<p>
 * 
 * The NoMagicHTTP's own {@link TestClient} is <i>not</i> supported as a
 * delegate. The {@code TestClient} should be used explicitly in its own
 * separated test as it offers a comprehensive low-level API (with background
 * assertions such as expect-no-trailing-bytes from the server) at the same time
 * it also makes the HTTP exchange more readable (most usages simply write and
 * read strings). Where suitable, a subsequent "compatibility" test using the
 * client facade can then be declared whose purpose it is to ensure, well,
 * compatibility. E.g.
 * <pre>
 *   {@literal @}Test
 *   void helloWorld() {
 *       // Very clear for human, and also very detailed, explicit (we like)
 *       TestClient client = ...
 *       String response = client.writeReadTextUntilNewlines("GET / HTTP/1.1 ...");
 *       assertThat(response).isEqualTo("HTTP/1.1 200 OK ...");
 *   }
 *   
 *   {@literal @}ParameterizedTest
 *   {@literal @}EnumSource
 *   void helloWorld_compatibility(HttpClientFacade.Implementation impl) {
 *       // Explosion of types and methods. Only God knows what happens. But okay, good having.
 *       int serverPort = ...
 *       HttpClientFacade client = impl.create(serverPort);
 *       ResponseFacade{@literal <}String{@literal >} response = client.getText("/", HTTP_1_1);
 *       assertThat(response.statusCode())...
 *   }
 * </pre>
 * 
 * The clients used does not expose an API for user-control of the connection,
 * and so, the facade implementation can do no better. In fact, the life-cycle
 * and performance characteristics of the facade and its underlying client
 * objects are pretty much unknown. Most of them have - unfortunately quite
 * expectedly - zero documentation regarding the client's life-cycle and how it
 * should be cached and used. Never mind concerns such as thread-safety and
 * identity lol. Hence, this class will mostly not cache the client object,
 * using one new client for each request executed.<p>
 * 
 * Likely, the underlying client connection will live in a client-specific
 * connection pool until timeout. Attempts to hack the connection may fail. For
 * example, the JDK client will throw an {@code IllegalArgumentException} if
 * the "Connection: close" header is set.<p>
 * 
 * But, if the connection is never closed, then a test class extending {@code
 * AbstractRealTest} will timeout after each test when the superclass stops the
 * server and gracefully awaits child channel closures. To fix this problem, the
 * test ought to close the child from the server-installed request handler.<p>
 * 
 * A specified HTTP version may be rejected with an {@code
 * IllegalArgumentException}, but only on a best-effort basis. Which versions
 * specifically a client implementation supports is not always that clear
 * hahaha. The argument will be passed forward to the client who may then blow
 * up with another exception.<p>
 * 
 * This class is not thread-safe and does not implement {@code hashCode} or
 * {@code equals}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public abstract class HttpClientFacade
{
    /**
     * Supported delegate implementations.
     */
    public enum Implementation {
        /**
         * Supports version HTTP/1.1 and HTTP/2.<p>
         * 
         * Known perks:
         * <ol>
         *   <li>Has no API for retrieval of the reason-phrase.</li>
         *   <li>Does not support setting a "Connection" header.</li>
         *   <li>Does not support HTTP method CONNECT.</li>
         * </ol>
         */
        JDK (JDK::new),
        
        /**
         * What HTTP version this client supports is slightly unknown. JavaDoc
         * for OkHttp 4 (the client version currently used) is - perhaps not
         * surprisingly,
         * <a href="https://square.github.io/okhttp/4.x/okhttp/okhttp3/-ok-http-client/protocols/">empty</a>.
         * <a href="https://square.github.io/okhttp/3.x/okhttp/okhttp3/OkHttpClient.Builder.html#protocols-java.util.List-">OkHttp 3</a>
         * indicates HTTP/1.0 (and consequently 0.9?) is not supported. I guess
         * we'll find out.
         */
        OKHTTP (OkHttp::new),
        
        /**
         * Apache HttpClient.
         * 
         * @see <a href="https://hc.apache.org/httpcomponents-client-5.1.x/index.html">website</a>
         */
        APACHE (Apache::new),
        
        /**
         * Jetty HttpClient.
         * 
         * @see <a href="https://www.eclipse.org/jetty/documentation/jetty-11/programming-guide/index.html#pg-client-http">website</a>
         */
        JETTY (Jetty::new),
        
        /**
         * Reactor-Netty HttpClient.
         * 
         * @see <a href="https://projectreactor.io/docs/netty/release/reference/index.html#http-client">website</a>
         */
        REACTOR (Reactor::new);
        
        private final IntFunction<HttpClientFacade> factory;
        
        Implementation(IntFunction<HttpClientFacade> f) {
            factory = f;
        }
        
        /**
         * Create the client facade.
         * 
         * @param port of server
         * @return a client facade
         */
        public final HttpClientFacade create(int port) {
            return factory.apply(port);
        }
    }
    
    private final int port;
    // "permits null elements", whatever that means
    private Map<String, List<String>> headers;
    
    /**
     * Constructs a {@code HttpClientFacade}.
     * 
     * @param port of server
     */
    protected HttpClientFacade(int port) {
         this.port = port;
         this.headers = Map.of();
    }
    
    /**
     * Add a global header.<p>
     * 
     * Will be sent with each request.
     * 
     * @param name of header
     * @param value of header
     * @return this for chaining/fluency
     */
    public final HttpClientFacade addHeader(String name, String value) {
        requireNonNull(name);
        requireNonNull(value);
        if (headers.isEmpty()) { // i.e. == Map.of()
            headers = new LinkedHashMap<>();
        }
        headers.computeIfAbsent(name, k -> new ArrayList<>(1)).add(value);
        return this;
    }
    
    /**
     * Create a URI with "http://localhost:{port}" prefixed to the specified
     * {@code path}.
     * 
     * @param path of server resource
     * @return a URI
     */
    protected final URI withBase(String path) {
        return URI.create("http://localhost:" + port + path);
    }
    
    /**
     * Copy all headers contained in this class into the given sink.
     * 
     * @param sink target
     */
    protected void copyHeaders(BiConsumer<String, String> sink) {
        headers.forEach((name, values) ->
                values.forEach(v -> sink.accept(name, v)));
    }
    
    /**
     * Execute a GET request expecting bytes in the response body.
     * 
     * @param path of server resource
     * @param version of HTTP
     * @return the response
     * 
     * @throws IllegalArgumentException
     *             if no equivalent implementation-specific version exists, or
     *             if the version is otherwise not supported (too old or too new) 
     * @throws IOException
     *             if an I/O error occurs when sending or receiving
     * @throws InterruptedException
     *             if the operation is interrupted
     * @throws TimeoutException
     *             if an otherwise asynchronous operation times out
     *             (timeout duration not specified)
     * @throws ExecutionException
     *             if an underlying asynchronous operation fails
     */
    public abstract ResponseFacade<byte[]> getBytes(String path, HttpConstants.Version version)
            throws IOException, InterruptedException, TimeoutException, ExecutionException;
    
    /**
     * Execute a GET request expecting text in the response body.<p>
     * 
     * Which charset to use for decoding is for the client implementation to
     * decide. In practice, this will likely be extracted from the Content-Type
     * header.
     * 
     * @param path of server resource
     * @param version of HTTP
     * @return the response
     * 
     * @throws IllegalArgumentException
     *             if no equivalent implementation-specific version exists, or
     *             if the version is otherwise not supported (too old or too new) 
     * @throws IOException
     *             if an I/O error occurs when sending or receiving
     * @throws InterruptedException
     *             if the operation is interrupted
     * @throws TimeoutException
     *             if an otherwise asynchronous operation times out
     *             (timeout duration not specified)
     * @throws ExecutionException
     *             if an underlying asynchronous operation fails
     */
    public abstract ResponseFacade<String> getText(String path, HttpConstants.Version version)
            throws IOException, InterruptedException, TimeoutException, ExecutionException;
    
    /**
     * Execute a POST request expecting text in the response body.<p>
     * 
     * Which charset to use for decoding is for the client implementation to
     * decide.
     * 
     * @param path of server resource
     * @param version of HTTP
     * @param body of request
     * @return the response
     * 
     * @throws IllegalArgumentException
     *             if no equivalent implementation-specific version exists, or
     *             if the version is otherwise not supported (too old or too new) 
     * @throws IOException
     *             if an I/O error occurs when sending or receiving
     * @throws InterruptedException
     *             if the operation is interrupted
     */
    public abstract ResponseFacade<String> postAndReceiveText(
            String path, HttpConstants.Version version, String body)
            throws IOException, InterruptedException;
    
    private static class JDK extends HttpClientFacade {
        private final java.net.http.HttpClient c;
        
        JDK(int port) {
            super(port);
            c = java.net.http.HttpClient.newHttpClient();
        }
        
        @Override
        public ResponseFacade<byte[]> getBytes(String path, HttpConstants.Version ver)
                throws IOException, InterruptedException
        {
            return get(path, ver, BodyHandlers.ofByteArray());
        }
        
        @Override
        public ResponseFacade<String> getText(String path, HttpConstants.Version ver)
                throws IOException, InterruptedException
        {
            return get(path, ver, BodyHandlers.ofString());
        }
        
        private <B> ResponseFacade<B> get(
                String path, HttpConstants.Version ver, HttpResponse.BodyHandler<B> rspBodyConverter)
                throws IOException, InterruptedException
        {
            var req = newRequest("GET", path, ver, BodyPublishers.noBody());
            return execute(req, rspBodyConverter);
        }
        
        @Override
        public ResponseFacade<String> postAndReceiveText(
                String path, HttpConstants.Version ver, String body)
                throws IOException, InterruptedException
        {
            var req = newRequest("POST", path, ver, BodyPublishers.ofString(body));
            return execute(req, BodyHandlers.ofString());
        }
        
        private HttpRequest.Builder newRequest(
                String method, String path, HttpConstants.Version ver, BodyPublisher reqBody)
        {
            var b = HttpRequest.newBuilder()
                    .method(method, reqBody)
                    .uri(withBase(path))
                    .version(toJDKVersion(ver));
            copyHeaders(b::header);
            return b;
        }
        
        private <B> ResponseFacade<B> execute(
                HttpRequest.Builder builder,
                HttpResponse.BodyHandler<B> rspBodyConverter)
                throws IOException, InterruptedException
        {
            var rsp = c.send(builder.build(), rspBodyConverter);
            return ResponseFacade.fromJDK(rsp);
        }
        
        private static Version toJDKVersion(HttpConstants.Version ver) {
            final Version jdk;
            switch (ver) {
                case HTTP_0_9:
                case HTTP_1_0:
                case HTTP_3:
                    throw new IllegalArgumentException("Not supported.");
                case HTTP_1_1:
                    jdk = Version.HTTP_1_1;
                    break;
                case HTTP_2:
                    jdk = Version.HTTP_2;
                    break;
                default:
                    throw new IllegalArgumentException("No mapping.");
            }
            return jdk;
        }
    }
    
    private static class OkHttp extends HttpClientFacade {
        OkHttp(int port) {
            super(port);
        }
        
        @Override
        public ResponseFacade<byte[]> getBytes(String path, HttpConstants.Version ver)
                throws IOException
        {
            return get(path, ver, ResponseBody::bytes);
        }
        
        @Override
        public ResponseFacade<String> getText(String path, HttpConstants.Version ver)
                throws IOException
        {
            return get(path, ver, ResponseBody::string);
        }
        
        private <B> ResponseFacade<B> get(
                String path, HttpConstants.Version ver,
                IOFunction<? super ResponseBody, ? extends B> bodyConverter)
                throws IOException
        {
            var cli = new OkHttpClient.Builder()
                    .protocols(List.of(toSquareVersion(ver)))
                    .build();
            
            var req = new Request.Builder()
                    .method("GET", null)
                    .url(withBase(path).toURL());
            
            copyHeaders(req::header);
            
            // No close callback from our Response type, so must consume eagerly
            var rsp = cli.newCall(req.build()).execute();
            B bdy;
            try (rsp) {
                bdy = bodyConverter.apply(rsp.body());
            }
            return ResponseFacade.fromOkHttp(rsp, bdy);
        }
        
        @Override
        public ResponseFacade<String> postAndReceiveText(
                String path, HttpConstants.Version version, String body)
        {
            throw new AbstractMethodError("Implement me");
        }
        
        private static Protocol toSquareVersion(HttpConstants.Version ver) {
            final Protocol square;
            switch (ver) {
                case HTTP_0_9:
                case HTTP_3:
                    throw new IllegalArgumentException("Not supported.");
                case HTTP_1_0:
                    square = Protocol.HTTP_1_0;
                    break;
                case HTTP_1_1:
                    square = Protocol.HTTP_1_1;
                    break;
                case HTTP_2:
                    square = Protocol.HTTP_2;
                    break;
                default:
                    throw new IllegalArgumentException("No mapping.");
            }
            return square;
        }
    }
    
    private static class Apache extends HttpClientFacade {
        Apache(int port) {
            super(port);
        }
        
        @Override
        public ResponseFacade<byte[]> getBytes(String path, HttpConstants.Version ver)
                throws IOException, InterruptedException, TimeoutException, ExecutionException
        {
            return get(path, ver, SimpleHttpResponse::getBodyBytes);
        }
        
        @Override
        public ResponseFacade<String> getText(String path, HttpConstants.Version ver)
                throws IOException, InterruptedException, TimeoutException, ExecutionException
        {
            return get(path, ver, SimpleHttpResponse::getBodyText);
        }
        
        private <B> ResponseFacade<B> get(
                String path, HttpConstants.Version ver,
                Function<? super SimpleHttpResponse, ? extends B> bodyConverter)
                throws IOException, InterruptedException, TimeoutException, ExecutionException
        {
            var req = SimpleRequestBuilder
                    .get(withBase(path))
                    .setVersion(toApacheVersion(ver));
            
            copyHeaders(req::addHeader);
            
            try (var c = HttpAsyncClients.createDefault()) {
                // Must "start" first, otherwise
                //     java.util.concurrent.CancellationException: Request execution cancelled
                c.start();
                var rsp = c.execute(req.build(), null).get(3, SECONDS);
                return ResponseFacade.fromApache(rsp, bodyConverter.apply(rsp));
            }
        }
        
        @Override
        public ResponseFacade<String> postAndReceiveText(
                String path, HttpConstants.Version version, String body)
        {
            throw new AbstractMethodError("Implement me");
        }
        
        private static ProtocolVersion toApacheVersion(HttpConstants.Version ver) {
            return org.apache.hc.core5.http.HttpVersion.get(ver.major(), ver.minor().orElse(0));
        }
        
    }
    
    private static class Jetty extends HttpClientFacade {
        Jetty(int port) {
            super(port);
        }
        
        @Override
        public ResponseFacade<byte[]> getBytes(String path, HttpConstants.Version ver)
                throws InterruptedException, TimeoutException, ExecutionException
        {
            return get(path, ver, ContentResponse::getContent);
        }
        
        @Override
        public ResponseFacade<String> getText(String path, HttpConstants.Version ver)
                throws InterruptedException, TimeoutException, ExecutionException
        {
            return get(path, ver, ContentResponse::getContentAsString);
        }
        
        private <B> ResponseFacade<B> get(
                String path, HttpConstants.Version ver,
                Function<? super ContentResponse, ? extends B> bodyConverter)
                throws InterruptedException, TimeoutException, ExecutionException
        {
            var c = new org.eclipse.jetty.client.HttpClient();
            
            try {
                c.start();
            } catch (Exception e) { // <-- oh, wow...
                throw new RuntimeException(e);
            }
            
            ContentResponse rsp;
            try {
                var req = c.newRequest(withBase(path))
                       .method(GET)
                       .version(toJettyVersion(ver));
                
                copyHeaders((k, v) ->
                    req.headers(h -> h.add(k, v)));
                
                rsp = req.send();
            } finally {
                try {
                    c.stop();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            
            return ResponseFacade.fromJetty(rsp, bodyConverter);
        }
        
        @Override
        public ResponseFacade<String> postAndReceiveText(
                String path, HttpConstants.Version version, String body)
        {
            throw new AbstractMethodError("Implement me");
        }
        
        private static org.eclipse.jetty.http.HttpVersion
                toJettyVersion(HttpConstants.Version ver)
        {
            return org.eclipse.jetty.http.HttpVersion.fromString(ver.toString());
        }
    }
    
    private static class Reactor extends HttpClientFacade {
        Reactor(int port) {
            super(port);
        }
        
        @Override
        public ResponseFacade<byte[]> getBytes(String path, HttpConstants.Version ver) {
            return get(path, ver, ByteBufMono::asByteArray);
        }
        
        @Override
        public ResponseFacade<String> getText(String path, HttpConstants.Version ver) {
            return get(path, ver, ByteBufMono::asString);
        }
        
        private <B> ResponseFacade<B> get(
                String path, HttpConstants.Version ver,
                Function<? super ByteBufMono, ? extends Mono<B>> bodyConverter)
        {
            var req = reactor.netty.http.client.HttpClient.create()
                    .protocol(toReactorVersion(ver));
            
            copyHeaders((k, v) ->
                req.headers(h -> h.add(k, v)));
            
            // "uri() should be invoked before request()" says the JavaDoc.
            // Except that doesn't compile lol.
            return req.request(io.netty.handler.codec.http.HttpMethod.GET)
                      .uri(withBase(path))
                      .responseSingle((head, body) ->
                          bodyConverter.apply(body).map(s ->
                               ResponseFacade.fromReactor(head, s)))
                      .block();
        }
        
        @Override
        public ResponseFacade<String> postAndReceiveText(
                String path, HttpConstants.Version version, String body)
        {
            throw new AbstractMethodError("Implement me");
        }
        
        private static HttpProtocol toReactorVersion(HttpConstants.Version ver) {
            switch (ver) {
                case HTTP_1_1:
                    return HttpProtocol.HTTP11;
                case HTTP_2:
                    return HttpProtocol.H2;
                default:
                    throw new IllegalArgumentException("No mapping.");
            }
        }
    }
    
    /**
     * A HTTP response API.<p>
     * 
     * Delegates all operations to the underlying client's response
     * implementation (if possible, lazily) without caching.<p>
     * 
     * Two instances are equal only if each operation-pair return equal values
     * (as determined by using {@link Objects#deepEquals(Object, Object)}), or
     * if they both throw two equal {@code UnsupportedOperationException}s. Any
     * other exception will be rethrown from the {@code equals} method.<p>
     * 
     * {@code hashCode} is not implemented.
     * 
     * @param <B> body type
     */
    public static final class ResponseFacade<B> {
        
        static <B> ResponseFacade<B> fromJDK(java.net.http.HttpResponse<? extends B> jdk) {
            Supplier<String> version = () -> HttpConstants.Version.valueOf(jdk.version().name()).toString(),
                             phrase  = () -> {throw new UnsupportedOperationException();};
            return new ResponseFacade<>(
                    version,
                    jdk::statusCode,
                    phrase,
                    jdk::headers,
                    jdk::body);
        }
        
        static <B> ResponseFacade<B> fromOkHttp(okhttp3.Response okhttp, B body) {
            Supplier<HttpHeaders> headers = () -> Headers.of(okhttp.headers().toMultimap());
            return new ResponseFacade<>(
                    () -> okhttp.protocol().toString().toUpperCase(ROOT),
                    okhttp::code,
                    okhttp::message,
                    headers, () -> body);
        }
        
        static <B> ResponseFacade<B> fromApache(SimpleHttpResponse apache, B body) {
            Supplier<HttpHeaders> headers = () -> {
                var exploded = stream(apache.getHeaders())
                        .flatMap(h -> Stream.of(h.getName(), h.getValue()))
                        .toArray(String[]::new);
                return Headers.of(exploded);
            };
            return new ResponseFacade<>(
                    () -> apache.getVersion().toString(),
                    apache::getCode,
                    apache::getReasonPhrase,
                    headers,
                    () -> body);
        }
        
        static <B> ResponseFacade<B> fromJetty(
                org.eclipse.jetty.client.api.ContentResponse jetty,
                Function<? super ContentResponse, ? extends B> bodyConverter)
        {
            Supplier<HttpHeaders> headers = () -> {
                var exploded = jetty.getHeaders().stream()
                        .flatMap(h -> Stream.of(h.getName(), h.getValue()))
                        .toArray(String[]::new);
                
                return Headers.of(exploded);
            };
            return new ResponseFacade<>(
                    () -> jetty.getVersion().toString(),
                    jetty::getStatus,
                    jetty::getReason,
                    headers,
                    () -> bodyConverter.apply(jetty));
        }
        
        static <B> ResponseFacade<B> fromReactor(HttpClientResponse reactor, B body) {
            Supplier<HttpHeaders> headers = () -> {
                var exploded = reactor.responseHeaders().entries().stream()
                        .flatMap(e -> Stream.of(e.getKey(), e.getValue()))
                        .toArray(String[]::new);
                
                return Headers.of(exploded);
            };
            return new ResponseFacade<>(
                    () -> reactor.version().toString(),
                    () -> reactor.status().code(),
                    () -> reactor.status().reasonPhrase(),
                    headers,
                    () -> body);
        }
        
        private final Supplier<String> version;
        private final IntSupplier statusCode;
        private final Supplier<String> reasonPhrase;
        private final Supplier<HttpHeaders> headers;
        private final Supplier<? extends B> body;
        
        private ResponseFacade(
                Supplier<String> version,
                IntSupplier statusCode,
                Supplier<String> reasonPhrase,
                Supplier<HttpHeaders> headers,
                Supplier<? extends B> body)
        {
            this.version      = version;
            this.statusCode   = statusCode;
            this.reasonPhrase = reasonPhrase;
            this.headers      = headers;
            this.body         = body;
        }
        
        /**
         * Returns the HTTP version.
         * 
         * @return the HTTP version
         */
        public String version() {
            return version.get();
        }
        
        /**
         * Returns the response status-code.
         * 
         * @return status-code
         */
        public int statusCode() {
            return statusCode.getAsInt();
        }
        
        /**
         * Returns the response reason-phrase.
         * 
         * @return reason-phrase
         * 
         * @throws UnsupportedOperationException
         *             if the underlying client does not support this operation
         */
        public String reasonPhrase() {
            return reasonPhrase.get();
        }
         
        /**
         * Returns the response headers.
         * 
         * @return headers
         */
        public HttpHeaders headers() {
            return headers.get();
        }
        
        /**
         * Returns the response body.
         * 
         * @return body
         */
        public B body() {
            return body.get();
        }
        
        @Override
        public int hashCode() {
            // Pseudo-implementation to stop compilation warning, which stops the build
            // (equals implemented but not hashCode)
            return super.hashCode();
        }
        
        private static final List<Function<ResponseFacade<?>, ?>> GETTERS = List.of(
                // Add new getters for inclusion into equals() here please
                ResponseFacade::version,
                ResponseFacade::statusCode,
                ResponseFacade::reasonPhrase,
                ResponseFacade::headers,
                ResponseFacade::body );
        
        @Override
        public boolean equals(Object other) {
            if (other == null || other.getClass() != getClass()) {
                return false;
            }
            
            BiFunction<ResponseFacade<?>, Function<ResponseFacade<?>, ?>, ?>
                    getVal = (container, operation) -> {
                            try {
                                return operation.apply(container);
                            } catch (UnsupportedOperationException e) {
                                // This is also considered a value lol
                                return e;
                            }
                    };
            
            var that = (ResponseFacade<?>) other;
            Predicate<Function<ResponseFacade<?>, ?>> check = method ->
                    almostDeepEquals(getVal.apply(this, method), getVal.apply(that, method));
            
            for (var m : GETTERS) {
                if (!check.test(m)) {
                    return false;
                }
            }
            return true;
        }
        
        private boolean almostDeepEquals(Object v1, Object v2) {
            if ((v1 != null && v1.getClass() == UnsupportedOperationException.class) &&
                (v2 != null && v2.getClass() == UnsupportedOperationException.class)) {
                // Same class, compare message
                v1 = ((Throwable) v1).getMessage();
                v2 = ((Throwable) v2).getMessage();
            }
            return deepEquals(v1, v2);
        }
    }
}