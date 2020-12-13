package alpha.nomagichttp.internal;

import org.junit.jupiter.api.Test;

import static java.util.List.of;
import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Small tests of {@link RequestTarget}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class RequestTargetTest
{
    @Test
    void happyPath() {
        RequestTarget rt = RequestTarget.parse("/seg/ment?q=1&q=2#ignored");
        
        assertThat(rt.segmentsNotPercentDecoded())
                .containsExactly("seg", "ment");
        
        assertThat(rt.segmentsPercentDecoded())
                .containsExactly("seg", "ment");
        
        assertThat(rt.queryMapNotPercentDecoded())
                .containsExactly(entry("q", of("1", "2")));
        
        assertThat(rt.queryMapPercentDecoded())
                .containsExactly(entry("q", of("1", "2")));
    }
    
    // TODO: Add lots of test cases
}