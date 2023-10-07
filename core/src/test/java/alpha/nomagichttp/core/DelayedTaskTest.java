package alpha.nomagichttp.core;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.ScheduledFuture;

import static java.time.Duration.ofDays;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DelayedTask}.
 */
final class DelayedTaskTest {
    @Test
    void everything() {
        // This doesn't matter...
        final long DELAY = 123;
        // ...because we're not actually going to do anything
        final Runnable NOP = () -> {};
        // The static method Timeout.schedule() needs to return
        final ScheduledFuture<?> NON_NULL = mock(ScheduledFuture.class);
        // And we need to capture the lambda inside instance method schedule(),
        // because we'll "fake run" the task ourselves
        final var callRunAction = ArgumentCaptor.forClass(Runnable.class);
        
        try (var closeThisThing = mockStatic(DelayedTask.class)) {
             when(DelayedTask.schedule(anyLong(), callRunAction.capture()))
               .thenAnswer(warningsIfUseThenReturn -> NON_NULL);
             
             var testee = new DelayedTask(ofDays(DELAY), NOP);
             // Can "abort" before scheduling
             assertTrue(testee.tryAbort());
             testee.schedule();
             // Can abort several times
             assertTrue(testee.tryAbort());
             assertTrue(testee.tryAbort());
             // Cannot abort after execution
             testee.schedule();
             callRunAction.getValue().run();
             assertFalse(testee.tryAbort());
        }
    }
}
