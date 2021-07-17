package alpha.nomagichttp.route;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.util.Collections.unmodifiableCollection;

/**
 * Builds a {@code Iterable<String>} of segments.<p>
 * 
 * For example {@code ["download", ":user", "*filepath"]}. Root - the empty
 * string - is never a segment encountered in the iterable.<p>
 * 
 * Duplicated parameter names can be accepted if specified to constructor
 * (useful to the route registry's remove(pattern) operation).
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class SegmentsBuilder
{
    private static final int  INITIAL_CAPACITY = 3;
    private static final char SINGLE = ':',
                              CATCH_ALL = '*';
    
    private final List<String> segments;
    private final boolean checkDupe;
    private Set<String> params;
    private boolean catchAllSet;
    
    SegmentsBuilder() {
        this(false);
    }
    
    SegmentsBuilder(boolean duplicatedParamsAllowed) {
        segments    = new ArrayList<>(INITIAL_CAPACITY);
        checkDupe   = !duplicatedParamsAllowed;
        params      = checkDupe ? Set.of() : null;
        catchAllSet = false;
    }
    
    SegmentsBuilder paramSingle(String name) {
        addParam(SINGLE, name);
        return this;
    }
    
    SegmentsBuilder paramCatchAll(String name) {
        addParam(CATCH_ALL, name);
        catchAllSet = true;
        return this;
    }
    
    SegmentsBuilder append(String p) { // pattern
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
                case SINGLE:
                    paramSingle(t.substring(1));
                    break;
                case CATCH_ALL:
                    paramCatchAll(t.substring(1));
                    break;
                default:
                    requireCatchAllNotSet();
                    segments.add(t);
                    break;
            }
        }
        return this;
    }
    
    Iterable<String> asIterable() {
        return unmodifiableCollection(segments);
    }
    
    private void addParam(char prefix, String name) {
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