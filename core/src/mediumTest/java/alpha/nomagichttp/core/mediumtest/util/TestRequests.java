package alpha.nomagichttp.mediumtest.util;

import static alpha.nomagichttp.testutil.TestConstants.CRLF;

/**
 * Factory of HTTP requests (as strings).
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class TestRequests
{
    private TestRequests() {
        // Empty
    }
    
    /**
     * Make a "GET / HTTP/1.1" request.
     * 
     * @param additionalHeaders as lines, e.g. "Connection: close"
     * @return the request
     */
    public static String get(String... additionalHeaders) {
        return "GET / HTTP/1.1"                    + CRLF +
               "Accept: text/plain; charset=utf-8" + CRLF +
               join(additionalHeaders)             +
               "Content-Length: " + 0              + CRLF + CRLF;
    }
    
    /**
     * Make a "POST / HTTP/1.1" request with a body.
     * 
     * @param body of request
     * @param additionalHeaders as lines, e.g. "Connection: close"
     * @return the request
     */
    public static String post(String body, String... additionalHeaders) {
        return "POST / HTTP/1.1"                         + CRLF +
               "Accept: text/plain; charset=utf-8"       + CRLF +
               "Content-Type: text/plain; charset=utf-8" + CRLF +
               join(additionalHeaders)                   +
               "Content-Length: " + body.length()        + CRLF + CRLF +
               
               body;
    }
    
    private static String join(String... headers) {
        return headers.length == 0 ? "" : String.join(CRLF, headers) + CRLF;
    }
}