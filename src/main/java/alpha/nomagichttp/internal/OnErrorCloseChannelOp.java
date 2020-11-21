package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.ClosedPublisherException;
import alpha.nomagichttp.message.Request;

import java.nio.channels.Channel;
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
    private final Channel child;
    
    private static final System.Logger LOG
            = System.getLogger(OnErrorCloseChannelOp.class.getPackageName());
    
    protected OnErrorCloseChannelOp(
            Flow.Publisher<? extends T> upstream, DefaultServer server, Channel child)
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
            if (child.isOpen()) {
                LOG.log(ERROR, SIGNAL_FAILURE + " Will close the channel.", t);
                server.orderlyShutdown(child);
            }
            signalError(new ClosedPublisherException(SIGNAL_FAILURE, t));
            throw t;
        }
    }
}