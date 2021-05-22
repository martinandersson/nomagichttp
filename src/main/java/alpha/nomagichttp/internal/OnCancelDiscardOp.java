package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.PooledByteBufferHolder;

import java.nio.ByteBuffer;
import java.util.concurrent.Flow;

import static java.lang.Long.MAX_VALUE;

/**
 * Upon receiving a cancel signal from downstream, instead of propagating the
 * signal upstream, this operator switches out the downstream subscriber into
 * logic that proceeds to discard all remaining bytes of all received
 * bytebuffers until no more bytebuffers are received from the upstream.<p>
 * 
 * With "discard" means that the bytebuffer's position will be set to its limit
 * (i.e. no more remaining bytes!) and then released. Hence this operator should
 * be attached <strong>after</strong> other operators that modifies the
 * bytebuffer view (or we risk clearing bytes that crosses over a message
 * boundary) and <strong>never</strong> to an infinite stream (by definition
 * pretty pointless).<p>
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
        if (!discarding && tryShutdown()) {
            discarding = true;
            trySubscribeToUpstream();
            fromDownstreamRequest(MAX_VALUE);
        }
    }
    
    private static void discard(PooledByteBufferHolder item) {
        ByteBuffer b = item.get();
        b.position(b.limit());
    }
}