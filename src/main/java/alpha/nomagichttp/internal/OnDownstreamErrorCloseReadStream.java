package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.util.Publishers;

import java.util.concurrent.Flow;

import static java.lang.System.Logger.Level.ERROR;
import static java.util.Objects.requireNonNull;

/**
 * On downstream {@code onNext} failure, close the channel's read stream.<p>
 * 
 * This behavior is specified by {@link Request.Body}, and also implemented
 * partially already by the channel itself (see {@code
 * ChannelByteBufferPublisher#subscriberAnnounce()}). Problem is though that not
 * all paths to the subscriber necessarily run through the channel's announce
 * method. For example, an empty request body delegates to {@link
 * Publishers#empty()}). Another example is the subscriber himself implicitly
 * doing the delivery through increasing his demand. But with this class in the
 * body's operator chain, the behavior is fully guaranteed.<p>
 * 
 * We wouldn't necessarily have to close the read-stream if the body was empty.
 * But, the subscriber isn't supposed to fail in the first place. The combo
 * empty body plus subscriber failure is simply put extremely rare and the extra
 * complexity this branching would have in docs and code alike is just not worth
 * it.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class OnDownstreamErrorCloseReadStream<T> extends AbstractOp<T>
{
    private final DefaultClientChannel chApi;
    
    private static final System.Logger LOG
            = System.getLogger(OnDownstreamErrorCloseReadStream.class.getPackageName());
    
    OnDownstreamErrorCloseReadStream(
            Flow.Publisher<? extends T> upstream, DefaultClientChannel chApi)
    {
        super(upstream);
        this.chApi  = requireNonNull(chApi);
    }
    
    @Override
    protected void fromUpstreamNext(T item) {
        try {
            signalNext(item);
        } catch (Throwable t) {
            if (chApi.isOpenForReading()) {
                LOG.log(ERROR,
                    "Signalling Flow.Subscriber.onNext() failed. " +
                    "Will close the channel's read stream.");
                chApi.shutdownInputSafe();
            }
            throw t;
        }
    }
}