package alpha.nomagichttp.util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Provides a convenient baseclass for immutable builder implementations.<p>
 * 
 * Builders are backwards-linked in a chain and the only real state they each
 * store is a modifying action, which is replayed against a mutable state
 * container during {@link #constructState(Supplier) construction time}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @param <S> mutable state container
 */
public abstract class AbstractImmutableBuilder<S> {
    private final AbstractImmutableBuilder<S> prev;
    private final Consumer<? super S> modifier;
    
    /**
     * Construct an {@code AbstractImmutableBuilder} root.
     */
    protected AbstractImmutableBuilder() {
        this.prev = null;
        this.modifier = null;
    }
    
    /**
     * Construct an {@code AbstractImmutableBuilder} leaf.
     * 
     * @param prev previous builder
     * @param modifier action to apply on mutable state
     * @throws NullPointerException if any arg is {@code null}
     */
    protected AbstractImmutableBuilder(AbstractImmutableBuilder<S> prev, Consumer<? super S> modifier) {
        this.prev = requireNonNull(prev);
        this.modifier = requireNonNull(modifier);
    }
    
    /**
     * Construct the mutable state container and play all modifiers against it.
     * <p>
     * 
     * It is expected that the concrete builder's {@code build()} method call
     * this method to get the state which is then transferred to the constructor
     * of the built object. The state object can either be copied by the built
     * object or referenced directly for "value lookup".
     * 
     * @param factory of state
     * @return the populated state
     */
    protected final S constructState(Supplier<? extends S> factory) {
        Deque<Consumer<? super S>> mods = new ArrayDeque<>();
        
        for (var b = this; b.modifier != null; b = b.prev) {
            mods.addFirst(b.modifier);
        }
        
        S s = factory.get();
        mods.forEach(m -> m.accept(s));
        return s;
    }
}
