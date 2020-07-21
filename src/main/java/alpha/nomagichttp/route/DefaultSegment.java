package alpha.nomagichttp.route;

import java.util.List;

import static java.util.Collections.unmodifiableList;
import static java.util.stream.Collectors.joining;

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
}
