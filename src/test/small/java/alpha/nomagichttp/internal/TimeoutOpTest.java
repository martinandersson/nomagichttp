package alpha.nomagichttp.internal;

import alpha.nomagichttp.testutil.MemorizingSubscriber.Signal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static alpha.nomagichttp.testutil.Assertions.assertPublisherIsEmpty;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_ERROR;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_SUBSCRIBE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.drainSignalsAsync;
import static alpha.nomagichttp.testutil.TestPublishers.blockSubscriber;
import static alpha.nomagichttp.util.Publishers.empty;
import static java.time.Duration.ofMillis;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Small tests for {@link TimeoutOp}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class TimeoutOpTest
{
    @Test
    void timeoutEmpty_flow() {
        assertPublisherIsEmpty(new TimeoutOp.Flow<>(
            false, false, empty(), ofMillis(0), AssertionError::new));
    }
    
    @Test
    void timeoutEmpty_pub() {
        assertPublisherIsEmpty(new TimeoutOp.Pub<>(
            false, false, empty(), ofMillis(0), AssertionError::new));
    }
    
    @Test
    void timeout_pub() throws ExecutionException, InterruptedException, TimeoutException {
        Throwable err = new RuntimeException("msg");
        var op = new TimeoutOp.Pub<>(false, false, blockSubscriber(), ofMillis(0), () -> err);
        List<Signal> s = drainSignalsAsync(op)
                .toCompletableFuture().get(1, SECONDS);
        assertThat(s).hasSize(2);
        assertSame(s.get(0).methodName(), ON_SUBSCRIBE);
        assertSame(s.get(1).methodName(), ON_ERROR);
        assertSame(s.get(1).argument(),   err);
    }
}