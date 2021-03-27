package alpha.nomagichttp.internal;

import java.nio.channels.AsynchronousChannelGroup;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static alpha.nomagichttp.internal.AtomicReferences.lazyInit;

/**
 * Manager of a global {@link AsynchronousChannelGroup}.<p>
 * 
 * If creating the group fails, then a new group can never be created again.
 * A new group can only be created if an old group successfully shutdown.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class AsyncGroup
{
    // Good info on async groups:
    // https://openjdk.java.net/projects/nio/resources/AsynchronousIo.html
    
    private static final AtomicReference<CompletableFuture<AsynchronousChannelGroup>>
            holder = new AtomicReference<>();
    
    static CompletionStage<AsynchronousChannelGroup> getOrCreate(int nThreads) {
        return lazyInit(holder, CompletableFuture::new, v -> {
            try {
                v.complete(
                    AsynchronousChannelGroup.withFixedThreadPool(nThreads,
                    // Default-group uses daemon threads, we use non-daemon
                    Executors.defaultThreadFactory()));
            } catch (Throwable t) {
                v.completeExceptionally(t);
            }
        });
    }
    
    static void shutdown() {
        CompletableFuture<AsynchronousChannelGroup> res = holder.get();
        if (res != null) {
            res.thenAccept(grp -> {
                grp.shutdown();
                holder.compareAndSet(res, null);
            });
        }
    }
}