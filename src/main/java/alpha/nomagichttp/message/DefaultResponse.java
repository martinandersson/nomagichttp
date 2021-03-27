package alpha.nomagichttp.message;

import java.net.http.HttpHeaders;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

import static alpha.nomagichttp.HttpConstants.HeaderKey.CONTENT_LENGTH;
import static alpha.nomagichttp.HttpConstants.HeaderKey.CONTENT_TYPE;
import static alpha.nomagichttp.util.Publishers.empty;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toUnmodifiableList;

/**
 * Default implementation of {@link Response}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class DefaultResponse implements Response
{
    private final int statusCode;
    private final String reasonPhrase;
    private final Iterable<String> headers;
    private final Flow.Publisher<ByteBuffer> body;
    private final boolean mustCloseAfterWrite;
    
    private DefaultResponse(
            int statusCode,
            String reasonPhrase,
            // Is unmodifiable
            Iterable<String> headers,
            Flow.Publisher<ByteBuffer> body,
            boolean mustCloseAfterWrite)
    {
        this.statusCode = statusCode;
        this.reasonPhrase = reasonPhrase;
        this.headers = headers;
        this.body = body;
        this.mustCloseAfterWrite = mustCloseAfterWrite;
    }
    
    @Override
    public int statusCode() {
        return statusCode;
    }
    
    @Override
    public String reasonPhrase() {
        return reasonPhrase;
    }
    
    @Override
    public Iterable<String> headers() {
        return headers;
    }
    
    @Override
    public Flow.Publisher<ByteBuffer> body() {
        return body;
    }
    
    @Override
    public boolean mustCloseAfterWrite() {
        return mustCloseAfterWrite;
    }
    
    @Override
    public String toString() {
        return DefaultResponse.class.getSimpleName() + "{" +
                "statusCode=" + statusCode +
                ", reasonPhrase='" + reasonPhrase + '\'' +
                ", headers=?" +
                ", body=?" +
                ", mustCloseAfterWrite=" + mustCloseAfterWrite +
                '}';
    }
    
    /**
     * Default implementation of {@link Response.Builder}.
     *
     * @author Martin Andersson (webmaster at martinandersson.com)
     */
    final static class Builder implements Response.Builder
    {
        // How this works: Builders are backwards-linked in a chain and the only
        // real state they each store is a modifying action, which is replayed
        // against a mutable state container during construction time.
        
        private static class MutableState {
            Integer statusCode;
            String reasonPhrase;
            Map<String, List<String>> headers;
            Flow.Publisher<ByteBuffer> body;
            Boolean mustCloseAfterWrite;
            
            void removeHeader(String name) {
                assert name != null;
                
                if (headers == null) {
                    return;
                }
                headers.remove(name);
            }
            
            void addHeader(boolean clearFirst, String name, String value) {
                assert name != null;
                assert value != null;
                
                List<String> v = getOrCreateHeaders()
                        .computeIfAbsent(name, k -> new ArrayList<>(1));
                
                if (clearFirst) {
                    v.clear();
                }
                
                v.add(value);
            }
            
            private Map<String, List<String>> getOrCreateHeaders() {
                if (headers == null) {
                    headers = new LinkedHashMap<>();
                }
                return headers;
            }
        }
        
        static final Response.Builder ROOT = new Builder(null, null);
        
        final Builder prev;
        final Consumer<MutableState> modifier;
        
        private Builder(Builder prev, Consumer<MutableState> modifier) {
            this.prev = prev;
            this.modifier = modifier;
        }
        
        @Override
        public Response.Builder statusCode(int statusCode) {
            return new Builder(this, s -> s.statusCode = statusCode);
        }
        
        @Override
        public Response.Builder reasonPhrase(String reasonPhrase) {
            requireNonNull(reasonPhrase, "reasonPhrase");
            return new Builder(this, s -> s.reasonPhrase = reasonPhrase);
        }
        
        @Override
        public Response.Builder header(String name, String value) {
            requireNonNull(name, "name");
            requireNonNull(value, "value");
            return new Builder(this, s -> s.addHeader(true, name, value));
        }
        
        @Override
        public Response.Builder contentType(MediaType type) {
            requireNonNull(type, "type");
            return addHeaders(CONTENT_TYPE, type.toString());
        }
        
        @Override
        public Response.Builder contentLenght(long value) {
            return addHeaders(CONTENT_LENGTH, Long.toString(value));
        }
        
        @Override
        public Response.Builder removeHeader(String name) {
            requireNonNull(name, "name");
            return new Builder(this, s -> s.removeHeader(name));
        }
        
        @Override
        public Response.Builder addHeader(String name, String value) {
            requireNonNull(name, "name");
            requireNonNull(value, "value");
            return new Builder(this, s -> s.addHeader(false, name, value));
        }
        
        @Override
        public Response.Builder addHeaders(String name, String value, String... morePairs) {
            requireNonNull(name, "name");
            requireNonNull(value, "value");
            
            for (int i = 0; i < morePairs.length; ++i) {
                requireNonNull(morePairs[i], "morePairs[" + i + "]");
            }
            
            if (morePairs.length % 2 != 0) {
                throw new IllegalArgumentException("morePairs.length is not even");
            }
            
            return new Builder(this, ms -> {
                ms.addHeader(false, name, value);
                
                for (int i = 0; i < morePairs.length - 1; i += 2) {
                    String k = morePairs[i],
                           v = morePairs[i + 1];
                    
                    ms.addHeader(false, k, v);
                }
            });
        }
        
        @Override
        public Response.Builder addHeaders(HttpHeaders headers) {
            requireNonNull(headers, "headers");
            return new Builder(this, s ->
                    headers.map().forEach((name, values) ->
                            values.forEach(v -> s.addHeader(false, name, v))));
        }
        
        @Override
        public Response.Builder body(Flow.Publisher<ByteBuffer> body) {
            requireNonNull(body, "body");
            return new Builder(this, s -> s.body = body);
        }
        
        @Override
        public Response.Builder mustCloseAfterWrite(boolean enabled) {
            return new Builder(this, s -> s.mustCloseAfterWrite = enabled);
        }
        
        private Response response;
        
        @Override
        public Response build() {
            Response r = response;
            return r != null ? r : (response = construct());
        }
        
        private Response construct() {
            MutableState s = new MutableState();
            
            populate(s);
            validate(s);
            setDefaults(s);
            
            Iterable<String> headers = s.headers == null ? emptyList() :
                    s.headers.entrySet().stream().flatMap(e ->
                            e.getValue().stream().map(v -> e.getKey() + ": " + v))
                    .collect(toUnmodifiableList());
            
            return new DefaultResponse(
                    s.statusCode, s.reasonPhrase, headers, s.body, s.mustCloseAfterWrite);
        }
        
        private void populate(MutableState s) {
            Deque<Consumer<MutableState>> mods = new ArrayDeque<>();
            
            for (Builder b = this; b.modifier != null; b = b.prev) {
                mods.addFirst(b.modifier);
            }
            
            mods.forEach(m -> m.accept(s));
        }
        
        private static void validate(MutableState s) {
            if (s.statusCode == null) {
                throw new IllegalArgumentException("Status code not set."); }
        }
        
        private static void setDefaults(MutableState s) {
            if (s.reasonPhrase == null) {
                s.reasonPhrase = "Unknown"; }
            
            if (s.body == null) {
                s.body = empty(); }
            
            if (s.mustCloseAfterWrite == null) {
                s.mustCloseAfterWrite = false; }
        }
    }
}