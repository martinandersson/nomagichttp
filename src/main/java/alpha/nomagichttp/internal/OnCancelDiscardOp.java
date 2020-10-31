package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.PooledByteBufferHolder;

import java.util.concurrent.Flow;

import static java.lang.Long.MAX_VALUE;

/**
 * Upon receiving a cancel signal from downstream, instead of propagating the
 * signal upstream, this operator switches out the downstream subscriber into
 * logic that proceeds to discard all remaining bytes of all received
 * bytebuffers until no more bytebuffers are received from the upstream.<p>
 * 
 * With "discard" means that the bytebuffer will be cleared and then released.
 * Hence this operator should be attached <strong>after</strong> other operators
 * that modifies the bytebuffer view <strong>and</strong> after any publisher
 * that makes the subscription finite (upstream must at some point complete the
 * subscription). Otherwise we could end up clearing bytes that crosses over a
 * message boundary or end up forever discarding bytes (by definition pretty
 * pointless).<p>
 * 
 * Is used by the server's request thread to make sure that if and when the
 * application's body subscription is prematurely cancelled, the read-position
 * in the underlying channel is moved forward so that the next request head
 * parser starts at a valid position. A potential improvement is to compute the
 * number of bytes that needs to be discarded and if this number is sufficient
 * enough; close the channel (re-establishing a new connection assumed to be
 * faster than accepting the rest of a large message that we don't care about).
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class OnCancelDiscardOp extends AbstractOp<PooledByteBufferHolder>
{
    private volatile boolean discarding;
    
    protected OnCancelDiscardOp(Flow.Publisher<? extends PooledByteBufferHolder> upstream) {
        super(upstream);
    }
    
    @Override
    protected void fromUpstreamNext(PooledByteBufferHolder item) {
        if (discarding) {
            discard(item);
            item.release();
        } else {
            item.onRelease(readCountIgnored -> {
                if (discarding) discard(item); });
            super.fromUpstreamNext(item);
        }
    }
    
    @Override
    protected void fromDownstreamCancel() {
        // Replace cancel signal
        start();
    }
    
    /**
     * If no subscriber is active, shutdown the operator and start
     * discarding.<p>
     * 
     * Is NOP if already discarding.
     */
    void discardIfNoSubscriber() {
        if (!discarding && tryShutdown()) {
            start();
        }
    }
    
    private void start() {
        discarding = true;
        fromDownstreamRequest(MAX_VALUE);
    }
    
    private static void discard(PooledByteBufferHolder item) {
        item.get().clear();
    }
}