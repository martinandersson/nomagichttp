package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpConstants.Version;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.HttpVersionTooNewException;
import alpha.nomagichttp.message.HttpVersionTooOldException;
import alpha.nomagichttp.route.RouteRegistry;

import java.nio.channels.AsynchronousSocketChannel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_0;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.util.Headers.accept;
import static alpha.nomagichttp.util.Headers.contentType;
import static alpha.nomagichttp.util.Subscribers.onNext;
import static java.lang.Integer.parseInt;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;
import static java.util.Objects.requireNonNull;

/**
 * Implementation of an HTTP exchange from request to response.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class HttpExchange
{
    private static final System.Logger LOG = System.getLogger(HttpExchange.class.getPackageName());
    
    private final DefaultServer server;
    private final ChannelByteBufferPublisher bytes;
    private final DefaultClientChannel child;
    private final CompletableFuture<Void> result;
    
    /*
     * No mutable field in this class are volatile or synchronized. It is
     * assumed that the asynchronous execution facility of the CompletionStage
     * implementation establishes a happens-before relationship. This is
     * certainly true for JDK's CompletableFuture which uses an
     * Executor/ExecutorService, or at worst, a new Thread.start() for each task.
     */
    
    private DefaultRequest request;
    private RequestHandler handler;
    private ErrorResolver err;
    
    HttpExchange(
            DefaultServer server,
            AsynchronousSocketChannel child,
            ChannelByteBufferPublisher bytes)
    {
        this.server  = server;
        this.bytes   = bytes;
        this.result  = new CompletableFuture<>();
        this.child   = new DefaultClientChannel(child, HTTP_1_1, () -> result.complete(null));
        this.request = null;
        this.handler = null;
        this.err     = null;
    }
    
    /**
     * Begin the exchange.<p>
     * 
     * The returned stage should mostly complete normally as HTTP exchange
     * errors are dealt with internally (through {@link ErrorHandler}). Any
     * other error will be logged in this class. The server must close the child
     * if the result completes exceptionally.
     * 
     * @return a stage (never {@code null})
     */
    CompletionStage<Void> begin() {
        try {
            begin0();
        } catch (Throwable t) {
            unexpected(t);
        }
        return result;
    }
    
    /**
     * Delegates to {@code child.}{@link DefaultClientChannel#isEverythingOpen()
     * isEverythingOpen()}.
     * 
     * @return see JavaDoc
     */
    boolean isEverythingOpen() {
        return child.isEverythingOpen();
    }
    
    private void begin0() {
        child.pipeline().subscribe(
                // Written byte count ignored
                onNext(r -> handleResult(r.error())));
        
        RequestHeadSubscriber rhs = new RequestHeadSubscriber(
                server.getConfig().maxRequestHeadSize());
        
        bytes.subscribe(rhs);
        rhs.asCompletionStage()
           .thenAccept(this::initialize)
           .thenRun(this::invokeRequestHandler)
           .exceptionally(thr -> {
               handleResult(thr);
               return null;
           });
    }
    
    private void initialize(RequestHead h) {
        RequestTarget t = RequestTarget.parse(h.requestTarget());
        
        Version v = getValidHttpVersion(h);
        child.pipeline().updateVersion(v);
        if (v == HTTP_1_0 && server.getConfig().rejectClientsUsingHTTP1_0()) {
            throw new HttpVersionTooOldException(h.httpVersion(), "HTTP/1.1");
        }
        
        RouteRegistry.Match m = findRoute(t);
        
        // This order is actually specified in javadoc of ErrorHandler#apply
        request = createRequest(v, h, t, m);
        handler = findRequestHandler(h, m);
    }
    
    private Version getValidHttpVersion(RequestHead h) {
        final Version ver;
        
        try {
            ver = Version.parse(h.httpVersion());
        } catch (IllegalArgumentException e) {
            String[] comp = e.getMessage().split(":");
            if (comp.length == 1) {
                // No literal for minor
                requireHTTP1(parseInt(comp[0]), h.httpVersion(), "HTTP/1.1"); // for now
                throw new AssertionError(
                        "String \"HTTP/<single digit>\" should have failed with parse exception (missing minor).");
            } else {
                // No literal for major + minor (i.e., version older than HTTP/0.9)
                assert comp.length == 2;
                assert parseInt(comp[0]) <= 0;
                throw new HttpVersionTooOldException(h.httpVersion(), "HTTP/1.1");
            }
        }
        
        requireHTTP1(ver.major(), h.httpVersion(), "HTTP/1.1");
        return ver;
    }
    
    private static void requireHTTP1(int major, String rejectedVersion, String upgrade) {
        if (major < 1) {
            throw new HttpVersionTooOldException(rejectedVersion, upgrade);
        }
        if (major > 1) { // for now
            throw new HttpVersionTooNewException(rejectedVersion);
        }
    }
    
    private RouteRegistry.Match findRoute(RequestTarget t) {
        return server.getRouteRegistry().lookup(t.segmentsNotPercentDecoded());
    }
    
    private DefaultRequest createRequest(Version v, RequestHead h, RequestTarget t, RouteRegistry.Match m) {
        return new DefaultRequest(v, h, t, m, bytes, child);
    }
    
    private static RequestHandler findRequestHandler(RequestHead rh, RouteRegistry.Match m) {
        RequestHandler h = m.route().lookup(
                rh.method(),
                contentType(rh.headers()).orElse(null),
                accept(rh.headers()));
        
        LOG.log(DEBUG, () -> "Matched handler: " + h);
        return h;
    }
    
    private void invokeRequestHandler() {
        handler.logic().accept(request, child);
    }
    
    private void handleResult(Throwable thr) {
        /*
         * We should make the connection life cycle much more solid; when is
         * the connection persistent and when is it not (also see RFC 2616
         * §14.10 Connection). No point in starting a new exchange if we expect
         * the connection to end. In fact, if that's the case we should actually
         * go ahead and close the channel! Currently, if the other side never
         * closes then we would end up having idle zombie connections (!).
         * TODO: 1) Make connection life cycle solid and robust.
         * TODO: 2) Implement idle timeout.
         */
        
        try {
            if (thr == null) {
                prepareForNewExchange();
            } else {
                resolve(thr);
            }
        } catch (Throwable t) {
            unexpected(t);
        }
    }
    
    private void prepareForNewExchange() {
        request.bodyDiscardIfNoSubscriber();
        request.bodyStage().whenComplete((Null, t) -> {
            if (t == null) {
                result.complete(null);
            } else {
                LOG.log(DEBUG,
                        // see SubscriptionAsStageOp.asCompletionStage()
                        "Upstream error/channel fault. " +
                        "Assuming reason and/or stacktrace was logged already.");
                result.completeExceptionally(t);
            }
        });
    }
    
    private void resolve(Throwable exc) {
        final Throwable unpacked = unpackCompletionException(exc);
        
        if (unpacked instanceof ClientAbortedException) {
            LOG.log(DEBUG, "Client aborted the HTTP exchange.");
            result.completeExceptionally(unpacked);
        } else if (child.isOpenForWriting())  {
            if (err == null) {
                err = new ErrorResolver();
            }
            err.resolve(unpacked);
        } else {
            LOG.log(DEBUG, () ->
                    "HTTP exchange finished exceptionally and child channel is closed for writing. " +
                    "Assuming reason and/or stacktrace was logged already.");
            result.completeExceptionally(unpacked);
        }
    }
    
    private class ErrorResolver {
        private Throwable prev;
        private int attemptCount;
        
        ErrorResolver() {
            this.attemptCount = 0;
        }
        
        void resolve(Throwable t) {
            if (prev != null) {
                assert prev != t;
                t.addSuppressed(prev);
            }
            prev = t;
            
            if (server.getErrorHandlers().isEmpty()) {
                usingDefault(t);
                return;
            }
            
            if (++attemptCount > server.getConfig().maxErrorRecoveryAttempts()) {
                LOG.log(WARNING, "Error recovery attempts depleted, will use default handler.");
                usingDefault(t);
                return;
            }
            
            LOG.log(DEBUG, () -> "Attempting error recovery #" + attemptCount);
            usingHandlers(t);
        }
        
        private void usingDefault(Throwable t) {
            try {
                ErrorHandler.DEFAULT.apply(t, child, request, handler);
            } catch (Throwable next) {
                next.addSuppressed(t);
                unexpected(next);
            }
        }
        
        private void usingHandlers(Throwable t) {
            for (ErrorHandler h : server.getErrorHandlers()) {
                try {
                    h.apply(t, child, request, handler);
                    return;
                } catch (Throwable next) {
                    if (t != next) {
                        // New fail
                        HttpExchange.this.resolve(next);
                        return;
                    } // else continue; Handler opted out
                }
            }
            // All handlers opted out
            usingDefault(t);
        }
    }
    
    private static Throwable unpackCompletionException(Throwable t) {
        requireNonNull(t);
        if (!(t instanceof CompletionException)) {
            return t;
        }
        return t.getCause() == null ? t : unpackCompletionException(t.getCause());
    }
    
    private void unexpected(Throwable t) {
        LOG.log(ERROR, "Unexpected.", t);
        result.completeExceptionally(t);
    }
}