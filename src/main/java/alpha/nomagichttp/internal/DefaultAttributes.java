package alpha.nomagichttp.internal;

import alpha.nomagichttp.util.Attributes;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static java.util.Optional.ofNullable;

/**
 * Default implementation of {@link Attributes}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class DefaultAttributes implements Attributes {
    // does not allow null as key or value
    private final ConcurrentMap<String, Object> map = new ConcurrentHashMap<>();
    
    @Override
    public Object get(String name) {
        return map.get(name);
    }
    
    @Override
    public Object set(String name, Object value) {
        return map.put(name, value);
    }
    
    @Override
    public <V> V getAny(String name) {
        return this.<V>asMapAny().get(name);
    }
    
    @Override
    public Optional<Object> getOpt(String name) {
        return ofNullable(get(name));
    }
    
    @Override
    public <V> Optional<V> getOptAny(String name) {
        return ofNullable(getAny(name));
    }
    
    @Override
    public ConcurrentMap<String, Object> asMap() {
        return map;
    }
    
    @Override
    public <V> ConcurrentMap<String, V> asMapAny() {
        @SuppressWarnings("unchecked")
        ConcurrentMap<String, V> m = (ConcurrentMap<String, V>) map;
        return m;
    }
    
    @Override
    public String toString() {
        return map.toString();
    }
}