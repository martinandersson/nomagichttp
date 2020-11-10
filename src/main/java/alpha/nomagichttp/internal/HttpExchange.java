package alpha.nomagichttp.internal;

import alpha.nomagichttp.ExceptionHandler;
import alpha.nomagichttp.handler.Handler;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.route.Route;

import java.nio.channels.AsynchronousByteChannel;
import java.nio.channels.NetworkChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.RandomAccess;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import static alpha.nomagichttp.message.Headers.accepts;
import static alpha.nomagichttp.message.Headers.contentType;
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
final class HttpExchange<C extends AsynchronousByteChannel & NetworkChannel>
{
    private static final System.Logger LOG = System.getLogger(HttpExchange.class.getPackageName());
    
    private final DefaultServer server;
    private final C child;
    private final ChannelByteBufferPublisher bytes;
    
    /*
     * No mutable field in this class is volatile or synchronized. It is assumed
     * that the asynchronous execution facility of the CompletionStage
     * implementation establishes a happens-before relationship. This is
     * certainly true for JDK's CompletableFuture which uses an
     * Executor/ExecutorService, or at worst, a new Thread.start() for each task.
     */
    
    private DefaultRequest request;
    private Handler handler;
    private ExceptionHandlers eh;
    
    HttpExchange(DefaultServer server, C child) {
        this(server, child, new ChannelByteBufferPublisher(server, child));
    }
    
    private HttpExchange(DefaultServer server, C child, ChannelByteBufferPublisher bytes) {
        this.server  = server;
        this.child   = child;
        this.bytes   = bytes;
        this.request = null;
        this.handler = null;
        this.eh      = null;
    }
    
    void begin() {
        RequestHeadSubscriber rhs = new RequestHeadSubscriber(
                server.getServerConfig().maxRequestHeadSize());
        
        bytes.subscribe(rhs);
        
        rhs.asCompletionStage()
           .thenAccept(this::initialize)
           .thenCompose(Null -> invokeRequestHandler())
           .thenCompose(this::writeResponseToChannel)
           .whenComplete(this::finish);
    }
    
    private void initialize(RequestHead head) {
        Route.Match route = findRoute(head);
        // This order is actually specified in javadoc of ExceptionHandler#apply
        request = createRequest(head, route);
        handler = findHandler(head, route);
    }
    
    private Route.Match findRoute(RequestHead rh) {
        return server.getRouteRegistry().lookup(rh.requestTarget());
    }
    
    private DefaultRequest createRequest(RequestHead rh, Route.Match rm) {
        return new DefaultRequest(rh, rm.parameters(), bytes, child);
    }
    
    private static Handler findHandler(RequestHead rh, Route.Match rm) {
        Handler h = rm.route().lookup(
                rh.method(),
                contentType(rh.headers()).orElse(null),
                accepts(rh.headers()));
        
        LOG.log(DEBUG, () -> "Matched handler: " + h);
        return h;
    }
    
    private CompletionStage<Response> invokeRequestHandler() {
        return handler.logic().apply(request);
    }
    
    private CompletionStage<Void> writeResponseToChannel(Response r) {
        ResponseBodySubscriber rbs = new ResponseBodySubscriber(r, child, server);
        r.body().subscribe(rbs);
        return rbs.asCompletionStage()
                  .thenRun(() -> {
                      if (r.mustCloseAfterWrite()) {
                          // TODO: Need to implement mustCloseAfterWrite( "mayInterrupt" param )
                          //       This will kill any ongoing subscription
                          bytes.close();
                      }
                  });
    }
    
