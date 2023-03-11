package alpha.nomagichttp.testutil;

import alpha.nomagichttp.message.ByteBufferIterable;
import alpha.nomagichttp.util.ByteBuffers;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Arrays.stream;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Utils for {@code ByteBufferIterable}s.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class ByteBufferIterables {
    private ByteBufferIterables() {
        // Empty
    }
    
    /**
     * Returns a bytebuffer iterable of US-ASCII decoded strings.
     * 
     * @param items contents
     * @return see JavaDoc
     */
    public static ByteBufferIterable just(String... items) {
        return alpha.nomagichttp.util.ByteBufferIterables.just(
                stream(items).map(ByteBuffers::asciiBytes).toList());
    }
    
    /**
     * Decodes all remaining bytes as a US-ASCII string.
     * 
     * @param bytes to decode
     * 
     * @return the decoded content
     */
    public static String getString(ByteBufferIterable bytes) {
        var b = new StringBuilder();
        var it = bytes.iterator();
        while (it.hasNext()) {
            final ByteBuffer buf;
            try {
                buf = it.next();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            // buf is read-only, can't do buf.array()
            var arr = new byte[buf.remaining()];
            buf.get(arr);
            b.append(new String(arr, US_ASCII));
        }
        return b.toString();
    }
    
    /**
     * Decodes all remaining bytes as a US-ASCII string, using a virtual thread.
     * 
     * @param bytes to decode
     * 
     * @return the decoded content
     */
    public static String getStringVThread(ByteBufferIterable bytes)
            throws ExecutionException, InterruptedException, TimeoutException {
        try (var exec = newVirtualThreadPerTaskExecutor()) {
            return exec.submit(() -> getString(bytes)).get(1, SECONDS);
        }
    }
    
    /**
     * Reads one byte, using a virtual thread.
     * 
     * @param source to read from
     * 
     * @return the byte
     */
    public static byte getNextByteVThread(ByteBufferIterable source)
            throws ExecutionException, InterruptedException, TimeoutException {
        try (var exec = newVirtualThreadPerTaskExecutor()) {
            return exec.submit(() -> source.iterator().next().get())
                    .get(1, SECONDS);
        }
    }
}