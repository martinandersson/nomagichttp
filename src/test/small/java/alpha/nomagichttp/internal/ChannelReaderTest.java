package alpha.nomagichttp.internal;

import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.handler.EndOfStreamException;
import alpha.nomagichttp.util.ScopedValues;
import alpha.nomagichttp.util.Throwing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static alpha.nomagichttp.testutil.ByteBufferIterables.getItemsVThread;
import static alpha.nomagichttp.testutil.ByteBufferIterables.getByteVThread;
import static alpha.nomagichttp.testutil.ByteBufferIterables.getStringVThread;
import static alpha.nomagichttp.testutil.ReadableByteChannels.ofString;
import static alpha.nomagichttp.util.DummyScopedValue.where;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Small tests for {@link ChannelReader}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ChannelReaderTest
{
    ChannelReader testee;
    
    @BeforeEach
    void beforeEach() throws Exception {
        testee = new ChannelReader(ofString("abc"));
        // Does not throw Exc
        assertTesteeLengthIs(-1);
    }
    
    // Two consecutive iterations; both reads from a length-limited testee
    @Test
    void limitedThenLimited()
            throws ExecutionException, InterruptedException, TimeoutException
    {
        // Limit next iterable to the first char
        testee.limit(1);
        assertTesteeLengthIs(1);
        
        // Reads "a"
        assertTesteeYieldsJoined("a");
        assertTesteeLengthIs(0);
        
        // Prepare for the next iterable
        testee.reset();
        assertTesteeLengthIs(-1);
        testee.limit(2);
        assertTesteeLengthIs(2);
        
        // Read the rest
        assertTesteeYieldsJoined("bc");
        assertTesteeLengthIs(0);
    }
    
    @Test
    void unlimitedThenLimited()
            throws ExecutionException, InterruptedException, TimeoutException
    {
        assertTesteeLengthIs(-1);
        assertNextCharIs('a');
        testee.limit(2);
        assertTesteeLengthIs(2);
        // This would've worked:
//        assertTesteeYieldsJoined("bc");
        // But, wish to emphasize that the iteration does not end with EOS,
        // because testee is limited (see next test case).
        assertTesteeYieldsExactly(new byte[]{'b', 'c'});
        assertTesteeLengthIs(0);
    }
    
    @Test
    void limitedThenUnlimited() throws Exception {
        testee.limit(2);
        assertTesteeYieldsJoined("ab");
        testee.reset();
        assertTesteeLengthIs(-1);
        var ch = withChannelMock(() ->
            // Empty byte[] is the EOS sentinel
            assertTesteeYieldsExactly(new byte[]{'c'}, new byte[]{}));
        verify(ch).shutdownInput();
    }
    
    @Test
    void limitSetToLessThanViewRemaining()
            throws ExecutionException, InterruptedException, TimeoutException
    {
        assertNextCharIs('a');
        testee.limit(1);
        assertTesteeLengthIs(1);
        assertNextCharIs('b');
        assertTesteeLengthIs(0);
        testee.reset();
        assertNextCharIs('c');
    }
    
    @Test
    void EndOfStreamException() throws Exception {
        // Exc is thrown only if we had a limit residue when observing EOS
        testee.limit(4);
        var ch = withChannelMock(() ->
            assertThatThrownBy(this::assertTesteeYieldsExactly)
                .isExactlyInstanceOf(ExecutionException.class)
                .hasMessage("alpha.nomagichttp.handler.EndOfStreamException")
                .hasNoSuppressedExceptions()
                .cause()
                    .isExactlyInstanceOf(EndOfStreamException.class)
                    .hasMessage(null)
                    .hasNoSuppressedExceptions()
                    .hasNoCause());
        verify(ch).shutdownInput();
    }
    
    @Test
    void IllegalArgumentException() {
        assertThatThrownBy(() -> testee.limit(-1))
            .isExactlyInstanceOf(IllegalArgumentException.class)
            .hasMessage("Negative limit: -1")
            .hasNoSuppressedExceptions()
            .hasNoCause();
    }
    
    @Test
    void UnsupportedOperationException() {
        testee.limit(123);
        assertThatThrownBy(() -> testee.limit(456))
            .isExactlyInstanceOf(UnsupportedOperationException.class)
            .hasMessage("Limit is already set.")
            .hasNoSuppressedExceptions()
            .hasNoCause();
    }
    
    @Test
    void IllegalStateException() {
        assertThatThrownBy(() -> testee.dismiss())
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessage("Is not empty.")
            .hasNoSuppressedExceptions()
            .hasNoCause();
    }
    
    private void assertTesteeLengthIs(long expected)
            throws ExecutionException, InterruptedException, TimeoutException {
        assertThat(testee.length()).isEqualTo(expected);
        if (expected == -1 || expected >= 1) {
            assertThat(testee.isEmpty()).isFalse();
        } else if (expected == 0) {
            assertThat(testee.isEmpty()).isTrue();
            assertTesteeYieldsExactly(); // Nothing
        } else {
            throw new IllegalArgumentException("Less than -1: " + expected);
        }
    }
    
    private void assertTesteeYieldsJoined(String expected)
            throws ExecutionException, InterruptedException, TimeoutException {
        assertThat(getStringVThread(testee)).isEqualTo(expected);
    }
    
    private void assertTesteeYieldsExactly(byte[]... items)
            throws ExecutionException, InterruptedException, TimeoutException {
        var expected = List.of(items);
        var actual = getItemsVThread(testee);
        // AssertJ's asserThat and JUnit's asserIterableEquals fails lol
        assertArrayEquals(expected.toArray(), actual.toArray());
    }
    
    private void assertNextCharIs(char c)
            throws ExecutionException, InterruptedException, TimeoutException {
        assertThat((int) getByteVThread(testee)).isEqualTo(c);
    }
    
    private static ClientChannel withChannelMock(
          Throwing.Runnable<? extends Exception> assertions) throws Exception {
        var ch = mock(ClientChannel.class);
        when(ch.isInputOpen()).thenReturn(true);
        where(ScopedValues.__CHANNEL, ch, () -> {
            assertions.run();
            return null;
        });
        return ch;
    }
}