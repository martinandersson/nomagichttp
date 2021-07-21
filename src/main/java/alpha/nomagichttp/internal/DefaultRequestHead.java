package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.RequestHead;

import java.net.http.HttpHeaders;

import static java.lang.String.join;

final class DefaultRequestHead implements RequestHead
{
    private final String method, requestTarget, httpVersion;
    private final HttpHeaders headers;
    private final long nanoOnStart;
    
    DefaultRequestHead(
            String method,
            String requestTarget,
            String httpVersion,
            HttpHeaders headers,
            long nanoOnStart)
    {
        this.method        = method;
        this.requestTarget = requestTarget;
        this.httpVersion   = httpVersion;
        this.headers       = headers;
        this.nanoOnStart   = nanoOnStart;
    }
    
    @Override
    public String method() {
        return method;
    }
    
    @Override
    public String requestTarget() {
        return requestTarget;
    }
    
    @Override
    public String httpVersion() {
        return httpVersion;
    }
    
    @Override
    public HttpHeaders headers() {
        return headers;
    }
    
    @Override
    public long nanoTimeOnStart() {
        return nanoOnStart;
    }
    
    @Override
    public String toString() {
        return "\"" + join(" ", method, requestTarget, httpVersion) + "\" " +
               headers.map().toString();
    }
}