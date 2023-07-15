package alpha.nomagichttp.util;

import org.junit.jupiter.api.Test;

import static alpha.nomagichttp.util.Blah.addExactOrCap;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Small tests for {@link Blah}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class BlahTest
{
    @Test
    void addExactOrMaxValue_int() {
        int min = Integer.MIN_VALUE,
            max = Integer.MAX_VALUE;
        assertEquals(3, addExactOrCap(1, 2));
        assertEquals(min, addExactOrCap(min, -1));
        assertEquals(max, addExactOrCap(max, +1));
    }
    
    @Test
    void addExactOrMaxValue_long() {
        long min = Long.MIN_VALUE,
             max = Long.MAX_VALUE;
        assertEquals(3, addExactOrCap(1, 2));
        assertEquals(min, addExactOrCap(min, -1));
        assertEquals(max, addExactOrCap(max, +1));
    }
}