package alpha.nomagichttp.internal;

/**
 * Namespace for functions that throw checked exceptions.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class Throwing {
    private Throwing() {
        // Intentionally empty
    }
    
    /**
     * A value supplier that may throw an exception.
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     * @param <T> the type of shit that can happen
     */
    @FunctionalInterface
    interface Supplier<T, X extends Exception> {
        /**
         * Gets a result.
         * 
         * @return a result
         * @throws X if shit happens
         */
        T get() throws X;
    }
    
    /**
     * A value consumer that may throw an exception.
     * 
     * @param <T> the type of the input to the operation
     * @param <X> the type of shit that can happen
     */
    @FunctionalInterface
    interface Consumer<T, X extends Exception> {
        /**
         * Gets a result.
         * 
         * @param t value to consume
         * @throws X if shit happens
         */
        void accept(T t) throws X;
    }
}