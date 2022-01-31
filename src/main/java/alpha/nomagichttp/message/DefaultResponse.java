package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.util.AbstractImmutableBuilder;
import alpha.nomagichttp.util.Publishers;

import java.net.http.HttpHeaders;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

import static alpha.nomagichttp.HttpConstants.HeaderName.CONNECTION;
import static alpha.nomagichttp.HttpConstants.HeaderName.CONTENT_LENGTH;
import static alpha.nomagichttp.HttpConstants.StatusCode.THREE_HUNDRED_FOUR;
import static alpha.nomagichttp.HttpConstants.StatusCode.TWO_HUNDRED_FOUR;
import static alpha.nomagichttp.util.Headers.of;
import static alpha.nomagichttp.util.Publishers.empty;
import static java.net.http.HttpRequest.BodyPublisher;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;

/**
 * Default implementation of {@link Response}.
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
    private final Flow.Publisher<ByteBuffer> body;
    private final DefaultBuilder origin;
    
    private DefaultResponse(
            int statusCode,
            String reasonPhrase,
            HttpHeaders headers,
            // Is unmodifiable
            Iterable<String> forWriting,
            Flow.Publisher<ByteBuffer> body,
            DefaultBuilder origin)
    {
        this.statusCode = statusCode;
        this.reasonPhrase = reasonPhrase;
        this.headers = new DefaultContentHeaders(headers);
        this.forWriting = forWriting;
        this.body = body;
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
    public Flow.Publisher<ByteBuffer> body() {
        return body;
    }
    
    @Override
    public boolean isBodyEmpty() {
        Flow.Publisher<ByteBuffer> b = body();
        if (b == Publishers.<ByteBuffer>empty()) {
            return true;
        }
        if (b instanceof BodyPublisher typed) {
            return typed.contentLength() == 0;
        }
        return headers().contain(CONTENT_LENGTH, "0");
    }
    
    private CompletionStage<Response> stage;
    
    @Override
    public CompletionStage<Response> completedStage() {
        var s = stage;
        return s != null ? s : (stage = CompletableFuture.completedStage(this));
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
     * Default implementation of {@link Response.Builder}.
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
            Flow.Publisher<ByteBuffer> body;
            
            void removeHeader(String name) {
                assert name != null;
                if (headers == null) {
                    return;
                }
                headers.entrySet().removeIf(e ->
                    e.getKey().equalsIgnoreCase(name));
            }
            
            void removeHeaderValue(String name, String presentValue) {
                assert name != null;
                assert presentValue != null;
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
                        v.equalsIgnoreCase(presentValue));
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
            return new DefaultBuilder(this, s -> s.statusCode = statusCode);
        }
        
        @Override
        public Response.Builder reasonPhrase(String reasonPhrase) {
            requireNonNull(reasonPhrase, "reasonPhrase");
            return new DefaultBuilder(this, s -> s.reasonPhrase = reasonPhrase);
        }
        
        @Override
        public Response.Builder header(String name, String value) {
            requireNonNull(name, "name");
            requireNonNull(value, "value");
            return new DefaultBuilder(this, s -> s.addHeader(true, name, value));
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
            return new DefaultBuilder(this, s -> s.removeHeaderValue(name, value));
        }
        
        @Override
        public Response.Builder addHeader(String name, String value) {
            requireNonNull(name, "name");
            requireNonNull(value, "value");
            return new DefaultBuilder(this, s -> s.addHeader(false, name, value));
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
            requireNonNull(headers, "headers");
            return new DefaultBuilder(this, s ->
                    headers.forEach((name, values) ->
                            values.forEach(v -> s.addHeader(false, name, v))));
        }
        
        @Override
        public Response.Builder body(Flow.Publisher<ByteBuffer> body) {
            requireNonNull(body, "body");
            final DefaultBuilder b = new DefaultBuilder(this, s -> s.body = body);
            
            if (body == Publishers.<ByteBuffer>empty()) {
                return b.removeHeader(CONTENT_LENGTH);
            } else if (body instanceof BodyPublisher) {
                long len = ((BodyPublisher) body).contentLength();
                if (len < 0) {
                    return b.removeHeader(CONTENT_LENGTH);
                } else {
                    return b.header(CONTENT_LENGTH, Long.toString(len));
                }
            }
            
            return b;
        }
        
        @Override
        public Response build() {
            MutableState s = constructState(MutableState::new);
            setDefaults(s);
            
            final HttpHeaders headers = s.headers == null ? of() : of(s.headers);
            
            if (headers.allValues(CONTENT_LENGTH).size() > 1) {
                throw new IllegalStateException("Multiple " + CONTENT_LENGTH + " headers.");
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
                    this);
            
            if (r.isInformational()) {
                if (r.headers().contain(CONNECTION, "close")) {
                    throw new IllegalStateException(
                            "\"Connection: close\" set on 1XX (Informational) response.");
                }
                if (!r.isBodyEmpty()) {
                    throw IllegalResponseBodyException(r);
                }
            } else if ((r.statusCode() == TWO_HUNDRED_FOUR    ||
                        r.statusCode() == THREE_HUNDRED_FOUR) && !r.isBodyEmpty()) {
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
        
        private static IllegalResponseBodyException IllegalResponseBodyException(Response r) {
            return new IllegalResponseBodyException(
                    "Presumably a body in a 1XX (Informational) response.", r);
        }
    }
}