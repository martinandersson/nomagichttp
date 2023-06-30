package alpha.nomagichttp.internal;

import alpha.nomagichttp.Chain;
import alpha.nomagichttp.message.Response;
import jdk.incubator.concurrent.ScopedValue;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Set;

import static java.util.Collections.newSetFromMap;
import static jdk.incubator.concurrent.ScopedValue.newInstance;
import static jdk.incubator.concurrent.ScopedValue.where;

/**
 * Provides {@code Chain.proceed} verification.<p>
 * 
 * All the application's calls to {@link Chain#proceed() proceed} will be routed
 * through to the abstract method {@code callIntermittentHandler} until there
 * are no more intermittent handlers which is when the abstract method
 * {@code callFinalHandler} executes.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @param <E> type of intermittent handler
 */
abstract class AbstractChain<E>
{
    private static final ScopedValue<Object> ENTITY_RUNNING = newInstance();
    
    private final Iterator<? extends E> entities;
    private final Set<Object> yielded;
    
    AbstractChain(Collection<? extends E> entities) {
        this.entities = entities.iterator();
        this.yielded = newSetFromMap(new IdentityHashMap<>(entities.size()));
    }
    
    abstract Response callIntermittentHandler(
            E entity, Chain passMeThrough) throws Exception;
    
    abstract Response callFinalHandler() throws Exception;
    
    public final Response ignite() throws Exception {
        return proceed0(false);
    }
    
    private Response proceed0(boolean appMadeThisCall) throws Exception {
        if (appMadeThisCall) {
            validateCall();
        }
        if (!entities.hasNext()) {
            return callFinalHandler();
        } else {
            var e = entities.next();
            return where(ENTITY_RUNNING, e, () ->
                    // Recursive
                    callIntermittentHandler(e, () -> proceed0(true)));
        }
    }
    
    private void validateCall() {
        if (!ENTITY_RUNNING.isBound()) {
            throw new UnsupportedOperationException(Chain.class.getSimpleName() +
                    ".proceed() not called from within the processing chain");
        }
        var ent = ENTITY_RUNNING.get();
        // TODO: We might have to remove this requirement!
        //       Coz, what if a BeforeAction implements a retry mechanism??
        //       Erm, actually, we can leave it conditionally:
        //          Retries okay as long as the chain did not return normally!
        if (!yielded.add(ent)) {
            throw new UnsupportedOperationException(Chain.class.getSimpleName() +
                    ".proceed() was already called");
        }
    }
}