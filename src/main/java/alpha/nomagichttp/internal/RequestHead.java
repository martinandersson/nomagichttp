package alpha.nomagichttp.internal;

import java.net.http.HttpHeaders;

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
}