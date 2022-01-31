package alpha.nomagichttp.testutil;

import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.util.PushPullUnicastPublisher;

import java.net.http.HttpRequest.BodyPublisher;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static alpha.nomagichttp.testutil.ByteBuffers.onRelease;
import static alpha.nomagichttp.testutil.ByteBuffers.toByteBufferPooled;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.drainItems;
import static alpha.nomagichttp.testutil.TestSubscribers.replaceOnNext;
import static alpha.nomagichttp.util.BetterBodyPublishers.asBodyPublisher;
import static java.util.Objects.requireNonNull;

/**
 * Arguably stupid publishers for test classes only.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class TestPublishers {
    private TestPublishers() {
        // Empty
    }
    
    /**
     * Returns a publisher that will block the subscriber thread on first
     * downstream request for demand until the subscription is cancelled.
     * 
     * @return a new publisher
     * @see #blockSubscriberUntil(Semaphore) 
     */
    public static BodyPublisher blockSubscriber() {
        return blockSubscriberUntil(new Semaphore(0));
    }
    
    /**
     * Returns a publisher that will block the subscriber thread on first
     * downstream request for demand until the given permit is released.<p>
     * 
     * The publisher will self-release a permit on downstream cancel. The
     * publisher does not interact with any methods of the subscriber except for
     * the initial {@code onSubscribe()}.<p>
     * 
     * Useful to test timeouts targeting the returned publisher. A timeout ought
     * to cancel the subscription and thus unblock the publisher. The test can
     * asynchronously release the permit however it sees fit.<p>
     * 
     * The {@code contentLength} is -1 (unknown).
     * 
     * @param permit release when blocking thread unblocks
     * 
     * @return a new publisher
     * @throws NullPointerException if {@code permit} is {@code null}
     */
    public static BodyPublisher blockSubscriberUntil(Semaphore permit) {
        requireNonNull(permit);
        return asBodyPublisher(s ->
            s.onSubscribe(new Flow.Subscription() {
                private final AtomicBoolean blocked = new AtomicBoolean();
                public void request(long n) {
                    if (!blocked.compareAndSet(false, true)) {
                        return;
                    }
                    try {
                        permit.acquire();
                    } catch (InterruptedException e) {
                    throw new RuntimeException(
                        "Interrupted while waiting on permit.", e);
                    }
                }
                public void cancel() {
                    permit.release();
                }
            }));
    }
    
    /**
     * Convert the upstream into a reusable publisher.<p>
     * 
     * This method will synchronously drain all items from the upstream which
     * are then released to the downstream as pooled bytebuffers. When the
     * downstream releases a bytebuffer and the buffer has bytes remaining,
     * it'll be re-released ahead of the queue.<p>
     * 
     * The upstream publisher must not publish items asynchronously in the
     * future.<p>
     * 
     * The returned publisher is semantically a dumb version of {@code
     * ChannelByteBufferPublisher}. The intended purpose is for testing a series
     * of subscribers cooperatively consuming a stream of bytes.
     * 
     * @param upstream see JavaDoc
     * @return see JavaDoc
     */
    public static Flow.Publisher<PooledByteBufferHolder> reusable(
            Flow.Publisher<ByteBuffer> upstream)
    {
        var items = new ConcurrentLinkedDeque<PooledByteBufferHolder>();
        
        var addFirst = new Consumer<ByteBuffer>() {
            public void accept(ByteBuffer buf) {
                if (buf.hasRemaining()) {
                    items.addFirst(onRelease(buf, this));
                }
            }
        };
        
        drainItems(upstream).stream()
                .map(buf -> onRelease(buf, addFirst))
                .forEach(items::add);
        
        var noMore = toByteBufferPooled("");
        items.add(noMore);
        
        var pub = PushPullUnicastPublisher.reusable(
                items::poll, pb -> addFirst.accept(pb.get()));
        
        return subscriber -> pub.subscribe(
                replaceOnNext(subscriber, buf -> {
                    if (buf == noMore) {
                        pub.complete();
                        items.add(noMore);
                    } else {
                        subscriber.onNext(buf);
                    }
                }));
    }
}