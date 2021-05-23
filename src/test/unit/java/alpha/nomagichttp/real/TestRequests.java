package alpha.nomagichttp.real;

import static alpha.nomagichttp.testutil.TestClient.CRLF;

/**
 * Factory of HTTP requests (as strings).
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class TestRequests
{
    private TestRequests() {
        // Empty
    }
    
    /**
     * Make a "GET / HTTP/1.1" request.
     * 
     * @return the request
     */
    public static String get() {
        return "GET / HTTP/1.1"                    + CRLF +
               "Accept: text/plain; charset=utf-8" + CRLF +
               "Content-Length: " + 0              + CRLF + CRLF;
    }
    
    /**
     * Make a "POST / HTTP/1.1" request with a body.
     * 
     * @param body of request
     * @return the request
     */
    public static String post(String body) {
        return "POST / HTTP/1.1"                         + CRLF +
               "Accept: text/plain; charset=utf-8"       + CRLF +
               "Content-Type: text/plain; charset=utf-8" + CRLF +
               "Content-Length: " + body.length()        + CRLF + CRLF +
               
               body;
    }
}