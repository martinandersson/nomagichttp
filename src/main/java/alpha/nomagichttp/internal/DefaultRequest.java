package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpConstants.Version;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.RequestBodyTimeoutException;
import alpha.nomagichttp.route.RouteRegistry;
import alpha.nomagichttp.util.Attributes;
import alpha.nomagichttp.util.Publishers;

import java.net.http.HttpHeaders;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.charset.Charset;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static alpha.nomagichttp.internal.AtomicReferences.lazyInit;
import static alpha.nomagichttp.util.Headers.contentLength;
import static alpha.nomagichttp.util.Headers.contentType;
import static java.lang.System.Logger.Level.DEBUG;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedStage;
import static java.util.concurrent.CompletableFuture.failedStage;

final class DefaultRequest implements Request
{
    private static final System.Logger LOG = System.getLogger(DefaultRequest.class.getPackageName());
    
    private static final CompletionStage<Void> COMPLETED = completedStage(null);
    
    // Copy-pasted from AsynchronousFileChannel.NO_ATTRIBUTES
    @SuppressWarnings({"rawtypes"}) // generic array construction
    private static final FileAttribute<?>[] NO_ATTRIBUTES = new FileAttribute[0];
    
    private final Version ver;
    private final RequestHead head;
    private final RequestTarget paramsQuery;
    private final RouteRegistry.Match paramsPath;
    private final CompletionStage<Void> bodyStage;
    private final Body bodyApi;
    private final OnCancelDiscardOp bodyDiscard;
    private final Attributes attributes;
    
    /**
     * Creates a complete request with access to parameters.
     * 
     * @param ver HTTP version
     * @param head request head
     * @param paramsQuery params from query
     * @param paramsPath params from path
     * @param bodySource body bytes
     * @param child client channel
     * @param timeout see {@link HttpServer.Config#timeoutIdleConnection()}
     * @param afterNonEmptyBodySubscription if {@code null}, no callback
     * 
     * @throws NullPointerException if a required argument is {@code null}
     * 
     * @return a request
     */
    static DefaultRequest withParams(
            Version ver,
            RequestHead head,
            RequestTarget paramsQuery,
            RouteRegistry.Match paramsPath,
            Flow.Publisher<DefaultPooledByteBufferHolder> bodySource,
            DefaultClientChannel child,
            Duration timeout,
            Runnable beforeNonEmptyBodySubscription,
            Runnable afterNonEmptyBodySubscription)
    {
        return new DefaultRequest(
                ver, head, paramsQuery, paramsPath, bodySource, child, timeout,
                beforeNonEmptyBodySubscription, afterNonEmptyBodySubscription);
    }
    
    /**
     * Creates a request without support for parameters.<p>
     * 
     * The benefit of this variant is that there's no need for the call site to
     * have local access to a parsed {@link RequestTarget} (query params) or a
     * {@link RouteRegistry.Match} (path params). This is the case if either of
     * the two failed to be produced, yet the HTTP exchange may need an API to
     * discard the request body.<p>
     * 
     * Accessing a parameter method will throw NPE.
     * 
     * @param ver HTTP version
     * @param head request head
     * @param bodySource body bytes
     * @param child client channel
     * @param timeout see {@link HttpServer.Config#timeoutIdleConnection()}
     * @param beforeNonEmptyBodySubscription if {@code null}, no callback
     * @param afterNonEmptyBodySubscription if {@code null}, no callback
     * 
     * @throws NullPointerException if a required argument is {@code null}
     * 
     * @return a request
     */
    static DefaultRequest withoutParams(
            Version ver,
            RequestHead head,
            Flow.Publisher<DefaultPooledByteBufferHolder> bodySource,
            DefaultClientChannel child,
            Duration timeout,
            Runnable beforeNonEmptyBodySubscription,
            Runnable afterNonEmptyBodySubscription)
    {
        return new DefaultRequest(
                ver, head, null, null, bodySource, child, timeout,
                beforeNonEmptyBodySubscription, afterNonEmptyBodySubscription);
    }
    
