package alpha.nomagichttp.internal;

import java.util.List;
import java.util.stream.StreamSupport;

import static java.util.stream.Collectors.toList;

/**
 * Util class for segments.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class Segments
{
    static final char COLON_CH = ':',    // key for single- path params
                      ASTERISK_CH = '*'; // key for catch-all path params
    
    static final String COLON_STR = ":",
                        ASTERISK_STR = "*";
    
    private Segments() {
        // Empty
    }
    
    /**
     * Returns the given segments without parameter names. E.g. {@code
     * /some/:arg/*rest} becomes {@code /some/:/*}.
     * 
     * @param segments to strip of names
     * 
     * @return the given segments without parameter names
     */
    static List<String> noParamNames(Iterable<String> segments) {
        return StreamSupport.stream(segments.spliterator(), false)
                .map(s -> s.startsWith(COLON_STR)    ? COLON_STR :
                          s.startsWith(ASTERISK_STR) ? ASTERISK_STR :
                          s)
                .collect(toList());
    }
}