    private void finish(Void Null, Throwable t) {
        if (t == null) {
            if (!child.isOpen()) {
                return;
            }
            
            // Else begin new HTTP exchange
            request.bodyDiscardIfNoSubscriber();
            request.bodyStage().whenComplete((Null2, t2) -> {
                if (t2 == null) {
                    // TODO: Possible recursion. Unroll.
                    new HttpExchange<>(server, child, bytes).begin();
                } else if (child.isOpen()) {
                    LOG.log(WARNING, "Expected ChannelByteBufferPublisher to have closed the channel already.");
                    server.orderlyShutdown(child);
                }
            });
        } else if (child.isOpen())  {
            if (eh == null) {
                eh = new ExceptionHandlers();
            }
            eh.resolve(t)
              .thenCompose(this::writeResponseToChannel)
              // TODO: Possible recursion. Unroll.
              .whenComplete(this::finish);
        } else {
            /*
             * Currently seeing a lot of ClosedPublisherException here. Because
             * a new HTTP exchange is indiscriminately started even if the other
             * side closes after the previous exchange, making
             * ChannelByteBufferPublisher reach EOS and consequently stop the
             * just-arrived RequestHeadSubscriber. The problem is that we should
             * make the connection life cycle much more solid; when is the
             * connection persistent and when is it not. No point in starting a
             * new exchange if we expect the connection to end. In fact, if
             * that's the case we should actually go ahead and close the channel!
             * Currently, if the other side never closes then we would end up
             * having idle zombie connections (!).
             * TODO: 1) Make connection life cycle solid and robust.
             *          (this logging statement should never happen?)
             * TODO: 2) Implement idle timeout.
             */
            LOG.log(DEBUG, () ->
                "HTTP exchange finished exceptionally and channel is closed. " +
                "Assuming reason was logged already.");
        }
    }
    
    private class ExceptionHandlers {
        private final List<Supplier<? extends ExceptionHandler>> factories;
        private List<ExceptionHandler> constructed;
        private Throwable prev;
        private int attemptCount;
        
        ExceptionHandlers() {
            this.factories = server.getExceptionHandlers();
            this.constructed = null;
            this.attemptCount = 0;
        }
        
        CompletionStage<Response> resolve(Throwable t) {
            final Throwable unpacked = unpackCompletionException(t);
            
            if (prev != null) {
                assert prev != unpacked;
                unpacked.addSuppressed(prev);
            }
            prev = unpacked;
            
            if (factories.isEmpty()) {
                return usingDefault(unpacked);
            }
            
            if (++attemptCount > server.getServerConfig().maxErrorRecoveryAttempts()) {
                LOG.log(WARNING, "Error recovery attempts depleted, will use default handler.");
                return usingDefault(unpacked);
            }
            
            LOG.log(DEBUG, () -> "Attempting error recovery #" + attemptCount);
            return usingHandlers(unpacked);
        }
        
        private CompletionStage<Response> usingDefault(Throwable t) {
            try {
                return ExceptionHandler.DEFAULT.apply(t, request, handler);
            } catch (Throwable next) {
                // Do not next.addSuppressed(unpacked); the first thing DEFAULT did was to log unpacked.
                LOG.log(ERROR, "Default exception handler failed.", next);
                return Responses.internalServerError().asCompletedStage();
            }
        }
        
        private CompletionStage<Response> usingHandlers(Throwable t) {
            for (int i = 0; i < factories.size(); ++i) {
                final ExceptionHandler h = cacheOrNew(i);
                try {
                    return requireNonNull(h.apply(t, request, handler));
                } catch (Throwable next) {
                    if (t != next) {
                        // New fail
                        return resolve(next);
                    } // else continue; Handler opted out
                }
            }
            
            // All handlers opted out
            return usingDefault(t);
        }
        
        private ExceptionHandler cacheOrNew(int handlerIndex) {
            final ExceptionHandler h;
            
            if (constructed == null) {
                constructed = new ArrayList<>();
            }
            
            assert factories instanceof RandomAccess;
            if (constructed.size() < handlerIndex + 1) {
                constructed.add(h = factories.get(handlerIndex).get());
            } else {
                h = constructed.get(handlerIndex);
            }
            
            return h;
        }
    }
    
    private static Throwable unpackCompletionException(Throwable t) {
        requireNonNull(t);
        if (!(t instanceof CompletionException)) {
            return t;
        }
        return t.getCause() == null ? t : unpackCompletionException(t.getCause());
    }
}