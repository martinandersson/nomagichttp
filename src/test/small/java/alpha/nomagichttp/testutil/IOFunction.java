package alpha.nomagichttp.testutil;

import java.io.IOException;
import java.util.function.Function;

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
}