package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.Char;
import alpha.nomagichttp.message.MaxRequestHeadSizeExceededException;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import static java.lang.System.Logger.Level.DEBUG;

// TODO: Rename to RequestHeadParser

// TODO: Should we have a timeout for how long we allow a client to send the head?
//       Or do we accept that server+client may have an "infinitely" slow connection?
//       Why we might want to add such a limit is because the connection might be
//       fast but the request bad, so our parser get's "stuck waiting" on a a particular
//       token that never arrives. An alternative would be to have a timeout that gets
//       cleared on each new read, almost like a heartbeat. So, if we don't get a single
//       byte despite us waiting for bytes, then we timeout.

final class HeadParser 
{
    private static final System.Logger LOG = System.getLogger(HeadParser.class.getPackageName());
    
    private final PooledByteBufferPublisher publisher;
    private final int maxHeadSize;
    private final CompletableFuture<Head> result;
    private final HeadProcessor processor;
    
    HeadParser(PooledByteBufferPublisher from, int maxRequestHeadSize) {
        publisher   = from;
        maxHeadSize = maxRequestHeadSize;
        result      = new CompletableFuture<>();
        processor   = new HeadProcessor();
        
        publisher.subscribe(new Subscriber());
    }
    
    CompletionStage<Head> asCompletionStage() {
        return result.minimalCompletionStage();
    }
    
    private final class Subscriber implements Flow.Subscriber<ByteBuffer> {
        private Flow.Subscription sub;
        
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            (sub = subscription).request(Long.MAX_VALUE);
        }
        
        int read;
        
        @Override
        public void onNext(ByteBuffer buff) {
            try {
                onNext0(buff);
            } catch (Throwable t) {
                sub.cancel();
                result.completeExceptionally(t);
            } finally {
                publisher.release(buff);
            }
        }
        
        private void onNext0(ByteBuffer buff) {
            Head finished = null;
            
            while (buff.hasRemaining() && finished == null) {
                char curr = (char) buff.get();
                LOG.log(DEBUG, () -> "pos=" + read + ", curr=\"" + Char.toDebugString(curr) + "\"");
                
                if (++read > maxHeadSize) {
                    throw new MaxRequestHeadSizeExceededException();
                }
                
                finished = processor.accept(curr);
            }
            
            if (finished != null) {
                sub.cancel();
                result.complete(finished);
            }
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
}