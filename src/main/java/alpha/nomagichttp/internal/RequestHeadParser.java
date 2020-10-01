package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.Char;
import alpha.nomagichttp.message.MaxRequestHeadSizeExceededException;
import alpha.nomagichttp.message.PooledByteBufferHolder;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import static java.lang.System.Logger.Level.DEBUG;

// TODO: Should we have a timeout for how long we allow a client to send the head?
//       Or do we accept that server+client may have an "infinitely" slow connection?
//       Why we might want to add such a limit is because the connection might be
//       fast but the request bad, so our parser get's "stuck waiting" on a a particular
//       token that never arrives. An alternative would be to have a timeout that gets
//       cleared on each new read, almost like a heartbeat. So, if we don't get a single
//       byte despite us waiting for bytes, then we timeout.

final class RequestHeadParser implements Flow.Subscriber<PooledByteBufferHolder>
{
    private static final System.Logger LOG = System.getLogger(RequestHeadParser.class.getPackageName());
    
    private final int maxHeadSize;
    private final RequestHeadProcessor processor;
    private final CompletableFuture<RequestHead> result;
    
    RequestHeadParser(int maxRequestHeadSize) {
        maxHeadSize = maxRequestHeadSize;
        processor   = new RequestHeadProcessor();
        result      = new CompletableFuture<>();
    }
    
    CompletionStage<RequestHead> asCompletionStage() {
        return result.minimalCompletionStage();
    }
    
    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        (sub = subscription).request(Long.MAX_VALUE);
    }
    
    // Flow.Subscriber implementation
    // ---
    
    private Flow.Subscription sub;
    private int read;
    
    @Override
    public void onNext(PooledByteBufferHolder item) {
        final RequestHead head;
        
        try {
            head = process(item.get());
        } catch (Throwable t) {
            sub.cancel();
            result.completeExceptionally(t);
            return;
        } finally {
            item.release();
        }
        
        if (head != null) {
            sub.cancel();
            result.complete(head);
        }
    }
    
    private RequestHead process(ByteBuffer buf) {
        RequestHead head = null;
        
        while (buf.hasRemaining() && head == null) {
            char curr = (char) buf.get();
            LOG.log(DEBUG, () -> "pos=" + read + ", curr=\"" + Char.toDebugString(curr) + "\"");
            
            if (++read > maxHeadSize) {
                throw new MaxRequestHeadSizeExceededException();
            }
            
            head = processor.accept(curr);
        }
        
        return head;
    }
    
    @Override
    public void onError(Throwable t) {
        result.completeExceptionally(t);
    }
    
    @Override
    public void onComplete() {
        // Accept that he is shutting down. Reason must have already been logged.
    }
}