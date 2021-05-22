package alpha.nomagichttp.util;

import java.util.Optional;
import java.util.concurrent.ConcurrentMap;

/**
 * Is an API for accessing objects associated with a holder.<p>
 * 
 * Useful when passing data across boundaries where the holder is the data
 * carrier, such as passing data from a request handler to an error handler or
 * the other way around.
 * 
 * <pre>{@code
 *   // In a request handler
 *   request.attributes().set("my.stuff", new MyClass());
 *   // Somewhere else
 *   MyClass obj = request.attributes().getAny("my.stuff");
 * }</pre>
 * 
 * The implementation is thread-safe.<p>
 * 
 * For as long as the holder object is reachable, the attributes are reachable.
 * The other way around is not true as the attributes does not keep a
 * back-reference to the holder object.<p>
 * 
 * The NoMagicHTTP library reserves the right to use the namespace
 * "alpha.nomagichttp.*" exclusively. Applications are encouraged to avoid
 * using this prefix in their names.
 *
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface Attributes {
    /**
     * Returns the value of the named attribute as an object.
     * 
     * @param name of attribute
     * 
     * @return the value of the named attribute as an object (may be {@code null})
     * 
     * @throws NullPointerException if {@code name} is {@code null}
     */
    Object get(String name);
    
    /**
     * Set the value of the named attribute.<p>
     * 
     * @param name  of attribute (any non-null string)
     * @param value of attribute (may be {@code null})
     * 
     * @return the old value (may be {@code null})
     * 
     * @throws NullPointerException if {@code name} is {@code null}
     */
    Object set(String name, Object value);
    
    /**
     * Returns the value of the named attribute cast to V.
     * 
     * This method is equivalent to:
     * <pre>{@code
     *   V v = (V) get(name);
     * }</pre>
     * 
     * Except the cast is implicit and the type is inferred by the Java
     * compiler. The call site will still blow up with a {@code
     * ClassCastException} if a non-null object can not be cast to the
     * inferred type.
     * 
     * <pre>{@code
     *   // Given
     *   request.attributes().set("name", "my string");
     *   
     *   // Okay
     *   String str = request.attributes().getAny("name");
     *   
     *   // ClassCastException
     *   DateTimeFormatter oops = request.attributes().getAny("name");
     * }</pre>
     * 
     * @param <V>  value type (explicitly provided on call site or inferred 
     *             by Java compiler)
     * @param name of attribute
     * 
     * @return the value of the named attribute as an object (may be {@code null})
     * 
     * @throws NullPointerException if {@code name} is {@code null}
     */
    <V> V getAny(String name);
    
    /**
     * Returns the value of the named attribute described as an Optional of
     * an object.<p>
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
     * ClassCastException is delayed (known as "heap pollution").
     * 
     * <pre>{@code
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
     * }</pre>
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
     * Returns a modifiable map view of the attributes. Changes to the map
     * are reflected in the attributes, and vice-versa.<p>
     * 
     * Unlike {@link #getOptAny(String)}, using this method does not lead to
     * heap pollution if the returned map is immediately used to work with
     * the values. For example:
     * 
     * <pre>{@code
     *   int v = request.attributes()
     *                  .<Integer>asMapAny()
     *                  .merge("my.counter", 1, Integer::sum);
     * }</pre>
     * 
     * @param <V> value type (explicitly provided on call site or inferred 
     *            by Java compiler)
     * 
     * @return a modifiable map view of the attributes
     */
    <V> ConcurrentMap<String, V> asMapAny();
}
