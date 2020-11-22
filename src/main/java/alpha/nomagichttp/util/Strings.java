package alpha.nomagichttp.util;

import java.util.ArrayList;
import java.util.List;
import java.util.PrimitiveIterator;

final class Strings
{
    private Strings() {
        // Empty
    }
    
    /**
     * Works just as {@code String.split}, except this method accepts an exclude
     * boundary within which, the delimiter will have no effect and the substring
     * will be taken at face value.<p>
     * 
     * For example, good to use when dealing with strings that have quoted parts
     * in them and the split shouldn't occur within those quoted parts.
     * 
     * @param string input string to split
     * @param delimiter char to split by...
     * @param excludeBoundary ...except if found within this boundary
     * 
     * @return the substrings
     */
    static String[] split(String string, char delimiter, char excludeBoundary) {
        if (delimiter == excludeBoundary) {
            throw new IllegalArgumentException(
                    "Delimiter char can not be same as exclude char.");
        }
        
        PrimitiveIterator.OfInt chars = string.chars().iterator();
        
        StringBuilder curr = null;
        boolean ignoring = false;
        List<String> bucket = null;
        
        while (chars.hasNext()) {
            final char c = (char) chars.nextInt();
            
            if (c == delimiter) {
                if (ignoring) {
                    curr.append(c);
                    continue; }
                
                if (curr == null) {
                    continue; }
                
                if (bucket == null) {
                    bucket = new ArrayList<>(); }
                
                bucket.add(curr.toString());
                curr = null;
            } else {
                if (curr == null) {
                    curr = new StringBuilder(); }
                
                curr.append(c);
                
                if (c == excludeBoundary) {
                    ignoring = !ignoring; }
            }
        }
        
        if (curr != null && !ignoring) {
            if (bucket == null) {
                bucket = new ArrayList<>(); }
            
            bucket.add(curr.toString());
        }
        
        return bucket == null ?
                new String[]{} :
                bucket.toArray(String[]::new);
    }
}
