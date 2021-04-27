package alpha.nomagichttp.testutil;

import java.io.IOException;

/**
 * A {@code Supplier} that may throw an {@code IOException}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
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