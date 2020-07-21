package alpha.nomagichttp.message;

import java.util.ArrayList;
import java.util.List;
import java.util.PrimitiveIterator;

// TODO: Document
final class Strings
{
    private Strings() {
        // Empty
    }
    
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
