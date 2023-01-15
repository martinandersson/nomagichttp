package alpha.nomagichttp.testutil;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * A {@link Supplier} that may throw an {@code IOException}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @param <T> the type of results supplied by this supplier
 */
@FunctionalInterface
public interface IOSupplier<T> {
    /**
     * Gets a result.
     * 
     * @return a result
     * 
     * @throws IOException if shit happens
     */
    T get() throws IOException;
}