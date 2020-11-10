package alpha.nomagichttp.internal;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.Flow;

import static java.util.Arrays.stream;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toCollection;

final class StringPublisher implements Flow.Publisher<String>
{
    private final Queue<String> items;
    
    StringPublisher(String... items) {
        this.items = stream(items).collect(toCollection(ArrayDeque::new));
    }
    
    @Override
    public void subscribe(Flow.Subscriber<? super String> subscriber) {
        requireNonNull(subscriber);
        
        SerialTransferService<String> service = new SerialTransferService<>(
                new NullReturningIterator<>(items.iterator())::next,
                subscriber::onNext);
        
        Flow.Subscription subscription = new Flow.Subscription(){
            @Override
            public void request(long n) {
                try {
                    service.increaseDemand(n);
                } catch (IllegalArgumentException e) {
                    subscriber.onError(e);
                }
            }
            
            @Override
            public void cancel() {
                service.finish();
            }
        };
        
        subscriber.onSubscribe(subscription);
    }
    
    private static class NullReturningIterator<T> implements Iterator<T> {
        private final Iterator<T> delegate;
        
        NullReturningIterator(Iterator<T> delegate) {
            this.delegate = delegate;
        }
        
        @Override
        public boolean hasNext() {
            return delegate.hasNext();
        }
        
        @Override
        public T next() {
            try {
                return delegate.next();
            } catch (NoSuchElementException e) {
                return null;
            }
        }
    }
}