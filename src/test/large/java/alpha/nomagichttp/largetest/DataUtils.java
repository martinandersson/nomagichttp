package alpha.nomagichttp.largetest;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Static util methods for producing data.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class DataUtils
{
    private DataUtils() {
        // Private
    }
    
    
    static byte[] bytes(int length) {
        byte[] b = new byte[length];
        
        byte v = Byte.MIN_VALUE;
        for (int i = 0; i < length; ++i) {
            b[i] = v++;
        }
        
        return b;
    }
    
    /**
     * Returns a char sequence of all printable ASCII letters, repeated over and
     * over again as necessary to reach specified {@code length} of string.
     * 
     * @param length of returned string
     * 
     * @return a char sequence of all printable ASCII letters, repeated over and
     * over again as necessary to reach specified {@code length} of string
     */
    static String text(int length) {
        byte[] source = lettersRange('!', '~');
        byte[] target = new byte[length];
        
        for (int i = 0, j = 0; i < length; ++i) {
            target[i] = source[j];
            // Re-cycle source
            j = j < source.length - 1 ? j + 1 : 0;
        }
        
        return new String(target, UTF_8);
    }
    
    private static byte[] lettersRange(char beginIncl, char endIncl) {
        final byte[] bytes = new byte[endIncl - beginIncl + 1];
        
        for (int i = beginIncl; i <= endIncl; ++i) {
            bytes[i - beginIncl] = (byte) i;
        }
        
        return bytes;
    }
}
