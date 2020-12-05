package alpha.nomagichttp.route;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toCollection;

final class DefaultSegment implements Segment
{
    private final boolean isFirst;
    private final String value;
    private final List<String> params;
    
    DefaultSegment(boolean isFirst, String value, List<String> params) {
        this.isFirst = isFirst;
        this.value = value;
        this.params = unmodifiableList(params);
    }
    
    @Override
    public boolean isFirst() {
        return isFirst;
    }
    
    @Override
    public String value() {
        return value;
    }
    
    @Override
    public List<String> params() {
        return params;
    }
    
    @Override
    public String toString() {
        if (params.isEmpty()) {
            return value;
        }
        
        // We don't want to prefix root with a forward slash.
        String prefix = value.equals("/") ? "{" : "/{";
        return value + params.stream().collect(joining("}/{", prefix, "}"));
    }
    
    static final class Builder implements Segment.Builder
    {
        private final StringBuilder value;
        private final boolean isFirst;
        private final Stream.Builder<String> params;
        private boolean hasParams;
        
        Builder(String str, boolean isFirst) {
            this.isFirst   = isFirst;
            this.value     = new StringBuilder();
            this.params    = Stream.builder();
            this.hasParams = false;
            
            append(str, isFirst);
        }
        
        @Override
        public void append(String str) {
            if (hasParams()) {
                throw new IllegalStateException();
            }
            
            append(str, false);
        }
        
        @Override
        public void addParam(String name) {
            requireNonNull(name);
            hasParams = true;
            // Duplicates checked by Route.Builder
            params.add(name);
        }
        
        @Override
        public boolean hasParams() {
            return hasParams;
        }
        
        @Override
        public Segment build() {
            List<String> randomAccess = params.build().collect(toCollection(ArrayList::new));
            return new DefaultSegment(
                    isFirst,
                    value.toString(),
                    randomAccess);
        }
        
        private void append(String str, boolean acceptSingleSlash) {
            requireFirstCharIsSlash(str);
            
            if (!acceptSingleSlash) {
                requireNotSingleSlash(str);
            }
            
            requireNotTwoSlashes(str);
            String clean = tryRemoveSlashEnding(str, false);
            
            this.value.append(clean);
        }
        
        private static void requireFirstCharIsSlash(String str) {
            if (!str.startsWith("/")) {
                throw new IllegalArgumentException(
                        "A segment (or a piece thereof) must start with a \"/\" character. Got: \"" + str + "\"");
            }
        }
        
        private static void requireNotSingleSlash(String str) {
            if (str.equals("/")) {
                throw new IllegalArgumentException(
                        "Segment (or a piece thereof) must contain more than just a forward slash character.");
            }
        }
        
        private static void requireNotTwoSlashes(String str) {
            if (str.equals("//")) {
                throw new IllegalArgumentException(
                        "Segment (or a piece thereof) must contain more than just forward slash(es).");
            }
        }
        
        private static String tryRemoveSlashEnding(final String str, boolean allOfThem) {
            if (str.length() < 2 || !str.endsWith("/")) {
                return str;
            }
            
            final String cut = str.substring(0, str.length() - 1);
            
            // If the cut still has a trailing '/', maybe remove them too;
            //   1) For developers we don't - no magic!
            //   2) For input from HTTP we must be tolerant, so we do.
            if (cut.length() > 1 && cut.endsWith("/")) {
                if (allOfThem) {
                    // TODO: Unroll into a while-loop or something instead of recursion.
                    return tryRemoveSlashEnding(cut, true);
                }
                else {
                    throw new IllegalArgumentException(
                            "Multiple trailing forward slashes in segment (or a piece thereof): \"" + str + "\"");
                }
            }
            
            return cut;
        }
    }
}
