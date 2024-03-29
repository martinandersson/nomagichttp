package alpha.nomagichttp.message;

import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * An API for storing and accessing objects.<p>
 * 
 * {@snippet :
 *   request.attributes().set("my.stuff", new MyClass());
 *   // Somewhere else
 *   MyClass obj = request.attributes().getAny("my.stuff");
 * }
 * 
 * The implementation is thread-safe and is never replaced throughout the life
 * of whichever object carries the attributes.<p>
 * 
 * The NoMagicHTTP library reserves the right to use the namespace
 * "alpha.nomagichttp.*" exclusively. Applications are encouraged to avoid
 * using this prefix in their names.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// TODO: Update JavaDoc. ScopedValue from BeforeAction is the preferred method
public interface Attributes {
    /**
     * Returns the value of the named attribute as an object.
     * 
     * @param name of attribute
     * 
     * @return the value of the named attribute as an object (can be {@code null})
     * 
     * @throws NullPointerException if {@code name} is {@code null}
     */
    Object get(String name);
    
    /**
     * Set the value of the named attribute.
     * 
     * @param name  of attribute (any non-null string)
     * @param value of attribute (can be {@code null})
     * 
     * @return the old value (can be {@code null})
     * 
     * @throws NullPointerException if {@code name} is {@code null}
     */
    Object set(String name, Object value);
    
    /**
     * Gets a named value if present, otherwise creates and stores it.
     * 
     * @implSpec
     * The default implementation is equivalent to:<p>
     * 
     * {@snippet :
     *   return this.<V>asMapAny()
     *              .computeIfAbsent(name,
     *                  keyIgnored -> Objects.requireNonNull(s.get()));
     * }
     * 
     * @param name of value (any non-null string)
     * @param factory of a new value if not already present
     * @param <V> value type
     *            (explicitly provided on call site or inferred by Java compiler)
     * 
     * @return the named value (never {@code null})
     * 
     * @throws NullPointerException
     *             if any argument is {@code null}, or
     *             if the value created by {@code s} is {@code null}
     */
    default <V> V getOrCreate(String name, Supplier<? extends V> factory) {
        return this.<V>asMapAny()
                   .computeIfAbsent(name,
                       x -> requireNonNull(factory.get()));
    }
    
    /**
     * Returns the value of the named attribute cast to V.<p>
     * 
     * This method is equivalent to:<p>
     * 
     * {@snippet :
     *   V v = (V) get(name);
     * }
     * 
     * Except the cast is implicit and the type is inferred by the Java
     * compiler. The call site will still blow up with a {@code
     * ClassCastException} if a non-null object can not be cast to the
     * inferred type.<p>
     * 
     * {@snippet :
     *   // Given
     *   request.attributes().set("name", "my string");
     *   
     *   // Okay
     *   String str = request.attributes().getAny("name");
     *   
     *   // ClassCastException
     *   DateTimeFormatter oops = request.attributes().getAny("name");
     * }
     * 
     * @param <V>  value type (explicitly provided on call site or inferred 
     *             by Java compiler)
     * @param name of attribute
     * 
     * @return the value of the named attribute as an object (can be {@code null})
     * 
     * @throws NullPointerException if {@code name} is {@code null}
     */
    <V> V getAny(String name);
    
    /**
     * Returns the value of the named attribute described as an Optional of
     * an object.
     * 
     * @param name of attribute
     * 
     * @return the value of the named attribute described as an Optional of
     *         an object (never {@code null} but possibly empty)
     * 
     * @throws NullPointerException if {@code name} is {@code null}
     */
    Optional<Object> getOpt(String name);
    
    /**
     * Returns the value of the named attribute described as an Optional of
     * V.<p>
     * 
     * Unlike {@link #getAny(String)} where the {@code ClassCastException}
     * is immediate for non-null and assignment-incompatible types, this
     * method should generally be considered unsafe as the
     * ClassCastException is delayed (known as "heap pollution").<p>
     * 
     * {@snippet :
     *   // Given
     *   request.attributes().set("name", "my string");
     *   
     *   // Okay
     *   Optional<String> str = request.attributes().getOptAny("name");
     *   
     *   // No ClassCastException!
     *   Optional<DateTimeFormatter> poison = request.attributes().getOptAny("name");
     *   
     *   // Let's give the problem to someone else in the future
     *   anotherDestination(poison);
     * }
     * 
     * @param <V>  value type (explicitly provided on call site or inferred 
     *             by Java compiler)
     * @param name of attribute
     * 
     * @return the value of the named attribute described as an Optional of
     *         V (never {@code null} but possibly empty)
     * 
     * @throws NullPointerException if {@code name} is {@code null}
     */
    <V> Optional<V> getOptAny(String name);
    
    /**
     * Returns a modifiable map view of the attributes. Changes to the map
     * are reflected in the attributes, and vice-versa.
     * 
     * @return a modifiable map view of the attributes
     */
    ConcurrentMap<String, Object> asMap();
    
    /**
     * {@return a modifiable map view of the attributes}<p>
     * 
     * Changes to the returned map are reflected in this object, and
     * vice-versa.<p>
     * 
     * Unlike {@link #getOptAny(String)}, using this method can not lead to
     * heap pollution if the returned map is immediately used to work with
     * the values.<p>
     * 
     * {@snippet :
     *   int v = request.attributes()
     *                  .<Integer>asMapAny()
     *                  .merge("my.counter", 1, Integer::sum);
     * }
     * 
     * @param <V> value type (explicitly provided on call site or inferred 
     *            by Java compiler)
     */
    <V> ConcurrentMap<String, V> asMapAny();
}