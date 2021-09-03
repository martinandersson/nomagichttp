package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.BadHeaderException;
import alpha.nomagichttp.message.DefaultCommonHeaders;
import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.MediaTypeParseException;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.RequestHead;

import java.net.http.HttpHeaders;
import java.util.List;

import static alpha.nomagichttp.HttpConstants.HeaderKey.ACCEPT;
import static alpha.nomagichttp.util.Streams.randomAndUnmodifiable;
import static alpha.nomagichttp.util.Strings.split;
import static java.lang.String.join;
import static java.util.Arrays.stream;

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
    public String target() {
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
            return ac != null ? ac : (this.ac = mkAccept(delegate()));
        }
        
        private static List<MediaType> mkAccept(HttpHeaders headers) {
            var l = headers.allValues(ACCEPT);
            if (l.isEmpty()) {
                return List.of();
            }
            try {
                return randomAndUnmodifiable(l.size(), l.stream()
                        .flatMap(v -> stream(split(v, ',', '"')))
                        .map(MediaType::parse));
            } catch (MediaTypeParseException e) {
                throw new BadHeaderException("Failed to parse " + ACCEPT + " header.", e);
            }
        }
    }
}