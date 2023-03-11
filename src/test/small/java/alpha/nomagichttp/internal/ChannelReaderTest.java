package alpha.nomagichttp.internal;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static alpha.nomagichttp.testutil.ReadableByteChannels.ofString;
import static alpha.nomagichttp.testutil.TestByteBufferIterables.getStringVThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Small tests for {@link ChannelReader}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ChannelReaderTest
{
    @Test
    void twoIterations()
            throws ExecutionException, InterruptedException, TimeoutException
    {
        // Setup
        var testee = new ChannelReader(ofString("abc"));
        assertThat(testee.isEmpty()).isFalse();
        assertThat(testee.length()).isEqualTo(-1);
        
        // Limit next iterable to the first char
        testee.limit(1);
        assertThat(testee.length()).isEqualTo(1);
        
        // Read "a"
        assertThat(getStringVThread(testee)).isEqualTo("a");
        assertThat(testee.isEmpty()).isTrue();
        assertThat(testee.length()).isEqualTo(0);
        
        // Reset for the next iterable
        testee.reset();
        assertThat(testee.isEmpty()).isFalse();
        assertThat(testee.length()).isEqualTo(-1);
        testee.limit(2);
        assertThat(testee.length()).isEqualTo(2);
        
        // Read "bc"
        assertThat(getStringVThread(testee)).isEqualTo("bc");
        assertThat(testee.isEmpty()).isTrue();
        assertThat(testee.length()).isEqualTo(0);
    }
}