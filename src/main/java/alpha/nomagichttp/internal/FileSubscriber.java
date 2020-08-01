package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.PooledByteBufferHolder;

import java.io.IOException;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import static java.lang.Long.MAX_VALUE;

/**
 * Subscribes to bytebuffers that is asynchronously written to a file channel.
 * On each write operation completion, the bytebuffer is released.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class FileSubscriber implements Flow.Subscriber<PooledByteBufferHolder>
{
    private final AsynchronousFileChannel file;
    private final CompletableFuture<Long> result;
    private final Writer writer;
    private volatile long bytesWritten;
    
    FileSubscriber(AsynchronousFileChannel file) {
        this.file = file;
        this.writer = new Writer();
        this.result = new CompletableFuture<>();
    }
    
    CompletionStage<Long> asCompletionStage() {
        return result.minimalCompletionStage();
    }
    
    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        if (result.isDone()) {
            subscription.cancel();
            throw new IllegalStateException("No support for subscriber re-use.");
        }
        
        subscription.request(MAX_VALUE);
    }
    
    @Override
    public void onNext(PooledByteBufferHolder item) {
        file.write(item.get(), bytesWritten, item, writer);
    }
    
    @Override
    public void onError(Throwable t) {
        try {
            file.close();
        } catch (IOException e) {
            e.addSuppressed(t);
            result.completeExceptionally(e);
            return;
        }
        result.completeExceptionally(t);
    }
    
    @Override
    public void onComplete() {
        try {
            file.close();
        } catch (IOException e) {
            result.completeExceptionally(e);
            return;
        }
        result.complete(bytesWritten);
    }
    
    private class Writer implements CompletionHandler<Integer, PooledByteBufferHolder> {
        
        @Override
        public void completed(Integer result, PooledByteBufferHolder item) {
            long bw = bytesWritten;
            bw += result;
            bytesWritten = bw;
            item.release();
        }
        
        @Override
        public void failed(Throwable t, PooledByteBufferHolder item) {
            try {
                result.completeExceptionally(t);
            } finally {
                item.release();
            }
        }
    }
}