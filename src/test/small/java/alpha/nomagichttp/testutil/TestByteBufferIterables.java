package alpha.nomagichttp.testutil;

import alpha.nomagichttp.message.ByteBufferIterable;
import alpha.nomagichttp.util.Blah;
import alpha.nomagichttp.util.ByteBufferIterables;

import static java.util.Arrays.stream;

/**
 * Special bytebuffer iterables for testing.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class TestByteBufferIterables {
    private TestByteBufferIterables() {
        // Empty
    }
    
    /**
     * Returns a bytebuffer iterable of US-ASCII decoded strings.
     * 
     * @param items contents
     * @return see JavaDoc
     */
    public static ByteBufferIterable just(String... items) {
        return ByteBufferIterables.just(
                stream(items).map(Blah::asciiBytes).toList());
    }
}