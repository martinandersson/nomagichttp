package alpha.nomagichttp.internal;

import java.net.http.HttpHeaders;

import static java.lang.String.join;

final class RequestHead
{
    private final String method, requestTarget, httpVersion;
    private final HttpHeaders headers;
    
    RequestHead(String method, String requestTarget, String httpVersion, HttpHeaders headers) {
        this.method = method;
        this.requestTarget = requestTarget;
        this.httpVersion = httpVersion;
        this.headers = headers;
    }
    
    String method() {
        return method;
    }
    
    String requestTarget() {
        return requestTarget;
    }
    
    String httpVersion() {
        return httpVersion;
    }
    
    HttpHeaders headers() {
        return headers;
    }
    
    @Override
    public String toString() {
        return "\"" + join(" ", method, requestTarget, httpVersion) + "\" " +
               headers.map().toString();
    }
}