package alpha.nomagichttp.util;

import java.util.stream.Stream;

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
}