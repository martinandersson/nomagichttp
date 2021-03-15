package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpConstants.Version;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.HttpVersionTooNewException;
import alpha.nomagichttp.message.HttpVersionTooOldException;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.route.RouteRegistry;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.util.Headers.accept;
import static alpha.nomagichttp.util.Headers.contentType;
import static java.lang.Integer.parseInt;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;
import static java.util.Objects.requireNonNull;

/**
 * Initiates read- and write operations on a client channel in order to realize
 * an HTTP exchange. Once a request-response pair has been exchanged, the flow
 * is restarted.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class HttpExchange
{
    private static final System.Logger LOG = System.getLogger(HttpExchange.class.getPackageName());
    
    private final DefaultServer server;
    private final DefaultChannelOperations child;
    private final ChannelByteBufferPublisher bytes;
    
    /*
     * No mutable field in this class is volatile or synchronized. It is assumed
     * that the asynchronous execution facility of the CompletionStage
     * implementation establishes a happens-before relationship. This is
     * certainly true for JDK's CompletableFuture which uses an
     * Executor/ExecutorService, or at worst, a new Thread.start() for each task.
     */
    
    private Version ver;
    private DefaultRequest request;
    private RequestHandler handler;
    private ErrorHandlers eh;
    
    HttpExchange(DefaultServer server, DefaultChannelOperations child) {
        this(server, child, new ChannelByteBufferPublisher(child));
    }
    
    private HttpExchange(
            DefaultServer server,
            DefaultChannelOperations child,
            ChannelByteBufferPublisher bytes)
    {
        this.server  = server;
        this.child   = child;
        this.bytes   = bytes;
        // If request fails to parse, this is what we use in response
        this.ver     = HTTP_1_1;
        this.request = null;
        this.handler = null;
        this.eh      = null;
    }
    
    void begin() {
        try {
            begin0();
        } catch (Throwable t) {
            unexpected(t);
        }
    }
    
    private void begin0() {
        RequestHeadSubscriber rhs = new RequestHeadSubscriber(
                server.getConfig().maxRequestHeadSize());
        
        bytes.subscribe(rhs);
        
        rhs.asCompletionStage()
           .thenAccept(this::initialize)
           .thenCompose(Null -> invokeRequestHandler())
           .thenCompose(this::writeResponseToChannel)
           .whenComplete(this::finish);
    }
    
    private void initialize(RequestHead h) {
        RequestTarget t = RequestTarget.parse(h.requestTarget());
        this.ver = parseVersion(h);
        RouteRegistry.Match m = findRoute(t);
        
        // This order is actually specified in javadoc of ErrorHandler#apply
        request = createRequest(h, t, m);
        handler = findRequestHandler(h, m);
    }
    
    private static Version parseVersion(RequestHead h) {
        final Version ver;
    
        try {
            ver = Version.parse(h.httpVersion());
        } catch (IllegalArgumentException e) {
            String[] comp = e.getMessage().split(":");
            if (comp.length == 1) {
                // No literal for major
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
    
    private DefaultRequest createRequest(RequestHead h, RequestTarget t, RouteRegistry.Match m) {
        return new DefaultRequest(ver, h, t, m, bytes, child);
    }
    
    private static RequestHandler findRequestHandler(RequestHead rh, RouteRegistry.Match m) {
        RequestHandler h = m.route().lookup(
                rh.method(),
                contentType(rh.headers()).orElse(null),
                accept(rh.headers()));
        
        LOG.log(DEBUG, () -> "Matched handler: " + h);
        return h;
    }
    
    private CompletionStage<Response> invokeRequestHandler() {
        return handler.logic().apply(request);
    }
    
    private CompletionStage<Void> writeResponseToChannel(Response r) {
        ResponseBodySubscriber rbs = new ResponseBodySubscriber(ver, r, child);
        r.body().subscribe(rbs);
        return rbs.asCompletionStage()
                  .thenRun(() -> {
                      if (r.mustCloseAfterWrite() && (
                              child.isOpenForReading() ||
                              child.isOpenForWriting() ||
                              child.get().isOpen()) )
                      {
                          // TODO: Need to implement mustCloseAfterWrite( "mayInterrupt" param )
                          //       This will kill any ongoing subscription
                          LOG.log(DEBUG, "Response wants us to close the child, will close.");
                          bytes.close();
                          child.orderlyClose();
                      }
                  });
    }
    
    private void finish(Void Null, Throwable exc) {
        try {
            finish0(exc);
        } catch (Throwable t) {
            unexpected(t);
        }
    }
    
    private void finish0(Throwable exc) {
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
        
        if (exc == null) {
            if (!child.get().isOpen()) {
                return;
            }
            
            // Else begin new HTTP exchange
            request.bodyDiscardIfNoSubscriber();
            request.bodyStage().whenComplete((Null2, t2) -> {
                if (t2 == null) {
                    // TODO: Possible recursion. Unroll.
                    new HttpExchange(server, child, bytes).begin();
                } else if (child.isOpenForReading()) {
                    // See SubscriberAsStageOp.asCompletionStage(); t2 can only be an upstream error
                    LOG.log(WARNING, "Expected someone to have closed the child channel's read stream already.");
                    child.orderlyShutdownInput();
                }
            });
            
            return;
        }
        
        final Throwable unpacked = unpackCompletionException(exc);
        
        if (unpacked instanceof RequestHeadSubscriber.ClientAbortedException) {
            LOG.log(DEBUG, "Child channel aborted the HTTP exchange, will not begin a new one.");
        } else if (child.isOpenForWriting())  {
            if (eh == null) {
                eh = new ErrorHandlers();
            }
            eh.resolve(unpacked)
              .thenCompose(this::writeResponseToChannel)
              // TODO: Possible recursion. Unroll.
              .whenComplete(this::finish);
        } else {
            LOG.log(DEBUG, () ->
                "HTTP exchange finished exceptionally and child channel is closed for writing. " +
                "Assuming reason was logged already.");
        }
    }
    
    private class ErrorHandlers {
        private Throwable prev;
        private int attemptCount;
        
        ErrorHandlers() {
            this.attemptCount = 0;
        }
        
        CompletionStage<Response> resolve(Throwable t) {
            if (prev != null) {
                assert prev != t;
                t.addSuppressed(prev);
            }
            prev = t;
            
            if (server.getErrorHandlers().isEmpty()) {
                return usingDefault(t);
            }
            
            if (++attemptCount > server.getConfig().maxErrorRecoveryAttempts()) {
                LOG.log(WARNING, "Error recovery attempts depleted, will use default handler.");
                return usingDefault(t);
            }
            
            LOG.log(DEBUG, () -> "Attempting error recovery #" + attemptCount);
            return usingHandlers(t);
        }
        
        private CompletionStage<Response> usingDefault(Throwable t) {
            try {
                return ErrorHandler.DEFAULT.apply(t, request, handler);
            } catch (Throwable next) {
                // Do not next.addSuppressed(unpacked); the first thing DEFAULT did was to log unpacked.
                LOG.log(ERROR, "Default error handler failed.", next);
                return Responses.internalServerError().completedStage();
            }
        }
        
        private CompletionStage<Response> usingHandlers(Throwable t) {
            for (ErrorHandler h : server.getErrorHandlers()) {
                try {
                    return requireNonNull(h.apply(t, request, handler));
                } catch (Throwable next) {
                    if (t != next) {
                        // New fail
                        return resolve(unpackCompletionException(next));
                    } // else continue; Handler opted out
                }
            }
            // All handlers opted out
            return usingDefault(t);
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
        bytes.close();
        child.orderlyClose();
    }
}