package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.BadHeaderException;
import alpha.nomagichttp.message.DefaultContentHeaders;
import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.MediaTypeParseException;
import alpha.nomagichttp.message.Request;

import java.util.LinkedHashMap;
import java.util.List;

import static alpha.nomagichttp.HttpConstants.HeaderName.ACCEPT;

final class RequestHeaders
        extends DefaultContentHeaders implements Request.Headers
{
    static final Request.Headers EMPTY
            = new RequestHeaders(new LinkedHashMap<>());
    
    RequestHeaders(LinkedHashMap<String, List<String>> headers) {
        super(headers, true);
    }
    
    private List<MediaType> ac;
    
    @Override
    public List<MediaType> accept() {
        var ac = this.ac;
        return ac != null ? ac : (this.ac = mkAccept());
    }
    
    private List<MediaType> mkAccept() {
        try {
            return allTokensKeepQuotes(ACCEPT)
                    .map(MediaType::parse)
                    .toList();
        } catch (MediaTypeParseException e) {
            throw new BadHeaderException(
                    "Failed to parse " + ACCEPT + " header.", e);
        }
    }
}