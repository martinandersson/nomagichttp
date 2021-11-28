package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.BadHeaderException;
import alpha.nomagichttp.message.DefaultContentHeaders;
import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.MediaTypeParseException;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.RequestHead;

import java.net.http.HttpHeaders;
import java.util.List;

import static alpha.nomagichttp.HttpConstants.HeaderKey.ACCEPT;
import static alpha.nomagichttp.util.Streams.randomAndUnmodifiable;
import static alpha.nomagichttp.util.Strings.split;
import static java.util.Arrays.stream;

record DefaultRequestHead(
        String method, String target, String httpVersion, Request.Headers headers)
        implements RequestHead
{
    DefaultRequestHead(
            String method,
            String target,
            String httpVersion,
            HttpHeaders headers)
    {
        this(method, target, httpVersion, new RequestHeaders(headers));
    }
    
    private static class RequestHeaders extends DefaultContentHeaders implements Request.Headers {
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