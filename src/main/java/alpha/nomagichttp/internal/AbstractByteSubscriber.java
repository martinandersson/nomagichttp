package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.Char;
import alpha.nomagichttp.message.PooledByteBufferHolder;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import static java.lang.Long.MAX_VALUE;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;

/**
 * A subscriber of bytebuffers parsed into a complex result.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
abstract class AbstractByteSubscriber<R>
        implements SubscriberWithResult<PooledByteBufferHolder, R>
{
    private static final System.Logger LOG
            = System.getLogger(AbstractByteSubscriber.class.getPackageName());
    
    private final CompletableFuture<R> res = new CompletableFuture<>();
    private Flow.Subscription subsc;
    private int read;
    
    @Override
    public final CompletionStage<R> result() {
        return res;
    }
    
    @Override
    public final void onSubscribe(Flow.Subscription s) {
        this.subsc = SubscriberWithResult.validate(this.subsc, s);
        s.request(MAX_VALUE);
    }
    
    @Override
    public final void onNext(PooledByteBufferHolder item) {
        var buf = item.get();
        R r = null;
        while (buf.hasRemaining() && r == null) {
            byte b = buf.get();
            ++read;
            LOG.log(DEBUG, () ->
                "[Parsing] pos=" + read() + ", byte=\"" + Char.toDebugString((char) b) + "\"");
            try {
                r = parse(b);
            } catch (Throwable t) {
                subsc.cancel();
                res.completeExceptionally(t);
                break;
            }
        }
        item.release();
        if (r != null) {
            subsc.cancel();
            res.complete(r);
        }
    }
    
    /**
     * Returns the number of bytes read so far.
     * 
     * @return the number of bytes read so far
     */
    final int read() {
        return read;
    }
    
    /**
     * Parse a byte from upstream.
     * 
     * @param b to be parsed
     * @return the final result type or {@code null} if more bytes are needed
     */
    protected abstract R parse(byte b);
    
    /**
     * Overridable for exception translation.<p>
     * 
     * If overridden, subclass must call through to super.
     * 
     * @param t upstream error
     */
    @Override
    public void onError(Throwable t) {
        if (!res.completeExceptionally(t)) {
            LOG.log(WARNING, "Failed to deliver this error downstream.", t);
        }
    }
    
    @Override
    public final void onComplete() {
        var msg = "Unexpected: Channel closed gracefully before parser was done.";
        if (!res.completeExceptionally(new AssertionError(msg))) {
            LOG.log(WARNING, msg);
        }
    }
}