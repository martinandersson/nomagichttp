package alpha.nomagichttp.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.RandomAccess;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.unmodifiableList;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Stream.of;

/**
 * Utils for working with arrays.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class Arrays {
    private Arrays() {
        // Empty
    }
    
    /**
     * Stream the given arguments.
     * 
     * @param first arg
     * @param more args
     * @param <T> type of element
     * 
     * @return a stream
     */
    @SafeVarargs
    public static <T> Stream<T> stream(T first, T... more) {
        @SuppressWarnings("varargs")
        var m = java.util.Arrays.stream(more);
        return Stream.concat(of(first), m);
    }
    
    /**
     * Stream the given arguments.
     * 
     * @param first arg
     * @param second arg
     * @param more args
     * @param <T> type of element
     * 
     * @return a stream
     */
    @SafeVarargs
    public static <T> Stream<T> stream(T first, T second, T... more) {
        @SuppressWarnings("varargs")
        var m = java.util.Arrays.stream(more);
        return Stream.concat(of(first, second), m);
    }
    
    /**
     * Collect all elements of the stream to a {@link RandomAccess} list which
     * is also unmodifiable.<p>
     * 
     * This method is semantically the same as using a {@link
     * Collectors#toUnmodifiableList()}, except with a capacity hint and no
     * unnecessary copying.
     * 
     * @param initialCapacity of sink
     * @param stream source
     * @param <T> element type
     * @return see JavaDoc
     */
    public static <T> List<T> randomAndUnmodifiable(
            int initialCapacity, Stream<? extends T> stream)
    {
        return unmodifiableList(stream.collect(toCollection(
                () -> new ArrayList<>(initialCapacity))));
    }
}