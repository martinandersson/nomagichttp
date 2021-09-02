package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.RequestHead;
import alpha.nomagichttp.util.Headers;

import java.net.http.HttpHeaders;
import java.util.Optional;

import static java.lang.String.join;

final class DefaultRequestHead implements RequestHead
{
    private final String method, requestTarget, httpVersion;
    private final HttpHeaders headers;
    
    DefaultRequestHead(
            String method,
            String requestTarget,
            String httpVersion,
            HttpHeaders headers)
    {
        this.method        = method;
        this.requestTarget = requestTarget;
        this.httpVersion   = httpVersion;
        this.headers       = headers;
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
    
    private Optional<MediaType> cc;
    
    @Override
    public Optional<MediaType> headerValContentType() {
        var cc = this.cc;
        if (cc == null) {
            this.cc = cc = Headers.contentType(headers());
        }
        return cc;
    }
    
    @Override
    public String toString() {
        return "\"" + join(" ", method, requestTarget, httpVersion) + "\" " +
               headers.map().toString();
    }
}