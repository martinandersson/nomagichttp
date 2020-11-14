package alpha.nomagichttp.internal;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.Flow;

import static java.util.Arrays.stream;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toCollection;

/**
 * Publishes a set of eagerly constructed items to each new subscriber.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ReplayPublisher<T> implements Flow.Publisher<T>
{
    private final Queue<T> items;
    
    @SafeVarargs
    ReplayPublisher(T... items) {
        this.items = stream(items).collect(toCollection(ArrayDeque::new));
    }
    
    @Override
    public void subscribe(Flow.Subscriber<? super T> subscriber) {
        requireNonNull(subscriber);
        
        SerialTransferService<T> service = new SerialTransferService<>(
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
    
    private static class NullReturningIterator<E> implements Iterator<E> {
        private final Iterator<E> delegate;
        
        NullReturningIterator(Iterator<E> delegate) {
            this.delegate = delegate;
        }
        
        @Override
        public boolean hasNext() {
            return delegate.hasNext();
        }
        
        @Override
        public E next() {
            try {
                return delegate.next();
            } catch (NoSuchElementException e) {
                return null;
            }
        }
    }
}