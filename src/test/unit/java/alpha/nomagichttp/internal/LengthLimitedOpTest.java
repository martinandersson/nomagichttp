package alpha.nomagichttp.internal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.function.Function;

import static java.lang.Long.MAX_VALUE;
import static java.util.Arrays.stream;
import static org.assertj.core.api.Assertions.assertThat;

class LengthLimitedOpTest
{
    @Test
    void all() {
        // Publish int values 1 and 2, each in a bytebuffer,
        // limit operator to 8 bytes; both values received.
        assertThat(engine(8, 1, 2)).containsExactly(1, 2);
    }
    
    @Test
    void half() {
        // Publish 1 and 2, but limit to capacity of 1 buffer, only first item received
        assertThat(engine(4, 1, 2)).containsExactly(1);
    }
    
    @Test
    void none() {
        // 0 limit = no items received
        assertThat(engine(0, 1, 2)).isEmpty();
    }
    
    private static List<Integer> engine(long testeeMaxBytes, Integer... valuesToPublish) {
        DefaultPooledByteBufferHolder[] items = stream(valuesToPublish)
                .map(PooledByteBuffers::wrap)
                .toArray(DefaultPooledByteBufferHolder[]::new);
        
        LengthLimitedOp testee = new LengthLimitedOp(testeeMaxBytes, new ReplayPublisher<>(items));
        
        final List<Integer> sink = new ArrayList<>();
        testee.subscribe(new CollectIntFromHolderSubscriber(sink));
        return sink;
    }
    
    private static class CollectIntFromHolderSubscriber
            extends CollectingSubscriber<DefaultPooledByteBufferHolder, Integer>
    {
        CollectIntFromHolderSubscriber(Collection<Integer> sink) {
            super(sink, h -> {
                int v = h.get().getInt();
                h.release();
                return v;
            });
        }
    }
    
    private static class CollectingSubscriber<T, R> implements Flow.Subscriber<T> {
        private final Collection<? super R> sink;
        private final Function<? super T, ? extends R> transformer;
        
        CollectingSubscriber(Collection<? super R> sink, Function<? super T, ? extends R> transformer) {
            this.sink = sink;
            this.transformer = transformer;
        }
        
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(MAX_VALUE);
        }
        
        @Override
        public void onNext(T item) {
            sink.add(transformer.apply(item));
        }
        
        @Override
        public void onError(Throwable throwable) {
            // Empty
        }
        
        @Override
        public void onComplete() {
            // Empty
        }
    }
}