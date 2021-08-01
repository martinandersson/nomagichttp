package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpConstants.Version;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.RequestHead;
import alpha.nomagichttp.util.Attributes;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

final class DefaultRequest implements Request
{
    private final Version ver;
    private final RequestHead head;
    private final Body body;
    private final RequestTarget paramsQuery;
    private final ResourceMatch<?> paramsPath;
    private final Attributes attributes;
    
    /**
     * Constructs this object.
     * 
     * @param ver HTTP version
     * @param head request head
     * @param paramsQuery params from query
     * @param paramsPath params from path
     * 
     * @throws NullPointerException if any argument is {@code null}
     */
    DefaultRequest(
            Version ver,
            RequestHead head,
            Body body,
            ResourceMatch<?> paramsPath,
            RequestTarget paramsQuery)
    {
        this.ver         = requireNonNull(ver);
        this.head        = requireNonNull(head);
        this.body        = requireNonNull(body);
        this.paramsPath  = requireNonNull(paramsPath);
        this.paramsQuery = requireNonNull(paramsQuery);
        this.attributes  = new DefaultAttributes();
    }
    
    @Override
    public String method() {
        return head.method();
    }
    
    @Override
    public String target() {
        return head.requestTarget();
    }
    
    @Override
    public Version httpVersion() {
        return ver;
    }
    
    @Override
    public String toString() {
        return DefaultRequest.class.getSimpleName() + "{head=" + head + ", body=?}";
    }
    
    private Parameters params;
    
    @Override
    public Parameters parameters() {
        Parameters p = params;
        return p != null ? p : (params = new DefaultParameters(paramsPath, paramsQuery));
    }
    
    @Override
    public HttpHeaders headers() {
        return head.headers();
    }
    
    @Override
    public Body body() {
        return body;
    }
    
    @Override
    public Attributes attributes() {
        return attributes;
    }
    
    private static final class DefaultParameters implements Parameters
    {
        private final ResourceMatch<?> p;
        private final Map<String, List<String>> q, qRaw;
        
        DefaultParameters(ResourceMatch<?> paramsPath, RequestTarget paramsQuery) {
            p = requireNonNull(paramsPath);
            q = paramsQuery.queryMapPercentDecoded();
            qRaw = paramsQuery.queryMapNotPercentDecoded();
        }
        
        @Override
        public String path(String name) {
            return p.pathParam(name);
        }
        
        @Override
        public String pathRaw(String name) {
            return p.pathParamRaw(name);
        }
        
        @Override
        public Optional<String> queryFirst(String key) {
            return queryStream(key).findFirst();
        }
        
        @Override
        public Optional<String> queryFirstRaw(String key) {
            return queryStreamRaw(key).findFirst();
        }
        
        @Override
        public Stream<String> queryStream(String key) {
            return queryList(key).stream();
        }
    
        @Override
        public Stream<String> queryStreamRaw(String key) {
            return queryListRaw(key).stream();
        }
        
        @Override
        public List<String> queryList(String key) {
            return queryMap().getOrDefault(key, List.of());
        }
        
        @Override
        public List<String> queryListRaw(String key) {
            return queryMapRaw().getOrDefault(key, List.of());
        }
        
        @Override
        public Map<String, List<String>> queryMap() {
            return q;
        }
        
        @Override
        public Map<String, List<String>> queryMapRaw() {
            return qRaw;
        }
    }
}