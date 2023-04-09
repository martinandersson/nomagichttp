package alpha.nomagichttp.testutil;

import alpha.nomagichttp.message.ByteBufferIterable;
import alpha.nomagichttp.util.ByteBuffers;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Arrays.stream;
import static java.util.Collections.unmodifiableList;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.joining;

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
        return getItems(bytes).stream()
                              .map(arr -> new String(arr, US_ASCII))
                              .collect(joining());
    }
    
    /**
     * Decodes all remaining bytes as a US-ASCII string, using a virtual thread.
     * 
     * @param bytes to decode
     * 
     * @return the decoded content
     * 
     * @throws ExecutionException
     *             if {@link #getString(ByteBufferIterable)} throws an exception
     * @throws InterruptedException
     *             if the current thread was interrupted while waiting
     * @throws TimeoutException
     *             if waiting more than 1 second for the result
     */
    public static String getStringVThread(ByteBufferIterable bytes)
            throws ExecutionException, InterruptedException, TimeoutException {
        try (var vThread = newVirtualThreadPerTaskExecutor()) {
            return vThread.submit(() -> getString(bytes)).get(1, SECONDS);
        }
    }
    
    /**
     * Reads one byte, using a virtual thread.
     * 
     * @param source to read from
     * 
     * @return the byte
     * 
     * @throws ExecutionException
     *             if the given source throws an exception
     * @throws InterruptedException
     *             if the current thread was interrupted while waiting
     * @throws TimeoutException
     *             if waiting more than 1 second for the result
     */
    public static byte getByteVThread(ByteBufferIterable source)
            throws ExecutionException, InterruptedException, TimeoutException {
        try (var vThread = newVirtualThreadPerTaskExecutor()) {
            return vThread.submit(() -> source.iterator().next().get())
                    .get(1, SECONDS);
        }
    }
    
    /**
     * Copies all iterated bytearrays.<p>
     * 
     * This method is intended for tests that need to assert the contents of the
     * source as well as how exactly the contents was iterated.
     * 
     * @param source to iterate
     * 
     * @return see JavaDoc
     */
    public static List<byte[]> getItems(ByteBufferIterable source) {
        var items = new ArrayList<byte[]>();
        var it = source.iterator();
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
            items.add(arr);
        }
        return unmodifiableList(items);
    }
    
    /**
     * Copies all iterated bytearrays, using a virtual thread.<p>
     * 
     * This method is intended for tests that need to assert the contents of the
     * source as well as how exactly the contents was iterated.
     * 
     * @param source to iterate
     * 
     * @return see JavaDoc
     * 
     * @throws ExecutionException
     *             if {@link #getItems(ByteBufferIterable)} throws an exception
     * @throws InterruptedException
     *             if the current thread was interrupted while waiting
     * @throws TimeoutException
     *             if waiting more than 1 second for the result
     */
    public static List<byte[]> getItemsVThread(ByteBufferIterable source)
            throws ExecutionException, InterruptedException, TimeoutException {
        try (var vThread = newVirtualThreadPerTaskExecutor()) {
            return vThread.submit(() -> getItems(source)).get(1, SECONDS);
        }
    }
}