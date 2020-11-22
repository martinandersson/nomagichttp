package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.ClosedPublisherException;
import alpha.nomagichttp.message.Request;

import java.nio.channels.AsynchronousSocketChannel;
import java.util.concurrent.Flow;

import static alpha.nomagichttp.message.ClosedPublisherException.SIGNAL_FAILURE;
import static java.lang.System.Logger.Level.ERROR;
import static java.util.Objects.requireNonNull;

/**
 * On downstream signal failure; log the exception and close the channel, then
 * pass the exception back to subscriber.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Request.Body
 */
final class OnErrorCloseChannelOp<T> extends AbstractOp<T>
{
    private final DefaultServer server;
    private final AsynchronousSocketChannel child;
    
    private static final System.Logger LOG
            = System.getLogger(OnErrorCloseChannelOp.class.getPackageName());
    
    protected OnErrorCloseChannelOp(
            Flow.Publisher<? extends T> upstream,
            DefaultServer server,
            AsynchronousSocketChannel child)
    {
        super(upstream);
        this.server = requireNonNull(server);
        this.child  = requireNonNull(child);
    }
    
    @Override
    protected void fromUpstreamNext(T item) {
        interceptThrowable(() -> signalNext(item));
    }
    
    @Override
    protected void fromUpstreamComplete() {
        interceptThrowable(this::signalComplete);
    }
    
    private void interceptThrowable(Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            /*
              * Note, this isn't the only place where the child is closed on an
              * exceptional signal return. See also
              * ChannelByteBufferPublisher.subscriberAnnounce().
              * 
              * This class guarantees the behavior though, as not all paths to
              * the subscriber run through the subscriberAnnounce() method.
             */
            if (child.isOpen()) {
                LOG.log(ERROR, SIGNAL_FAILURE + " Will close the channel.", t);
                server.orderlyShutdown(child);
            } // else assume whoever closed the channel also logged the exception
            
            signalError(new ClosedPublisherException(SIGNAL_FAILURE, t));
            throw t;
        }
    }
}