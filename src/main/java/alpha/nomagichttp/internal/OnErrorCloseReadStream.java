package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.util.SubscriberFailedException;

import java.util.concurrent.Flow;

import static java.lang.System.Logger.Level.ERROR;
import static java.util.Objects.requireNonNull;

/**
 * On downstream {@code onNext} failure; 1) close the channel's read stream, 2)
 * pass the exception back to subscriber's {@code onError()} and lastly 3)
 * re-throw the exception.<p>
 * 
 * This behavior is specified by {@link Request.Body}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class OnErrorCloseReadStream<T> extends AbstractOp<T>
{
    private final DefaultClientChannel child;
    
    private static final System.Logger LOG
            = System.getLogger(OnErrorCloseReadStream.class.getPackageName());
    
    protected OnErrorCloseReadStream(Flow.Publisher<? extends T> upstream, DefaultClientChannel child) {
        super(upstream);
        this.child  = requireNonNull(child);
    }
    
    @Override
    protected void fromUpstreamNext(T item) {
        try {
            signalNext(item);
        } catch (Throwable t) {
            /*
             * Note, this isn't the only place where the read stream is closed
             * on an exceptional signal return. See also
             * ChannelByteBufferPublisher.subscriberAnnounce().
             * 
             * This class guarantees the behavior though, as not all paths to
             * the subscriber run through the subscriberAnnounce() method.
             */
            var e = SubscriberFailedException.onNext(t);
            if (child.isOpenForReading()) {
                LOG.log(ERROR, e.getMessage() + " Will close the channel's read stream.");
                child.shutdownInputSafe();
            } // else assume whoever closed the stream also logged the exception
            signalError(e);
            throw t;
        }
    }
}