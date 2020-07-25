package alpha.nomagichttp.internal;

import alpha.nomagichttp.ExceptionHandler;
import alpha.nomagichttp.Server;
import alpha.nomagichttp.ServerConfig;
import alpha.nomagichttp.handler.Handler;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.route.RouteRegistry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.nio.channels.ShutdownChannelGroupException;
import java.util.OptionalLong;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import static alpha.nomagichttp.message.Headers.accepts;
import static alpha.nomagichttp.message.Headers.contentLength;
import static alpha.nomagichttp.message.Headers.contentType;
import static alpha.nomagichttp.message.Publishers.empty;
import static java.lang.System.Logger;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;

/**
 * Handles accepted (new) client/child connections.<p>
 * 
 * The way this class handles the channel (client connection) is to setup an
 * asynchronous and continuous flow of anticipated HTTP exchanges.<p>
 * 
 * Logically, the channel is transformed into a {@link ChannelBytePublisher}
 * which is a {@link Flow.Publisher Flow.Publisher&lt;ByteBuffer&gt;}. This
 * publisher will never complete for as long as the channel remains open.<p>
 * 
 * The first subscriber to subscribe to the channel is {@link RequestHeadParser}
 * which is setup by this class. Once the request head has been parsed, an
 * application-provided {@link Handler} will be resolved and called with a
 * {@link DefaultRequest} through which the channel byte publisher is exposed.
 * The handler will then be able to subscribe to the channel and consume the
 * request body.<p>
 * 
 * The request handler will return the response body in the form of a
 * {@code Flow.Publisher<ByteBuffer>} to which this class subscribes a
 * {@link ResponseToChannelWriter}. The response subscriber will write the
 * response head- and body to the channel.<p>
 * 
 * Once the response subscription completes, the entire flow is restarted.<p>
 * 
 * Please note that even though this flow is very "HTTP/1.1 exchange"-centric,
 * underlying components can (scratch that, "should") be easily adaptable to
 * other designs such as a "pipeline". See api-note in {@link
 * AbstractUnicastPublisher}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// TODO: This class shouldn't implement CompletionHandler.
final class OnAccept implements CompletionHandler<AsynchronousSocketChannel, Void>
{
    private static final Logger LOG = System.getLogger(OnAccept.class.getPackageName());
    
    private final AsynchronousServerSocketChannel listener;
    private final ExceptionHandler onError;
    private final RouteRegistry routes;
    private final ServerConfig config;
    
    OnAccept(AsynchronousServerSocketChannel listener, ExceptionHandler onError, Server server) {
        this.listener = listener;
        this.onError  = onError;
        this.routes   = server.getRouteRegistry();
        this.config   = server.getServerConfig();
        
        listener.accept(null, this);
    }
    
    @Override
    public void completed(AsynchronousSocketChannel child, Void attachmentIgnored) {
        // TODO: Wrap everything in error handling.
        
        LOG.log(DEBUG, () -> "Accepted: " + child);
        listener.accept(null, this);
        
        // TODO: child.setOption(StandardSocketOptions.SO_KEEPALIVE, true); ??
        
        PooledByteBufferPublisher bytebuffers = new ChannelBytePublisher(child);
        
        new RequestHeadParser(bytebuffers, config.maxRequestHeadSize())
                .asCompletionStage()
                .whenComplete((head, exc) -> {
                    if (exc != null) {
                        dealWithError(exc, child, null);
                    } else {
                        callHandler(head, child, bytebuffers);
                    }
                });
    }
    
    @Override
    public void failed(Throwable exc, Void attachment) {
        if (exc instanceof ClosedChannelException) {
            LOG.log(DEBUG, "Group already closed when initiating a new accept. Will accept no more.");
        }
        else if (exc instanceof IOException && exc.getCause() instanceof ShutdownChannelGroupException) {
            LOG.log(DEBUG, "Connection accepted and immediately closed, because group is shutting down/was shutdown. Will accept no more.");
        }
        else {
            // TODO: We wanna catch error here and shut down server?
            onError.apply(exc);
        }
    }
    
    private void dealWithError(Throwable exc, Channel child, Handler handler) {
        try {
            onError.apply(exc);
        }
        catch (Throwable t) {
            // TODO: Move somewhere else
            LOG.log(ERROR, "Exception handler threw a throwable, will close child socket.", t);
            try {
                child.close();
            } catch (IOException e1) {
                LOG.log(ERROR, "Failed to close child socket. Will close server (reduce security risk).", e1);
                try {
                    listener.close();
                } catch (IOException e2) {
                    LOG.log(ERROR, "Failed to close server. Will exit application (reduce security risk).", e2);
                    System.exit(1);
                }
            }
            
            throw t;
        }
    }
    
    private void callHandler(RequestHead head, AsynchronousSocketChannel child, PooledByteBufferPublisher bytebuffers) {
        final Route.Match match;
        final Handler handler;
        
        try {
            match = routes.lookup(head.requestTarget());
            // TODO: From hereon we call route-level exception handler for errors
            // And he should have access to Request. Has even been specified in BadMediaTypeSyntaxException.
            handler = match.route().lookup(
                    head.method(),
                    contentType(head.headers()).orElse(null),
                    accepts(head.headers()));
            
            LOG.log(DEBUG, () -> "Matched handler: " + handler);
        } catch (Throwable t) {
            dealWithError(t, child, null);
            return;
        }
        
        try {
            // TODO: If length is not present, then body is possibly chunked.
            // https://tools.ietf.org/html/rfc7230#section-3.3.3
            
            // TODO: Server should throw ParseException if Content-Length is present AND Content-Encoding
            // https://tools.ietf.org/html/rfc7230#section-3.3.2
            
            OptionalLong len = contentLength(head.headers());
            
            Flow.Publisher<ByteBuffer> requestBody = len.isPresent() ?
                    new LimitedBytePublisher(bytebuffers, len.getAsLong()) : empty();
            
            Request request = new DefaultRequest(head, match.parameters(), requestBody);
            CompletionStage<Response> response = handler.logic().apply(request);
            
            response.whenComplete((resp, exc) -> {
                if (exc != null) {
                    dealWithError(exc, child, handler);
                } else {
                    new ResponseToChannelWriter(child, resp).asCompletionStage()
                            .whenComplete((Void, exc2) -> {
                                if (exc2 != null) {
                                    dealWithError(exc2, child, handler);
                                } else {
                                    // TODO: Restart HTTP exchange flow
                                    LOG.log(INFO, "TODO: Restart flow.");
                                }
                            });
                }
            });
        } catch (Throwable t) {
            dealWithError(t, child, handler);
        }
    }
}