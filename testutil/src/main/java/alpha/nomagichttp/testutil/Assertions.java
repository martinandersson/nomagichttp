package alpha.nomagichttp.testutil;

import alpha.nomagichttp.message.BetterHeaders;
import alpha.nomagichttp.message.HeaderHolder;
import alpha.nomagichttp.message.ResourceByteBufferIterable;
import org.assertj.core.api.MapAssert;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static alpha.nomagichttp.testutil.Headers.linkedHashMap;
import static alpha.nomagichttp.util.Streams.stream;
import static java.nio.ByteBuffer.allocate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Assertion utils.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class Assertions {
    private Assertions() {
        // Empty
    }
    
    /**
     * Returns a {@code MapAssert} of the given headers.<p>
     * 
     * This method is equivalent to:
     * <pre>
     *   {@link #assertHeaders(BetterHeaders) assertHeaders
     *   }(holder.{@link HeaderHolder#headers() headers}())
     * </pre>
     * 
     * @param holder to get headers from
     * 
     * @return see JavaDoc
     */
    public static MapAssert<String, List<String>> assertHeaders(
            HeaderHolder holder) {
        return assertHeaders(holder.headers());
    }
    
    /**
     * Returns a {@code MapAssert} of the given {@code headers}.<p>
     * 
     * The headers are copied into a {@code LinkedHashMap}; i.e. the returned
     * API may be used to assert both <i>order and case-sensitive header
     * names</i>.
     * 
     * @param headers to assert
     * 
     * @return see JavaDoc
     */
    public static MapAssert<String, List<String>> assertHeaders(
            BetterHeaders headers) {
        return assertThat(linkedHashMap(headers));
    }
    
    /**
     * Asserts that the iterated items are equal to the expected.<p>
     * 
     * The assertion will fail if:
     * 
     * <ul>
     *   <li>Number of iterated items are fewer or more</li>
     *   <li>Iterated items are not {@link ByteBuffer#equals(Object) equal}
     *       (in order)</li>
     *   <li>A known {@link ResourceByteBufferIterable#length() length} is not
     *       the same as the sum of all remaining bytes in the expected</li>
     * </ul>
     * 
     * @param iterable to iterate
     * @param first item expected
     * @param more items expected
     * 
     * @throws InterruptedException
     *             if interrupted while waiting on something
     * @throws TimeoutException
     *             when a blocking operation times out
     * @throws IOException
     *             on I/O error
     */
    public static void assertIterable(
            ResourceByteBufferIterable iterable,
            ByteBuffer first, ByteBuffer... more)
            throws InterruptedException, TimeoutException, IOException {
        long len;
        var actual = new ArrayList<>();
        try (var it = iterable.iterator()) {
            len = iterable.length();
            while (it.hasNext()) {
                var buf = it.next();
                var copy = allocate(buf.remaining());
                while (buf.hasRemaining()) {
                    copy.put(buf.get());
                }
                actual.add(copy.flip());
            }
        }
        var expected = stream(first, more).toList();
        if (len >= 0) {
            assertThat(len).isEqualTo(
                    expected.stream().mapToInt(ByteBuffer::remaining).sum());
        }
        assertThat(actual).isEqualTo(expected);
    }
}