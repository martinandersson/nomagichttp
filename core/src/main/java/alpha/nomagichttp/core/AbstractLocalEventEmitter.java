package alpha.nomagichttp.core;

import alpha.nomagichttp.event.AbstractEventEmitter;
import alpha.nomagichttp.util.TriConsumer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * An EventEmitter using {@code HashMap} and {@code HashSet}(s) as backing data
 * structures.<p>
 * 
 * Not being thread-safe is an optimization, albeit small (the gain is
 * essentially only cutting down on some atomic reads and writes).<p>
 * 
 * The emitter can still be used by multiple threads, if orchestrated correctly.
 * For example, if all listeners subscribe serially before the first event takes
 * place, and the subscribe operations have been made fully visible to whichever
 * thread emits the events, then the emission may be done by a different
 * thread - even concurrently by lots of different threads (concurrent reads are
 * not problematic, writes are) - as long as the listeners never unsubscribe,
 * i.e. modify the backing stores.<p>
 * 
 * All {@code off} methods in this class throws {@link
 * UnsupportedOperationException}.<p>
 * 
 * Using a local event emitter by multiple threads is obviously an optimization
 * on top of an optimization and must be documented or otherwise understood by
 * the code context in which the emitter executes.<p>
 * 
 * All enums in the subclass are emitted events.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
abstract class AbstractLocalEventEmitter extends AbstractEventEmitter {
    AbstractLocalEventEmitter() {
        super(new HashMap<>(), HashSet::new);
    }
    
    @Override
    public <T> boolean off(Class<T> eventType, Consumer<? super T> listener) {
        throw new UnsupportedOperationException();
    }
    
    @Override
    public <T> boolean off(Class<T> eventType, BiConsumer<? super T, ?> listener) {
        throw new UnsupportedOperationException();
    }
    
    @Override
    public <T> boolean off(Class<T> eventType, TriConsumer<? super T, ?, ?> listener) {
        throw new UnsupportedOperationException();
    }
}