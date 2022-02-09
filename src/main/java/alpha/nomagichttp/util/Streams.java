package alpha.nomagichttp.util;

import java.util.List;
import java.util.stream.Stream;

import static java.util.stream.Stream.of;

/**
 * Utils for working with streams.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class Streams {
    private Streams() {
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
     * Combine the given arguments into a List.<p>
     * 
     * The returned list is unmodifiable.
     * 
     * @param <T> the type of elements in the list
     * @param first element
     * @param more elements
     * @return a list
     */
    @SafeVarargs
    public static <T> List<T> toList(T first, T... more) {
        @SuppressWarnings("varargs")
        var s = stream(first, more);
        return s.toList();
    }
}