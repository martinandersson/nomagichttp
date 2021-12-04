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
            return ac != null ? ac : (this.ac = mkAccept());
        }
        
        private List<MediaType> mkAccept() {
            try {
                return allTokensKeepQuotes(ACCEPT)
                        .map(MediaType::parse)
                        .toList();
            } catch (MediaTypeParseException e) {
                throw new BadHeaderException("Failed to parse " + ACCEPT + " header.", e);
            }
        }
    }
}