package alpha.nomagichttp.testutil;

import java.net.http.HttpRequest.BodyPublisher;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;

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
     * release a permit asynchronously however it sees fit.
     * 
     * @param permit release when blocking thread unblocks
     * 
     * @return a new publisher
     * @throws NullPointerException if {@code permit} is {@code null}
     */
    public static BodyPublisher blockSubscriberUntil(Semaphore permit) {
        requireNonNull(permit);
        return asBodyPublisher(s -> s.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {
                try {
                    permit.acquire();
                } catch (InterruptedException e) {
                    throw new RuntimeException("Interrupted while waiting on permit.", e);
                }
            }
            
            @Override
            public void cancel() {
                permit.release();
            }
        }));
    }
}