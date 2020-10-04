package alpha.nomagichttp.internal;

import alpha.nomagichttp.ExceptionHandler;
import alpha.nomagichttp.handler.Handler;
import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.route.Route;

import java.nio.channels.AsynchronousByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

import static alpha.nomagichttp.message.Headers.accepts;
import static alpha.nomagichttp.message.Headers.contentLength;
import static alpha.nomagichttp.message.Headers.contentType;
import static alpha.nomagichttp.util.Publishers.empty;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;
import static java.util.Objects.requireNonNull;

/**
 * Initiates read and write operations on a client channel in order to realize
 * an HTTP exchange. Once a request-response pair has been exchanged, the flow
 * is restarted.<p>
 * 
 * @implNote
 * Logically, the client channel is transformed into a
 * {@link ChannelByteBufferPublisher} which is a {@link Flow.Publisher} that
 * will never complete for as long as the channel remains open.<p>
 * 
 * The first subscriber to subscribe to the channel is {@link
 * RequestHeadSubscriber}. Once the request head has been parsed, an
 * application-provided {@link Handler} will be resolved and called with a
 * {@link DefaultRequest} through which the channel byte publisher is exposed.
 * The handler will then be able to subscribe to the channel and consume the
 * request body.<p>
 * 
 * The request handler will return the response body in the form of a
 * {@code Flow.Publisher<ByteBuffer>} to which this class subscribes a
 * {@link ResponseToChannelWriter}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class HttpExchange
{
    // All mutable fields in this class are not volatile nor synchronized; it is
    // assumed that the asynchronous execution facility of the CompletionStage
    // implementation establishes a happens-before relationship. This is
    // certainly true for JDK's CompletableFuture which uses an
    // Executor/ExecutorService, or at worst, a new Thread.start() for each
    // task.
    
    private static final System.Logger LOG = System.getLogger(HttpExchange.class.getPackageName());
    
    private final DefaultServer server;
    private final AsynchronousByteChannel child;
    private final Flow.Publisher<DefaultPooledByteBufferHolder> bytes;
    
    // Are set lazily as exchange progresses
    private DefaultRequest request;
    private Route route;
    private Handler handler;
    
    HttpExchange(
            DefaultServer server,
            AsynchronousByteChannel child,
            Flow.Publisher<DefaultPooledByteBufferHolder> bytes)
    {
        this.server  = server;
        this.child   = child;
        this.bytes   = bytes;
        this.request = null;
        this.route   = null;
        this.handler = null;
    }
    
    void begin() {
        RequestHeadSubscriber rhp = new RequestHeadSubscriber(server.getServerConfig().maxRequestHeadSize());
        bytes.subscribe(rhp);
        
        rhp.asCompletionStage()
           .thenAccept(this::createRequest)
           .thenCompose(Null -> handleRequest())
           .thenCompose(this::handleResponse)
           .whenComplete(this::finish);
    }
    
    private void createRequest(RequestHead reqHead) {
        request = new DefaultRequest(reqHead);
    }
    
    private CompletionStage<Response> handleRequest() {
        // 1. Lookup route and handler
        // ---
        final Route.Match match = server.getRouteRegistry().lookup(request.target());
        route = match.route();
        request.setPathParameters(match.parameters());
        
        handler = route.lookup(
                request.method(),
                contentType(request.headers()).orElse(null),
                accepts(request.headers()));
        
        LOG.log(DEBUG, () -> "Matched handler: " + handler);
        
        
        // 2. Invoke handler
        // ---
        
        // TODO: If length is not present, then body is possibly chunked.
        // https://tools.ietf.org/html/rfc7230#section-3.3.3
        
        // TODO: Server should throw BadRequestException if Content-Length is present AND Content-Encoding
        // https://tools.ietf.org/html/rfc7230#section-3.3.2
        
        Flow.Publisher<PooledByteBufferHolder> reqBody = empty();
        
        OptionalLong len = contentLength(request.headers());
        
        if (len.isPresent()) {
            long v = len.getAsLong();
            if (v > 0) {
                LimitedFlow lf = new LimitedFlow(v);
                bytes.subscribe(lf);
                reqBody = lf;
            }
        }
        
        request.setBodySource(reqBody);
        return handler.logic().apply(request);
    }
    
    private CompletionStage<Boolean> handleResponse(Response res) {
        return new ResponseToChannelWriter(child, res)
                .asCompletionStage()
                .thenApply(Void -> {
                    if (res.mustCloseAfterWrite()) {
                        server.orderlyShutdown(child);
                        return true;
                    } else {
                        return false;
                    }
                });
    }
    
    private void finish(Boolean closed, Throwable t) {
        if (t != null) {
            handleError(t);
        } else {
            request = null;
            route = null;
            handler = null;
            if (!closed) {
                new HttpExchange(server, child, bytes).begin();
            }
        }
    }
    
    // Context for error handling
    private Throwable original;
    private List<ExceptionHandler> constructed;
    private int attemptCount;
    
    private void handleError(final Throwable t) {
        final List<Supplier<? extends ExceptionHandler>> factories = server.exceptionHandlers();
        final Throwable unpacked = unpackCompletionException(t);
        CompletionStage<Response> alternative = null;
        
        if (original == null) {
            original = unpacked;
        }
        
        if (factories.isEmpty() ||
            ++attemptCount > server.getServerConfig().maxErrorRecoveryAttempts())
        {
            if (!factories.isEmpty()) {
                LOG.log(WARNING, "Error recovery attempts depleted.");
            }
            
            try {
                alternative = ExceptionHandler.DEFAULT.apply(unpacked, request, route, handler);
            } catch (Throwable next) {
                LOG.log(ERROR, "Default exception handler failed. Will initiate orderly shutdown.", next);
                server.orderlyShutdown(child);
                return;
            }
        } else {
            LOG.log(DEBUG, () -> "Attempting error recovery #" + attemptCount);
            
            for (int i = 0; i < factories.size(); ++i) {
                if (constructed == null) {
                    constructed = new ArrayList<>();
                }
                
                final ExceptionHandler h;
                if (constructed.size() < i + 1) {
                    constructed.add(h = factories.get(i).get());
                } else {
                    h = constructed.get(i);
                }
                
                try {
                    alternative = requireNonNull(h.apply(unpacked, request, route, handler));
                    break;
                } catch (Throwable next) {
                    if (unpacked == next) {
                        // Handler opted out
                        continue;
                    }
                    // New fail
                    unpacked.addSuppressed(next);
                    handleError(next);
                    return;
                }
            }
        }
        
        alternative.thenCompose(this::handleResponse)
                   .whenComplete(this::finish);
    }
    
    private static Throwable unpackCompletionException(Throwable t) {
        if (!(t instanceof CompletionException)) {
            return t;
        }
        return t.getCause() == null ? t : unpackCompletionException(t.getCause());
    }
}