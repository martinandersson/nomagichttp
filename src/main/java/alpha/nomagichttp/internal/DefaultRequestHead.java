package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.DefaultCommonHeaders;
import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.RequestHead;
import alpha.nomagichttp.util.Headers;

import java.net.http.HttpHeaders;
import java.util.List;

import static java.lang.String.join;

final class DefaultRequestHead implements RequestHead
{
    private final String method, requestTarget, httpVersion;
    private final Request.Headers headers;
    
    DefaultRequestHead(
            String method,
            String requestTarget,
            String httpVersion,
            HttpHeaders headers)
    {
        this.method        = method;
        this.requestTarget = requestTarget;
        this.httpVersion   = httpVersion;
        this.headers       = new RequestHeaders(headers);
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
    public Request.Headers headers() {
        return headers;
    }
    
    @Override
    public String toString() {
        return "\"" + join(" ", method, requestTarget, httpVersion) + "\" " +
               headers.delegate().map().toString();
    }
    
    private static class RequestHeaders extends DefaultCommonHeaders implements Request.Headers {
        RequestHeaders(HttpHeaders headers) {
            super(headers);
        }
        
        private List<MediaType> ac;
        
        @Override
        public final List<MediaType> accept() {
            var ac = this.ac;
            return ac != null ? ac : (this.ac = Headers.accept(delegate()));
        }
    }
}