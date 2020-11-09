package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.PooledByteBufferHolder;

import java.io.IOException;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import static java.util.Objects.requireNonNull;

/**
 * Subscribes to bytebuffers that is asynchronously written to a file
 * channel.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class FileSubscriber implements Flow.Subscriber<PooledByteBufferHolder>
{
    /*
     * Currently, subscribes to- and writes only one bytebuffer at a time. This
     * makes keeping track of the file position pretty simple.
     * 
     * AsynchronousFileChannel does allow multiple write operations to be
     * outstanding, but I'd say it's doubtful if having multiple write
     * operations running concurrently would yield a performance gain,
     * especially in the light of our environment where the source is a network
     * channel which we can pretty much assume is always going to send us stuff
     * in a slower pace than one local write operation.
     * 
     * Even if the network channel would outrun the single operation, past
     * experience has told me personally that concurrent disk I/O yields very
     * little in performance gain and can sometimes even be detrimental.
     * 
     * Okay, so performance tests and/or sound research could potentially hint a
     * performance boost using concurrent writes. I would still be hesitant to
     * implement it. Because having 1 request spur N number of concurrent
     * server-side processes is always a pretty dangerous path to take. The
     * server is already running requests in parallel, perhaps even a large
     * number of them.
     * 
     * In fact, ideally, we would love to know and set a limit to the level of
     * "optimal disk concurrency" and then implement a strategy to distribute
     * our resources fairly amongst file-receiving requests. Refusing to spur
     * more than 1 concurrent write per request is actually a good start.
     */
    
    private final AsynchronousFileChannel file;
    private final CompletableFuture<Long> result;
    private final Writer writer;
    private Flow.Subscription subscription;
    private long bytesWritten;
    
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
        requireNonNull(subscription);
        if (this.subscription != null) {
            throw new IllegalStateException("No support for subscriber re-use.");
        }
        this.subscription = subscription;
        subscription.request(1);
    }
    
    @Override
    public void onNext(PooledByteBufferHolder item) {
        file.write(item.get(), bytesWritten, item, writer);
    }
    
    @Override
    public void onError(Throwable t) {
        try {
            file.close();
        } catch (IOException next) {
            t.addSuppressed(next);
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
    
    private class Writer implements CompletionHandler<Integer, PooledByteBufferHolder>
    {
        @Override
        public void completed(Integer result, PooledByteBufferHolder item) {
            bytesWritten += result;
            item.release();
            subscription.request(1);
        }
        
        @Override
        public void failed(Throwable t, PooledByteBufferHolder item) {
            item.release();
            result.completeExceptionally(t);
        }
    }
}