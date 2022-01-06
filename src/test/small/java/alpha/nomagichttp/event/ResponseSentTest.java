package alpha.nomagichttp.event;

import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Small tests for {@link ResponseSent}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ResponseSentTest {
    @Test
    void simple() {
        long start = 0, stop = MILLISECONDS.toNanos(1_000), bytes = 3;
        var testee = new ResponseSent.Stats(start, stop, bytes);
        
        assertThat(testee.nanoTimeOnStart()).isEqualTo(start);
        assertThat(testee.nanoTimeOnStop()).isEqualTo(stop);
        assertThat(testee.byteCount()).isEqualTo(bytes);
        
        assertThat(testee.hashCode()).isEqualTo(testee.hashCode());
        assertThat(testee).isEqualTo(testee);
        assertThat(testee.toString()).isEqualTo("ResponseSent.Stats{start=0, stop=1000000000, byteCount=3}");
        
        assertThat(testee.elapsedNanos()).isEqualTo(stop);
        assertThat(testee.elapsedMillis()).isEqualTo(1_000);
        assertThat(testee.elapsedDuration().toNanos()).isEqualTo(stop);
    }
}