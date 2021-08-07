package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.Request;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

class DefaultParameters implements Request.Parameters
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