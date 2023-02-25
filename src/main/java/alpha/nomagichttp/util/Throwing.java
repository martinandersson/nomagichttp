package alpha.nomagichttp.util;

/**
 * Namespace for functions that throw checked exceptions.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// TODO: Consider to not type the exception, just do Exception (makes RequestHandler simpler)
public final class Throwing {
    private Throwing() {
        // Empty
    }
    
    /**
     * Represents an operation that does not return a result.
     * 
     * @param <X> the type of problem that can happen
     */
    @FunctionalInterface
    public interface Runnable<X extends Exception> {
        /**
         * Runs this operation.
         * 
         * @throws X should be documented by implementation
         */
        void run() throws X;
    }
    
    /**
     * A value consumer that may throw an exception.
     * 
     * @param <T> the type of the input to the operation
     * @param <X> the type of problem that can happen
     */
    @FunctionalInterface
    public interface Consumer<T, X extends Exception> {
        /**
         * Gets a result.
         * 
         * @param t value to consume
         * @throws X should be documented by implementation
         */
        void accept(T t) throws X;
    }
    
    /**
     * A value supplier that may throw an exception.
     * 
     * @param <T> the result type
     * @param <X> the type of problem that can happen
     */
    @FunctionalInterface
    public interface Supplier<T, X extends Exception> {
        /**
         * Gets a result.
         * 
         * @return a result
         * @throws X should be documented by implementation
         */
        T get() throws X;
    }
    
    /**
     * Represents a function that accepts one argument and produces a result.
     * 
     * @param <T> the type of the input to the function
     * @param <R> the type of the result of the function
     * @param <X> the type of problem that can happen
     */
    public interface Function<T, R, X extends Exception> {
        /**
         * Applies this function to the given argument.
         * 
         * @param t the function argument
         * @return the function result
         * @throws X should be documented by implementation
         */
        R apply(T t) throws X;
    }
    
    /**
     * Represents a function that accepts two arguments and produces a result.
     * 
     * @param <T> the type of the first argument to the function
     * @param <U> the type of the second argument to the function
     * @param <R> the type of the result of the function
     * @param <X> the type of problem that can happen
     */
    public interface BiFunction<T, U, R, X extends Exception> {
        /**
         * Applies this function to the given arguments.
         * 
         * @param t the first function argument
         * @param u the second function argument
         * @return the function result
         * @throws X should be documented by implementation
         */
        R apply(T t, U u) throws X;
    }
}