    private DefaultRequest(
            Version ver,
            RequestHead head,
            RequestTarget paramsQuery,
            RouteRegistry.Match paramsPath,
            Flow.Publisher<DefaultPooledByteBufferHolder> bodySource,
            DefaultClientChannel child,
            Duration timeout,
            Runnable beforeNonEmptyBodySubscription,
            Runnable afterNonEmptyBodySubscription)
    {
        this.ver = requireNonNull(ver);
        this.head = requireNonNull(head);
        this.paramsQuery = paramsQuery;
        this.paramsPath = paramsPath;
        
        // TODO: If length is not present, then body is possibly chunked.
        // https://tools.ietf.org/html/rfc7230#section-3.3.3
        
        // TODO: Server should throw BadRequestException if Content-Length is present AND Content-Encoding
        // https://tools.ietf.org/html/rfc7230#section-3.3.2
        
        final long len = contentLength(head.headers()).orElse(0);
        
        if (len <= 0) {
            requireNonNull(bodySource);
            requireNonNull(child);
            requireNonNull(timeout);
            bodyStage   = COMPLETED;
            bodyApi     = DefaultBody.empty(headers());
            bodyDiscard = null;
        } else {
            var bounded = new LengthLimitedOp(len, bodySource);
            var timeOut = new TimeoutOp.Flow<>(bounded, timeout, () -> {
                if (LOG.isLoggable(DEBUG) && child.isOpenForReading()) {
                    LOG.log(DEBUG, "Request body timed out, shutting down child channel's read stream.");
                }
                child.shutdownInputSafe();
                return new RequestBodyTimeoutException();
            });
            var observe = new SubscriptionAsStageOp(timeOut);
            var onError = new OnErrorCloseReadStream<>(observe, child);
            bodyDiscard = new OnCancelDiscardOp(onError);
            timeOut.start();
            
            bodyStage = observe.asCompletionStage();
            bodyApi = DefaultBody.of(headers(), bodyDiscard,
                    beforeNonEmptyBodySubscription, afterNonEmptyBodySubscription);
        }
        
        this.attributes = new DefaultAttributes();
    }
    
    @Override
    public String method() {
        return head.method();
    }
    
    @Override
    public String target() {
        return head.requestTarget();
    }
    
    @Override
    public Version httpVersion() {
        return ver;
    }
    
    @Override
    public String toString() {
        return DefaultRequest.class.getSimpleName() + "{head=" + head + ", body=?}";
    }
    
    private Parameters params;
    
    @Override
    public Parameters parameters() {
        Parameters p = params;
        return p != null ? p : (params = new DefaultParameters(paramsPath, paramsQuery));
    }
    
    @Override
    public HttpHeaders headers() {
        return head.headers();
    }
    
    @Override
    public Body body() {
        return bodyApi;
    }
    
    @Override
    public Attributes attributes() {
        return attributes;
    }
    
    /**
     * Returns a stage that completes when the body subscription completes.<p>
     * 
     * The returned stage is already completed if the request contains no body.
     * 
     * @return a stage that completes when the body subscription completes
     * 
     * @see SubscriptionAsStageOp
     */
    CompletionStage<Void> bodyStage() {
        return bodyStage;
    }
    
    /**
     * If no downstream body subscriber is active, complete downstream and
     * discard upstream.<p>
     * 
     * Is NOP if body is empty or already discarding.
     */
    void bodyDiscardIfNoSubscriber() {
        if (body().isEmpty()) {
            return;
        }
        bodyDiscard.discardIfNoSubscriber();
    }
    
    private static final class DefaultBody implements Request.Body
    {
        static Request.Body empty(HttpHeaders headers) {
            // Even an empty body must still validate the arguments,
            // for example toText() may complete with IllegalCharsetNameException.
            requireNonNull(headers);
            return new DefaultBody(headers, null, null, null);
        }
    
        static Request.Body of(
                HttpHeaders headers,
                Flow.Publisher<PooledByteBufferHolder> source,
                Runnable beforeNonEmptyBodySubscription,
                Runnable afterNonEmptyBodySubscription)
        {
            requireNonNull(headers);
            requireNonNull(source);
            return new DefaultBody(headers, source,
                    beforeNonEmptyBodySubscription, afterNonEmptyBodySubscription);
        }
        
        private final HttpHeaders headers;
        private final Flow.Publisher<PooledByteBufferHolder> source;
        private final AtomicReference<CompletionStage<String>> cachedText;
        private final Runnable beforeSubscription;
        private final Runnable afterSubscription;
        
