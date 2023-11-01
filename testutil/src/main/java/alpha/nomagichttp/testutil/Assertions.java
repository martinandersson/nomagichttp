package alpha.nomagichttp.testutil;

import alpha.nomagichttp.message.BetterHeaders;
import alpha.nomagichttp.message.HasHeaders;
import alpha.nomagichttp.message.ResourceByteBufferIterable;
import alpha.nomagichttp.util.FileLockTimeoutException;
import org.assertj.core.api.MapAssert;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

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
     * {@return a {@code MapAssert} of the given headers}<p>
     * 
     * This method is equivalent to:
     * <pre>
     *   {@link #assertHeaders(BetterHeaders) assertHeaders
     *   }(thing.{@link HasHeaders#headers() headers}())
     * </pre>
     * 
     * @param thing to get headers from
     */
    public static MapAssert<String, List<String>> assertHeaders(
            HasHeaders thing) {
        return assertHeaders(thing.headers());
    }
    
    /**
     * {@return a {@code MapAssert} of the given {@code headers}}<p>
     * 
     * The headers are copied into a {@code LinkedHashMap}; i.e. the returned
     * API may be used to assert both <i>order and case-sensitive header
     * names</i>.
     * 
     * @param headers to assert
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
     * @throws FileLockTimeoutException
     *             when a blocking operation times out
     * @throws IOException
     *             on I/O error
     */
    public static void assertIterable(
            ResourceByteBufferIterable iterable,
            ByteBuffer first, ByteBuffer... more)
            throws InterruptedException, FileLockTimeoutException, IOException {
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