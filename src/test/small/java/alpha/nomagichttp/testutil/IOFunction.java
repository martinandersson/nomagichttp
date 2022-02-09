package alpha.nomagichttp.testutil;

import java.io.IOException;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * A {@link Function} that may throw an {@code IOException}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
@FunctionalInterface
public interface IOFunction<T, R> {
    /**
     * Applies this function to the given argument.
     * 
     * @param t the function argument
     * @return the function result
     * @throws IOException if an I/O error occurs
     */
    R apply(T t) throws IOException;
    
    /**
     * Equivalent to {@link Function#andThen(Function)}.
     * 
     * @param <V> the type of output of the {@code after} function,
     * and of the composed function
     * @param after the function to apply after this function is applied
     * @return a composed function that first applies this function and then
     * applies the {@code after} function
     * 
     * @throws NullPointerException if after is null
     */
    default <V> IOFunction<T, V> andThen(IOFunction<? super R, ? extends V> after) {
        requireNonNull(after);
        return (T t) -> after.apply(apply(t));
    }
}