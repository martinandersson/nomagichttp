package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.util.AbstractImmutableBuilder;
import alpha.nomagichttp.util.ByteBufferIterables;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static alpha.nomagichttp.HttpConstants.StatusCode.THREE_HUNDRED_FOUR;
import static alpha.nomagichttp.HttpConstants.StatusCode.TWO_HUNDRED_FOUR;
import static alpha.nomagichttp.util.Strings.requireNoSurroundingWS;
import static java.util.Objects.requireNonNull;

/**
 * Default implementation of {@code Response}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class DefaultResponse implements Response
{
    /** Initial capacity of the list of a header map value. */
    private static final int INITIAL_CAPACITY = 1;
    
    private final int statusCode;
    private final String reasonPhrase;
    private final ContentHeaders headers;
    private final ResourceByteBufferIterable body;
    private final Supplier<LinkedHashMap<String, List<String>>> trailers;
    private final DefaultBuilder origin;
    
    private DefaultResponse(
            int statusCode,
            String reasonPhrase,
            ContentHeaders headers,
            // Is unmodifiable
            ResourceByteBufferIterable body,
            Supplier<LinkedHashMap<String, List<String>>> trailers,
            DefaultBuilder origin)
    {
        this.statusCode = statusCode;
        this.reasonPhrase = reasonPhrase;
        this.headers = headers;
        this.body = body;
        this.trailers = trailers;
        this.origin = origin;
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
    public ContentHeaders headers() {
        return headers;
    }
    
    @Override
    public ResourceByteBufferIterable body() {
        return body;
    }
    
    @Override
    public LinkedHashMap<String, List<String>> trailers() {
        return trailers == null ? null : trailers.get();
    }
    
    @Override
    public Response.Builder toBuilder() {
        return origin;
    }
    
    @Override
    public String toString() {
        return DefaultResponse.class.getSimpleName() + "{" +
                "statusCode=" + statusCode +
                ", reasonPhrase='" + reasonPhrase + '\'' +
                ", headers=?" +
                ", body=?" +
                '}';
    }
    
    /**
     * Default implementation of {@code Response.Builder}.
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     */
    static final class DefaultBuilder
            extends AbstractImmutableBuilder<DefaultBuilder.MutableState>
            implements Response.Builder
    {
        private static class MutableState {
            Integer statusCode;
            String reasonPhrase;
            LinkedHashMap<String, List<String>> headers;
            ResourceByteBufferIterable body;
            Supplier<LinkedHashMap<String, List<String>>> trailers;
            
            void removeHeader(String name) {
                if (headers == null) {
                    return;
                }
                headers.entrySet().removeIf(e ->
                    e.getKey().equalsIgnoreCase(name));
            }
            
            void removeHeaderValue(String name, String value) {
                if (headers == null) {
                    return;
                }
                var it = headers.entrySet().iterator();
                while (it.hasNext()) {
                    var e = it.next();
                    if (!e.getKey().equalsIgnoreCase(name)) {
                        continue;
                    }
                    e.getValue().removeIf(v ->
                        v.equalsIgnoreCase(value));
                    if (e.getValue().isEmpty()) {
                        it.remove();
                    }
                }
            }
            
            void addHeader(boolean clearFirst, String name, String value) {
                var vals = getOrCreateEntry(name);
                if (clearFirst) {
                    vals.clear();
                }
                vals.add(value);
            }
            
            void appendHeaderToken(String name, String token) {
                var vals = getOrCreateEntry(name);
                boolean success = false;
                // In reverse
                for (int i = vals.size() - 1; i >= 0; --i) {
                    var v = vals.get(i);
                    if (v.isEmpty()) {
                        continue;
                    }
                    vals.remove(i);
                    vals.add(i, v + ", " + token);
                    success = true;
                    break;
                }
                if (!success) {
                    vals.add(token);
                }
            }
            
            private List<String> getOrCreateEntry(String name) {
                return getOrCreateHeaders().computeIfAbsent(
                        name, k -> new ArrayList<>(INITIAL_CAPACITY));
            }
            
            private Map<String, List<String>> getOrCreateHeaders() {
                var h = headers;
                return h == null ? (headers = new LinkedHashMap<>()) : h;
            }
        }
        
        static final Response.Builder ROOT = new DefaultBuilder();
        
        private DefaultBuilder() {
            // super()
        }
        
        private DefaultBuilder(DefaultBuilder prev, Consumer<MutableState> modifier) {
            super(prev, modifier);
        }
        
        @Override
        public Response.Builder statusCode(int statusCode) {
            return new DefaultBuilder(this, s -> s.statusCode = statusCode);
        }
        
        @Override
        public Response.Builder reasonPhrase(String reasonPhrase) {
            requireNonNull(reasonPhrase, "reasonPhrase");
            return new DefaultBuilder(this, s -> s.reasonPhrase = reasonPhrase);
        }
        
        @Override
        public Response.Builder header(String name, String value) {
            final String key = requireNotEmpty(requireNoSurroundingWS(name)),
                         val = requireNoSurroundingWS(value);
            return new DefaultBuilder(this, s -> s.addHeader(true, key, val));
        }
        
        @Override
        public Response.Builder removeHeader(String name) {
            final String key = requireNotEmpty(requireNoSurroundingWS(name));
            return new DefaultBuilder(this, s -> s.removeHeader(key));
        }
        
        @Override
        public Response.Builder removeHeaderValue(String name, String value) {
            final String key = requireNotEmpty(requireNoSurroundingWS(name)),
                         val = requireNoSurroundingWS(value);
            return new DefaultBuilder(this, s -> s.removeHeaderValue(key, val));
        }
        
        @Override
        public Response.Builder addHeader(String name, String value) {
            final String key = requireNotEmpty(requireNoSurroundingWS(name)),
                         val = requireNoSurroundingWS(value);
            return new DefaultBuilder(this, s -> s.addHeader(false, key, val));
        }
        
        @Override
        public Response.Builder addHeaders(String name, String value, String... morePairs) {
            final String key1 = requireNotEmpty(requireNoSurroundingWS(name)),
                         val1 = requireNoSurroundingWS(value);
            for (int i = 0; i < morePairs.length; ++i) {
                if (i % 2 == 0) {
                    // Key
                    morePairs[i] = requireNotEmpty(requireNoSurroundingWS(morePairs[i]));
                } else {
                    // Val
                    morePairs[i] = requireNoSurroundingWS(morePairs[i]);
                }
            }
            if (morePairs.length % 2 != 0) {
                throw new IllegalArgumentException("morePairs.length is not even");
            }
            return new DefaultBuilder(this, ms -> {
                ms.addHeader(false, key1, val1);
                for (int i = 0; i < morePairs.length - 1; i += 2) {
                    String k = morePairs[i],
                           v = morePairs[i + 1];
                    ms.addHeader(false, k, v);
                }
            });
        }
        
        @Override
        public Builder appendHeaderToken(String name, String token) {
            requireNotEmpty(requireNoSurroundingWS(name));
            requireNotEmpty(requireNoSurroundingWS(token));
            return new DefaultBuilder(this, ms ->
                    ms.appendHeaderToken(name, token));
        }
        
        @Override
        public Response.Builder body(ResourceByteBufferIterable body) {
            requireNonNull(body, "body");
            return new DefaultBuilder(this, s -> s.body = body);
        }
        
        @Override
        public Builder addTrailers(Supplier<LinkedHashMap<String, List<String>>> trailers) {
            requireNonNull(trailers, "trailers");
            return new DefaultBuilder(this, s -> s.trailers = trailers);
        }
        
        @Override
        public Builder removeTrailers() {
            return new DefaultBuilder(this, s -> s.trailers = null);
        }
        
        @Override
        public Response build() {
            MutableState s = constructState(MutableState::new);
            setDefaults(s);
            
            final ContentHeaders headers;
            try {
                headers = s.headers == null ?
                        DefaultContentHeaders.empty() :
                        new DefaultContentHeaders(s.headers, false);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(e);
            }
            
            Response r = new DefaultResponse(
                    s.statusCode,
                    s.reasonPhrase,
                    headers,
                    s.body,
                    s.trailers,
                    this);
            
            if (r.isInformational()) {
                if (r.headers().hasConnectionClose()) {
                    throw new IllegalStateException(
                            "\"Connection: close\" set on 1XX (Informational) response.");
                }
                if (!isEmpty(r)) {
                    throw IllegalResponseBodyException(r);
                }
            } else if ((r.statusCode() == TWO_HUNDRED_FOUR    ||
                        r.statusCode() == THREE_HUNDRED_FOUR) && !isEmpty(r)) {
                throw IllegalResponseBodyException(r);
            }
            
            return r;
        }
        
        private static String requireNotEmpty(String name) {
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Empty header name");
            }
            return name;
        }
        
        private static void setDefaults(MutableState s) {
            if (s.reasonPhrase == null) {
                s.reasonPhrase = HttpConstants.ReasonPhrase.UNKNOWN; }
            
            if (s.body == null) {
                s.body = ByteBufferIterables.empty(); }
        }
        
        private static boolean isEmpty(Response r) {
            try {
                return r.body().isEmpty();
            } catch (IOException ignored) {
                // Something caused that to happen, after all
                return true;
            }
        }
        
        private static IllegalResponseBodyException IllegalResponseBodyException(Response r) {
            return new IllegalResponseBodyException(
                    "Presumably a body in a " + r.statusCode() + " (" + r.reasonPhrase() + ") response.", r);
        }
    }
}