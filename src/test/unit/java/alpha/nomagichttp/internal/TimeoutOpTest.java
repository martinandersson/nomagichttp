package alpha.nomagichttp.internal;

import alpha.nomagichttp.testutil.MemorizingSubscriber;
import alpha.nomagichttp.testutil.MemorizingSubscriber.Signal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Flow;

import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_COMPLETE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_SUBSCRIBE;
import static alpha.nomagichttp.util.Publishers.empty;
import static java.time.Duration.ofMillis;
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
        assertCompletesNormally(
                new TimeoutOp.Flow<>(empty(), ofMillis(0), AssertionError::new));
    }
    
    @Test
    void timeoutEmpty_pub() {
        assertCompletesNormally(
                new TimeoutOp.Pub<>(empty(), ofMillis(0), AssertionError::new));
    }
    
    private void assertCompletesNormally(Flow.Publisher<?> op) {
        List<Signal> s = MemorizingSubscriber.drainSignals(op);
        assertThat(s).hasSize(2);
        assertSame(s.get(0).getMethodName(), ON_SUBSCRIBE);
        assertSame(s.get(1).getMethodName(), ON_COMPLETE);
    }
}