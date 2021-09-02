package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.util.AbstractImmutableBuilder;
import alpha.nomagichttp.util.Headers;
import alpha.nomagichttp.util.Publishers;

import java.net.http.HttpHeaders;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

import static alpha.nomagichttp.HttpConstants.HeaderKey.CONNECTION;
import static alpha.nomagichttp.HttpConstants.HeaderKey.CONTENT_LENGTH;
import static alpha.nomagichttp.HttpConstants.StatusCode.THREE_HUNDRED_FOUR;
import static alpha.nomagichttp.HttpConstants.StatusCode.TWO_HUNDRED_FOUR;
import static alpha.nomagichttp.util.Publishers.empty;
import static java.net.http.HttpRequest.BodyPublisher;
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
    /** Initial capacity of the list of a header map value. */
    private static final int INITIAL_CAPACITY = 1;
    
    private final int statusCode;
    private final String reasonPhrase;
    private final HttpHeaders headers;
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
        this.headers = headers;
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
    public HttpHeaders headers() {
        return headers;
    }
    
    private Optional<MediaType> cc;
    
    @Override
    public Optional<MediaType> headerValContentType() {
        var cc = this.cc;
        if (cc == null) {
            this.cc = cc = Headers.contentType(headers());
        }
        return cc;
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
        if (b instanceof BodyPublisher) {
            var typed = (BodyPublisher) b;
            return typed.contentLength() == 0;
        }
        return headerContains(CONTENT_LENGTH, "0");
    }
    
    private CompletionStage<Response> stage;
    
    @Override
    public CompletionStage<Response> completedStage() {
        var s = stage;
        if (s == null) {
            s = stage = CompletableFuture.completedStage(this);
        }
        return s;
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
    final static class DefaultBuilder
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
                headers.remove(name);
            }
            
            void removeHeaderIf(String name, String presentValue) {
                assert name != null;
                assert presentValue != null;
                if (headers == null) {
                    return;
                }
                headers.entrySet().stream()
                       .filter(e -> e.getKey().equalsIgnoreCase(name))
                       .forEach(e -> {
                           Iterator<String> v = e.getValue().iterator();
                           while (v.hasNext() && v.next().equalsIgnoreCase(presentValue)) {
                               v.remove();
                           }
                       });
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
        public Response.Builder removeHeaderIf(String name, String presentValue) {
            requireNonNull(name, "name");
            requireNonNull(presentValue, "presentValue");
            return new DefaultBuilder(this, s -> s.removeHeaderIf(name, presentValue));
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
        public Response.Builder addHeaders(HttpHeaders headers) {
            requireNonNull(headers, "headers");
            return new DefaultBuilder(this, s ->
                    headers.map().forEach((name, values) ->
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
            
            HttpHeaders headers = s.headers == null ? Headers.of() :
                    HttpHeaders.of(s.headers, (k, v) -> true);
            
            if (headers.allValues(CONTENT_LENGTH).size() > 1) {
                throw new IllegalStateException("Multiple " + CONTENT_LENGTH + " headers.");
            }
            
            Iterable<String> forWriting = s.headers == null ? emptyList() :
                    s.headers.entrySet().stream().flatMap(e ->
                            e.getValue().stream().map(v -> e.getKey() + ": " + v))
                    .collect(toUnmodifiableList());
            
            Response r = new DefaultResponse(
                    s.statusCode,
                    s.reasonPhrase,
                    headers,
                    forWriting,
                    s.body,
                    this);
            
            if (r.isInformational()) {
                if (r.headerContains(CONNECTION, "close")) {
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