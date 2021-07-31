package alpha.nomagichttp.internal;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpConstants.Version;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.RequestBodyTimeoutException;
import alpha.nomagichttp.message.RequestHead;
import alpha.nomagichttp.util.Attributes;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.stream.Stream;

import static alpha.nomagichttp.util.Headers.contentLength;
import static java.lang.System.Logger.Level.DEBUG;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedStage;

final class DefaultRequest implements Request
{
    private static final System.Logger LOG = System.getLogger(DefaultRequest.class.getPackageName());
    private static final CompletionStage<Void> COMPLETED = completedStage(null);
    
    private final Version ver;
    private final RequestHead head;
    private final RequestTarget paramsQuery;
    private final ResourceMatch<?> paramsPath;
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
     * @param chIn source of body bytes
     * @param chApi client channel
     * @param timeout see {@link Config#timeoutIdleConnection()}
     * @param beforeNonEmptyBodySubscription if {@code null}, no callback
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
            ResourceMatch<?> paramsPath,
            Flow.Publisher<DefaultPooledByteBufferHolder> chIn,
            DefaultClientChannel chApi,
            Duration timeout,
            Runnable beforeNonEmptyBodySubscription,
            Runnable afterNonEmptyBodySubscription)
    {
        return new DefaultRequest(
                ver, head, paramsQuery, paramsPath, chIn, chApi, timeout,
                beforeNonEmptyBodySubscription, afterNonEmptyBodySubscription);
    }
    
    /**
     * Creates a request without support for parameters.<p>
     * 
     * The benefit of this variant is that there's no need for the call site to
     * have local access to a parsed {@link RequestTarget} (query params) or a
     * {@link ResourceMatch} (path params). This is the case if either of the
     * two failed to be produced, yet the HTTP exchange may need an API to
     * discard the request body.<p>
     * 
     * Accessing a parameter method will throw NPE.
     * 
     * @param ver HTTP version
     * @param head request head
     * @param chIn source of body bytes
     * @param chApi client channel
     * @param timeout see {@link Config#timeoutIdleConnection()}
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
            Flow.Publisher<DefaultPooledByteBufferHolder> chIn,
            DefaultClientChannel chApi,
            Duration timeout,
            Runnable beforeNonEmptyBodySubscription,
            Runnable afterNonEmptyBodySubscription)
    {
        return new DefaultRequest(
                ver, head, null, null, chIn, chApi, timeout,
                beforeNonEmptyBodySubscription, afterNonEmptyBodySubscription);
    }
    
    private DefaultRequest(
            Version ver,
            RequestHead head,
            RequestTarget paramsQuery,
            ResourceMatch<?> paramsPath,
            Flow.Publisher<DefaultPooledByteBufferHolder> chIn,
            DefaultClientChannel chApi,
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
            requireNonNull(chIn);
            requireNonNull(chApi);
            requireNonNull(timeout);
            bodyStage   = COMPLETED;
            bodyApi     = RequestBody.empty(headers());
            bodyDiscard = null;
        } else {
            var bounded = new LengthLimitedOp(len, chIn);
            // Upstream is ChannelByteBufferPublisher, he can handle async cancel
            var timeOut = new TimeoutOp.Flow<>(false, true, bounded, timeout, () -> {
                if (LOG.isLoggable(DEBUG) && chApi.isOpenForReading()) {
                    LOG.log(DEBUG, "Request body timed out, shutting down child channel's read stream.");
                }
                chApi.shutdownInputSafe();
                return new RequestBodyTimeoutException();
            });
            var observe = new SubscriptionAsStageOp(timeOut);
            var onError = new OnErrorCloseReadStream<>(observe, chApi);
            bodyDiscard = new OnCancelDiscardOp(onError);
            timeOut.start();
            
            bodyStage = observe.asCompletionStage();
            bodyApi = RequestBody.of(headers(), bodyDiscard,
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
    
    private static final class DefaultParameters implements Parameters
    {
        private final ResourceMatch<?> p;
        private final Map<String, List<String>> q, qRaw;
        
        DefaultParameters(ResourceMatch<?> paramsPath, RequestTarget paramsQuery) {
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