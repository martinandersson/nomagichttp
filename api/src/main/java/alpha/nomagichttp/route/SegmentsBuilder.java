package alpha.nomagichttp.route;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.util.Collections.unmodifiableCollection;
import static java.util.Objects.requireNonNull;

/**
 * Builds an {@code Iterable<String>} of segments.<p>
 * 
 * For example {@code ["download", ":user", "*filepath"]}. Root - the empty
 * string - is never a segment encountered in the built iterable.<p>
 * 
 * Duplicated parameter names can be accepted if specified to constructor
 * (useful for the route registry's remove(pattern) operation).<p>
 * 
 * This class has the same characteristics as {@link Route.Builder};
 * specifically, it is not thread-safe and should be disposed after use. 
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class SegmentsBuilder
{
    private static final int  INITIAL_CAPACITY = 3;
    private static final char SINGLE = ':',
                              CATCH_ALL = '*';
    
    private final List<String> segments;
    private final boolean checkDupe;
    // permits null
    private Set<String> params;
    private boolean catchAllSet;
    
    /**
     * Constructs this object.<p>
     * 
     * Duplicated parameter names will be rejected.
     */
    public SegmentsBuilder() {
        this(false);
    }
    
    /**
     * Constructs this object.<p>
     * 
     * Duplicated parameter names are not allowed when {@linkplain
     * Route#builder(String) building a route}. But when {@linkplain
     * RouteRegistry#remove(String) removing a route}, the name doesn't matter,
     * even if it is duplicated.
     * 
     * @param duplicatedParamsAllowed allow or reject duplicated parameter names
     */
    public SegmentsBuilder(boolean duplicatedParamsAllowed) {
        segments    = new ArrayList<>(INITIAL_CAPACITY);
        checkDupe   = !duplicatedParamsAllowed;
        params      = checkDupe ? Set.of() : null;
        catchAllSet = false;
    }
    
    /**
     * Programmatically add a single-segment parameter name. The name will be
     * prefixed with {@value SINGLE}.
     * 
     * @param name of parameter
     * 
     * @return this (for fluency/chaining)
     * 
     * @throws NullPointerException
     *             if {@code name} is {@code null}
     * @throws IllegalStateException
     *             if a catch-all parameter has been set, or
     *             if the name has already been used and duplicates are not allowed
     */
    public SegmentsBuilder paramSingle(String name) {
        addParam(SINGLE, name);
        return this;
    }
    
    /**
     * Programmatically add a catch-all parameter name. The name will be
     * prefixed with {@value CATCH_ALL}.
     * 
     * @param name of parameter
     * 
     * @return this (for fluency/chaining)
     * 
     * @throws NullPointerException
     *             if {@code name} is {@code null}
     * @throws IllegalStateException
     *             if a catch-all parameter has been set, or
     *             if the name has already been used and duplicates are not allowed
     */
    public SegmentsBuilder paramCatchAll(String name) {
        addParam(CATCH_ALL, name);
        catchAllSet = true;
        return this;
    }
    
    /**
     * Append a pattern.
     * 
     * @param p pattern
     * 
     * @return this (for fluency/chaining)
     * 
     * @throws NullPointerException
     *             if {@code p} is {@code null}
     * @throws IllegalArgumentException
     *             if a static segment value is empty
     * @throws IllegalStateException
     *             if a catch-all parameter has been set, or
     *             if a name is repeated and duplicates are not allowed
     */
    public SegmentsBuilder append(String p) { // pattern
        if (p.endsWith("//")) {
            throw new IllegalArgumentException("Static segment value is empty.");
        }
        
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        
        String[] tokens = p.split("/");
        
        if (tokens.length == 0) {
            throw new IllegalArgumentException("Static segment value is empty.");
        }
        
        for (String t : tokens) {
            if (t.isEmpty()) {
                throw new IllegalArgumentException("Static segment value is empty.");
            }
            switch (t.charAt(0)) {
                case SINGLE -> paramSingle(t.substring(1));
                case CATCH_ALL -> paramCatchAll(t.substring(1));
                default -> {
                    requireCatchAllNotSet();
                    segments.add(t);
                }
            }
        }
        return this;
    }
    
    /**
     * Returns all segments.<p>
     * 
     * This is the "build" method.
     * 
     * @return all segments (a non-null and unmodifiable copy)
     * @see SegmentsBuilder
     */
    public Iterable<String> asIterable() {
        return unmodifiableCollection(new ArrayList<>(segments));
    }
    
    /**
     * Equivalent to {@link #asIterable()}, except the underlying iterable of
     * segments is not a copy. I.e., this version is safe to use only locally if
     * it is absolutely known that the builder instance will be discarded.
     * 
     * @return all segments (non-null)
     * @see SegmentsBuilder
     */
    public Iterable<String> asIterableNoCopy() {
        return unmodifiableCollection(segments);
    }
    
    private void addParam(char prefix, String name) {
        requireNonNull(name);
        requireCatchAllNotSet();
        if (checkDupe) {
            if (params.isEmpty()) {
                params = new HashSet<>();
            }
            
            if (!params.add(name)) {
                throw new IllegalStateException(
                    "Duplicated parameter name: \"" + name + "\"");
            }
        }
        segments.add(prefix + name);
    }
    
    private void requireCatchAllNotSet() {
        if (catchAllSet) {
            throw new IllegalStateException(
                "Catch-all path parameter must be the last segment.");
        }
    }
}