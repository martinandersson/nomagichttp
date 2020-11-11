package alpha.nomagichttp.message;

import java.lang.ref.SoftReference;
import java.util.WeakHashMap;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Super simple but highly efficient thread-local object store and factory of
 * singleton objects that are evicted at the discretion of the garbage
 * collector.<p>
 * 
 * Backed by weak keys and soft values.<p>
 * 
 * Which means that keys will only stay around for as long as the key itself is
 * strongly reachable outside of this class. But if the key is lost, value is
 * lost.<p>
 * 
 * The values will be kept around at the discretion of the JVM implementation.
 * As per the JavaDoc's of {@link SoftReference}, the JVM implementation is
 * "encouraged to bias against clearing recently-created or recently-used soft
 * references".<p>
 * 
 * <h2>An example</h2>
 * <pre>{@code
 *   NumberFormat nf = ThreadLocalCache.get(NumberFormat.class, () -> {
 *           // Thread-unique instance created only when not already cached:
 *           NumberFormat cached = NumberFormat.getInstance();
 *           cached.setMaximumFractionDigits(3);
 *           return cached;
 *   });
 * }</pre>
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ThreadLocalCache
{
    private ThreadLocalCache() {
        // Empty
    }
    
    private static final ThreadLocal<WeakHashMap<Class<?>, SoftReference<?>>>
            CACHE = ThreadLocal.withInitial(WeakHashMap::new);
    
    /**
     * Retrieve a new or cached instance of the specified type.<p>
     * 
     * The factory may return null, in which case null will be returned until
     * the factory yields a non-null result.
     * 
     * @param type     key
     * @param factory  producer
     * @param <T>      static type
     * 
     * @return  a cached, new instance or null
     * 
     * @throws NullPointerException  if {@code type} is {@code null}
     */
    public static <T> T get(Class<T> type, Supplier<? extends T> factory) {
        final WeakHashMap<Class<?>, SoftReference<?>> cache = CACHE.get();
        
        requireNonNull(type);
        
        SoftReference<?> ref = cache.computeIfAbsent(
                type, keyIgnored -> new SoftReference<>(factory.get()));
        
        Object obj = ref.get();
        
        if (obj == null) {
            obj = factory.get();
            if (obj == null) {
                cache.remove(type);
            } else {
                cache.put(type, new SoftReference<>(obj));
            }
        }
        
        @SuppressWarnings("unchecked")
        T typed = (T) obj;
        
        return typed;
    }
}