        private DefaultBody(
                HttpHeaders headers,
                Flow.Publisher<PooledByteBufferHolder> source,
                Runnable beforeSubscription,
                Runnable afterSubscription)
        {
            this.headers = headers;
            this.source  = source;
            this.cachedText = new AtomicReference<>(null);
            this.beforeSubscription = beforeSubscription;
            this.afterSubscription = afterSubscription;
        }
        
        @Override
        public CompletionStage<String> toText() {
            return lazyInit(cachedText, CompletableFuture::new, v -> copyResult(mkText(), v));
        }
        
        private CompletionStage<String> mkText() {
            final Charset charset;
            
            try {
                charset = contentType(headers)
                        .filter(m -> m.type().equals("text"))
                        .map(MediaType::parameters)
                        .map(p -> p.get("charset"))
                        .map(Charset::forName)
                        .orElse(UTF_8);
            } catch (Throwable t) {
                 return failedStage(t);
            }
            
            return convert((buf, count) ->
                    new String(buf, 0, count, charset));
        }
        
        @Override
        public CompletionStage<Long> toFile(Path file, OpenOption... options) {
            return toFile(file, Set.of(options), NO_ATTRIBUTES);
        }
        
        @Override
        public CompletionStage<Long> toFile(Path file, Set<? extends OpenOption> options, FileAttribute<?>... attrs) {
            final Set<? extends OpenOption> opt = !options.isEmpty() ? options :
                    Set.of(WRITE, CREATE_NEW);
            
            final AsynchronousFileChannel fs;
            
            try {
                // TODO: Potentially re-use server's async group
                //       (currently not possible to specify group?)
                fs = AsynchronousFileChannel.open(file, opt, null, attrs);
            } catch (Throwable t) {
                return failedStage(t);
            }
            
            FileSubscriber s = new FileSubscriber(file, fs);
            subscribe(s);
            return s.asCompletionStage();
        }
        
        @Override
        public <R> CompletionStage<R> convert(BiFunction<byte[], Integer, R> f) {
            HeapSubscriber<R> s = new HeapSubscriber<>(f);
            subscribe(s);
            return s.asCompletionStage();
        }
        
        @Override
        public void subscribe(Flow.Subscriber<? super PooledByteBufferHolder> subscriber) {
            if (isEmpty()) {
                Publishers.<PooledByteBufferHolder>empty().subscribe(subscriber);
            } else {
                if (beforeSubscription != null) {
                    beforeSubscription.run();
                }
                source.subscribe(subscriber);
                if (afterSubscription != null) {
                    afterSubscription.run();
                }
            }
        }
        
        @Override
        public boolean isEmpty() {
            return source == null;
        }
        
        private static <T> void copyResult(CompletionStage<? extends T> from, CompletableFuture<? super T> to) {
            from.whenComplete((val, exc) -> {
                if (exc == null) {
                    to.complete(val);
                } else {
                    to.completeExceptionally(exc);
                }
            });
        }
    }
    
    private static final class DefaultParameters implements Parameters
    {
        private final RouteRegistry.Match p;
        private final Map<String, List<String>> q, qRaw;
        
        DefaultParameters(RouteRegistry.Match paramsPath, RequestTarget paramsQuery) {
            p = requireNonNull(paramsPath);
            q = paramsQuery.queryMapPercentDecoded();
            qRaw = paramsQuery.queryMapNotPercentDecoded();
        }
        
        @Override
        public String path(String name) {
            return p.pathParam(name);
        }
        
        @Override
        public String pathRaw(String name) {
            return p.pathParamRaw(name);
        }
        
        @Override
        public Optional<String> queryFirst(String key) {
            return queryStream(key).findFirst();
        }
        
        @Override
        public Optional<String> queryFirstRaw(String key) {
            return queryStreamRaw(key).findFirst();
        }
        
        @Override
        public Stream<String> queryStream(String key) {
            return queryList(key).stream();
        }
    
        @Override
        public Stream<String> queryStreamRaw(String key) {
            return queryListRaw(key).stream();
        }
        
        @Override
        public List<String> queryList(String key) {
            return queryMap().getOrDefault(key, List.of());
        }
        
        @Override
        public List<String> queryListRaw(String key) {
            return queryMapRaw().getOrDefault(key, List.of());
        }
        
        @Override
        public Map<String, List<String>> queryMap() {
            return q;
        }
        
        @Override
        public Map<String, List<String>> queryMapRaw() {
            return qRaw;
        }
    }
}