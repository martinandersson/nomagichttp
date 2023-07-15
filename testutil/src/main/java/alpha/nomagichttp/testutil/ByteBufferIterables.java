package alpha.nomagichttp.testutil;

import alpha.nomagichttp.message.ByteBufferIterable;
import alpha.nomagichttp.util.ByteBuffers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static alpha.nomagichttp.testutil.VThreads.getUsingVThread;
import static alpha.nomagichttp.util.Blah.throwsNoChecked;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Arrays.stream;
import static java.util.Collections.unmodifiableList;
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
     * 
     * @return see JavaDoc
     * 
     * @throws NullPointerException
     *             if {@code items} is {@code null}
     */
    public static ByteBufferIterable just(String... items) {
        return alpha.nomagichttp.util.ByteBufferIterables.just(
                stream(items).map(ByteBuffers::asciiBytes).toList());
    }
    
    /**
     * Decodes all remaining bytes as a US-ASCII string.<p>
     * 
     * This method assumes that the source does not throw an
     * {@code IOException}, and will translate such an exception into an
     * {@code AssertionError}.
     * 
     * @param bytes to decode
     * 
     * @return the decoded content
     * 
     * @throws NullPointerException
     *             if {@code bytes} is {@code null}
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
     * @throws NullPointerException
     *             if {@code bytes} is {@code null}
     * @throws InterruptedException
     *             if the calling thread is interrupted while waiting
     * @throws ExecutionException
     *             if {@link #getString(ByteBufferIterable)} throws an exception
     * @throws TimeoutException
     *             if {@code getString} takes longer than 1 second
     */
    public static String getStringVThread(ByteBufferIterable bytes)
            throws InterruptedException, ExecutionException, TimeoutException {
        return getUsingVThread(() -> getString(bytes));
    }
    
    /**
     * Reads one byte, using a virtual thread.
     * 
     * @param source to read from
     * 
     * @return the byte
     * 
     * @throws NullPointerException
     *             if {@code source} is {@code null}
     * @throws InterruptedException
     *             if the calling thread is interrupted while waiting
     * @throws ExecutionException
     *             if the given {@code source} throws an exception
     * @throws TimeoutException
     *             if waiting more than 1 second for the result
     */
    public static byte getByteVThread(ByteBufferIterable source)
            throws InterruptedException, ExecutionException, TimeoutException {
        return getUsingVThread(() -> source.iterator().next().get());
    }
    
    /**
     * Copies all iterated bytearrays.<p>
     * 
     * This method is intended for tests that need to assert the content of the
     * source as well as how exactly the content was iterated.<p>
     * 
     * This method assumes that the source does not throw an
     * {@code IOException}, and will translate such an exception into an
     * {@code AssertionError}.
     * 
     * @param source to iterate
     * 
     * @return see JavaDoc
     * 
     * @throws NullPointerException
     *             if {@code source} is {@code null}
     */
    public static List<byte[]> getItems(ByteBufferIterable source) {
        var items = new ArrayList<byte[]>();
        var it = source.iterator();
        while (it.hasNext()) {
            var buf = throwsNoChecked(it::next);
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
     * source as well as how exactly the content was iterated.
     * 
     * @param source to iterate
     * 
     * @return see JavaDoc
     * 
     * @throws NullPointerException
     *             if {@code source} is {@code null}
     * @throws InterruptedException
     *             if the calling thread is interrupted while waiting
     * @throws ExecutionException
     *             if {@link #getItems(ByteBufferIterable)} throws an exception
     * @throws TimeoutException
     *             if {@code getItems} takes longer than 1 second
     */
    public static List<byte[]> getItemsVThread(ByteBufferIterable source)
            throws ExecutionException, InterruptedException, TimeoutException {
        return getUsingVThread(() -> getItems(source));
    }
}