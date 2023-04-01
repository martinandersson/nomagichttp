package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.util.AbstractImmutableBuilder;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static alpha.nomagichttp.HttpConstants.StatusCode.THREE_HUNDRED_FOUR;
import static alpha.nomagichttp.HttpConstants.StatusCode.TWO_HUNDRED_FOUR;
import static alpha.nomagichttp.util.ByteBufferIterables.empty;
import static alpha.nomagichttp.util.Headers.of;
import static java.util.Collections.emptyList;
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
    private final Iterable<String> forWriting;
    private final ResourceByteBufferIterable body;
    private final Supplier<HttpHeaders> trailers;
    private final DefaultBuilder origin;
    
    private DefaultResponse(
            int statusCode,
            String reasonPhrase,
            HttpHeaders headers,
            // Is unmodifiable
            Iterable<String> forWriting,
            ResourceByteBufferIterable body,
            Supplier<HttpHeaders> trailers,
            DefaultBuilder origin)
    {
        this.statusCode = statusCode;
        this.reasonPhrase = reasonPhrase;
        this.headers = new DefaultContentHeaders(headers);
        this.forWriting = forWriting;
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
    public Iterable<String> headersForWriting() {
        return forWriting;
    }
    
    @Override
    public ResourceByteBufferIterable body() {
        return body;
    }
    
    @Override
    public HttpHeaders trailers() {
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
            Map<String, List<String>> headers;
            ResourceByteBufferIterable body;
            Supplier<HttpHeaders> trailers;
            
            void removeHeader(String name) {
                assert name != null;
                if (headers == null) {
                    return;
                }
                headers.entrySet().removeIf(e ->
                    e.getKey().equalsIgnoreCase(name));
            }
            
            void removeHeaderValue(String name, String value) {
                assert name != null;
                assert value != null;
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
                assert name != null;
                assert value != null;
                List<String> v = getOrCreateHeaders()
                        .computeIfAbsent(name, k -> new ArrayList<>(INITIAL_CAPACITY));
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
        
        static final Response.Builder ROOT = new DefaultBuilder();
        
        private DefaultBuilder() {
            // super()
        }
        
        private DefaultBuilder(DefaultBuilder prev, Consumer<MutableState> modifier) {
            super(prev, modifier);
        }
        
        @Override
        public Response.Builder statusCode(int statusCode) {
            return new DefaultBuilder(this, s ->
                    s.statusCode = statusCode);
        }
        
        @Override
        public Response.Builder reasonPhrase(String reasonPhrase) {
            requireNonNull(reasonPhrase, "reasonPhrase");
            return new DefaultBuilder(this, s ->
                    s.reasonPhrase = reasonPhrase);
        }
        
        @Override
        public Response.Builder header(String name, String value) {
            requireNonNull(name, "name");
            requireNonNull(value, "value");
            return new DefaultBuilder(this, s ->
                    s.addHeader(true, name, value));
        }
        
        @Override
        public Response.Builder removeHeader(String name) {
            requireNonNull(name, "name");
            return new DefaultBuilder(this, s -> s.removeHeader(name));
        }
        
        @Override
        public Response.Builder removeHeaderValue(String name, String value) {
            requireNonNull(name, "name");
            requireNonNull(value, "value");
            return new DefaultBuilder(this, s ->
                    s.removeHeaderValue(name, value));
        }
        
        @Override
        public Response.Builder addHeader(String name, String value) {
            requireNonNull(name, "name");
            requireNonNull(value, "value");
            return new DefaultBuilder(this, s ->
                    s.addHeader(false, name, value));
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
            return new DefaultBuilder(this, ms -> {
                ms.addHeader(false, name, value);
                for (int i = 0; i < morePairs.length - 1; i += 2) {
                    String k = morePairs[i],
                           v = morePairs[i + 1];
                    ms.addHeader(false, k, v);
                }
            });
        }
        
        @Override
        public Response.Builder addHeaders(Map<String, List<String>> headers) {
            requireNonNull(headers);
            return new DefaultBuilder(this, s ->
                    headers.forEach((name, values) ->
                            values.forEach(v -> s.addHeader(false, name, v))));
        }
        
        @Override
        public Response.Builder body(ResourceByteBufferIterable body) {
            requireNonNull(body, "body");
            return new DefaultBuilder(this, s -> s.body = body);
        }
        
        @Override
        public Builder addTrailers(Supplier<HttpHeaders> trailers) {
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
            
            final HttpHeaders headers;
            try {
                headers = s.headers == null ? of() : of(s.headers);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(e);
            }
            
            Iterable<String> forWriting = s.headers == null ? emptyList() :
                    s.headers.entrySet().stream().flatMap(e ->
                            e.getValue().stream().map(v -> e.getKey() + ": " + v))
                    .toList();
            
            Response r = new DefaultResponse(
                    s.statusCode,
                    s.reasonPhrase,
                    headers,
                    forWriting,
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
        
        private static void setDefaults(MutableState s) {
            if (s.reasonPhrase == null) {
                s.reasonPhrase = HttpConstants.ReasonPhrase.UNKNOWN; }
            
            if (s.body == null) {
                s.body = empty(); }
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