package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.ClosedPublisherException;
import alpha.nomagichttp.message.Request;

import java.util.concurrent.Flow;

import static alpha.nomagichttp.message.ClosedPublisherException.SIGNAL_FAILURE;
import static java.lang.System.Logger.Level.ERROR;
import static java.util.Objects.requireNonNull;

/**
 * On downstream signal failure; 1) log the exception and close the channel's
 * read stream, 2) pass the exception back to subscriber's {@code onError()} and
 * lastly 3) re-throw the exception.<p>
 * 
 * This behavior is specified by {@link Request.Body}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class OnErrorCloseReadStream<T> extends AbstractOp<T>
{
    private final DefaultChannelOperations child;
    
    private static final System.Logger LOG
            = System.getLogger(OnErrorCloseReadStream.class.getPackageName());
    
    protected OnErrorCloseReadStream(Flow.Publisher<? extends T> upstream, DefaultChannelOperations child) {
        super(upstream);
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
              * Note, this isn't the only place where the read stream is closed
              * on an exceptional signal return. See also
              * ChannelByteBufferPublisher.subscriberAnnounce().
              * 
              * This class guarantees the behavior though, as not all paths to
              * the subscriber run through the subscriberAnnounce() method.
             */
            if (child.isOpenForReading()) {
                LOG.log(ERROR, SIGNAL_FAILURE + " Will close the channel's read stream.", t);
                child.orderlyShutdownInputSafe();
            } // else assume whoever closed the stream also logged the exception
            
            signalError(new ClosedPublisherException(SIGNAL_FAILURE, t));
            throw t;
        }
    }
}