package alpha.nomagichttp.events;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * This is the three-arity specialization of {@link Consumer}.<p>
 * 
 * @param <T> the type of the first argument to the operation
 * @param <U> the type of the second argument to the operation
 * @param <V> the type of the second argument to the operation
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
@FunctionalInterface
public interface TriConsumer<T, U, V> {
    /**
     * Performs this operation on the given arguments.
     * 
     * @param t the first input argument
     * @param u the second input argument
     * @param v the third input argument
     */
    void accept(T t, U u, V v);
    
    /**
     * Returns a composed {@code TriConsumer} that performs, in sequence, this
     * operation followed by the {@code after} operation. If performing either
     * operation throws an exception, it is relayed to the caller of the
     * composed operation.  If performing this operation throws an exception,
     * the {@code after} operation will not be performed.
     * 
     * @param after the operation to perform after this operation
     * @return a composed {@code TriConsumer} that performs in sequence this
     * operation followed by the {@code after} operation
     * @throws NullPointerException if {@code after} is null
     */
    default TriConsumer<T, U, V> andThen(TriConsumer<? super T, ? super U, ? super V> after) {
        Objects.requireNonNull(after);
        return (t, u, v) -> {
            accept(t, u, v);
            after.accept(t, u, v);
        };
    }
}