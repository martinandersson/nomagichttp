package alpha.nomagichttp.route;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toCollection;

@Deprecated // To be removed
final class DefaultSegment implements Segment
{
    private final boolean isFirst;
    private final String value;
    private final List<String> params;
    
    DefaultSegment(boolean isFirst, String value, List<String> params) {
        this.isFirst = isFirst;
        this.value   = value;
        this.params  = unmodifiableList(params);
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
            mustStartWithSlash(str);
            if (!acceptSingleSlash) {
                mustContainMoreThanSlash(str);
                mustNotEndWithSlash(str);
            }
            mustNotContainEmptySegments(str);
            this.value.append(str);
        }
        
        private static void mustStartWithSlash(String str) {
            if (!str.startsWith("/")) {
                throw new IllegalArgumentException(
                        "Segment must start with a forward slash character.");
            }
        }
        
        private static void mustContainMoreThanSlash(String str) {
            if (str.equals("/")) {
                throw new IllegalArgumentException(
                        "Segment must contain more than just a forward slash character.");
            }
        }
        
        private static void mustNotEndWithSlash(String str) {
            if (str.endsWith("/")) {
                throw new IllegalArgumentException(
                        "Segment must not end with a forward slash character.");
            }
        }
        
        private static void mustNotContainEmptySegments(String str) {
            if (str.contains("//")) {
                throw new IllegalArgumentException("Segment is empty.");
            }
        }
    }
}
