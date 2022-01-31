package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.PooledByteBufferHolder;

import java.util.concurrent.Flow;

import static java.lang.Long.MAX_VALUE;
import static java.lang.System.Logger.Level.DEBUG;

/**
 * Upon receiving a cancel signal from downstream, instead of propagating the
 * signal upstream, this operator replaces the downstream subscriber for logic
 * that discards all received bytebuffers from the upstream until finish.<p>
 * 
 * This operator should be attached <strong>after</strong> other operators that
 * modifies the bytebuffer view (or we risk clearing bytes that crosses over a
 * message boundary) and <strong>never</strong> to an infinite stream (by
 * definition pretty pointless).<p>
 * 
 * Is used by the server's request thread to make sure that if and when the
 * application's body subscription is prematurely cancelled, the read-position
 * in the underlying channel is moved forward so that the next request line
 * subscriber starts at a valid position.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @see PooledByteBufferHolder#discard() 
 */
final class OnCancelDiscardOp extends AbstractOp<PooledByteBufferHolder>
{
    private static final System.Logger LOG
            = System.getLogger(OnCancelDiscardOp.class.getPackageName());
    
    /*
     * TODO
     * A potential improvement is to compute the number of bytes that needs to
     * be discarded and if this number is sufficient; close the channel
     * (re-establishing a new connection assumed to be faster than accepting the
     * rest of a large message that we don't care about).
     * 
     * AND also, if HttpExchange.prepareForNewExchange() can figure out there
     * will be no subsequent exchange from the same channel, then instead of
     * discarding - whatever the size - he can just close the channel.
     */
    
    private volatile boolean discarding;
    
    OnCancelDiscardOp(Flow.Publisher<? extends PooledByteBufferHolder> upstream) {
        super(upstream);
    }
    
    @Override
    protected void fromUpstreamNext(PooledByteBufferHolder item) {
        if (discarding) {
            item.discard();
        } else {
            super.fromUpstreamNext(item);
        }
    }
    
    @Override
    protected void fromDownstreamCancel() {
        // Replace cancel signal (no call to super)
        discarding = true;
    }
    
    /**
     * If no subscriber is active, shutdown the operator and start
     * discarding.<p>
     * 
     * Is NOP if already discarding.
     */
    void discardIfNoSubscriber() {
        if (tryShutdown()) {
            discarding = true;
            LOG.log(DEBUG, "Switched to discarding mode");
            trySubscribeToUpstream();
            fromDownstreamRequest(MAX_VALUE);
        }
    }
}