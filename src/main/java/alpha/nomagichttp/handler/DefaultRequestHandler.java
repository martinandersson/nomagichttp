package alpha.nomagichttp.handler;

import alpha.nomagichttp.message.MediaRange;
import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.Request;

import java.util.Objects;
import java.util.StringJoiner;
import java.util.function.BiConsumer;

import static alpha.nomagichttp.message.MediaType.__ALL;
import static alpha.nomagichttp.message.MediaType.__NOTHING;
import static alpha.nomagichttp.message.MediaType.__NOTHING_AND_ALL;
import static java.text.MessageFormat.format;
import static java.util.Objects.requireNonNull;

/**
 * Default implementation of {@link RequestHandler}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class DefaultRequestHandler implements RequestHandler
{
    private final String method;
    private final MediaType consumes, produces;
    private final BiConsumer<Request, ClientChannel> logic;
    private final int hash;
    
    DefaultRequestHandler(
            String method,
            MediaType consumes,
            MediaType produces,
            BiConsumer<Request, ClientChannel> logic)
    {
        this.method   = requireNonNull(method);
        this.consumes = validateConsumes(consumes);
        this.produces = validateProduces(produces);
        this.logic    = requireNonNull(logic);
        this.hash     = Objects.hash(method, consumes, produces);;
    }
    
    @Override
    public String method() {
        return method;
    }
    
    @Override
    public MediaType consumes() {
        return consumes;
    }
    
    @Override
    public MediaType produces() {
        return produces;
    }
    
    @Override
    public BiConsumer<Request, ClientChannel> logic() {
        return logic;
    }
    
    @Override
    public int hashCode() {
        return hash;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        
        if (obj == null) {
            return false;
        }
        
        if (DefaultRequestHandler.class != obj.getClass()) {
            return false;
        }
        
        DefaultRequestHandler other = (DefaultRequestHandler) obj;
        
        return this.method.equals(other.method) &&
               this.consumes.equals(other.consumes) &&
               this.produces.equals(other.produces);
    }
    
    @Override
    public String toString() {
        StringJoiner contents = new StringJoiner(", ")
                .add("method=\""   + method   + "\"")
                .add("consumes=\"" + consumes + "\"")
                .add("produces=\"" + produces + "\"");
        
        return DefaultRequestHandler.class.getSimpleName() + '{' + contents + '}';
    }
    
    private static MediaType validateConsumes(MediaType consumes) {
        return requireQualityOne(requireNonNull(consumes));
    }
    
    private static MediaType validateProduces(MediaType produces) {
        requireQualityOne(requireNonNull(produces));
        requireNotSame(produces, __NOTHING);
        requireNotSame(produces, __NOTHING_AND_ALL);
        return produces;
    }
    
    private static MediaType requireQualityOne(MediaType type) {
        if (type instanceof MediaRange && ((MediaRange) type).quality() != 1.) {
            throw new IllegalArgumentException(
                    "A handler can not consume or produce a media range with a different quality than 1.");
        }
        
        return type;
    }
    
    private static void requireNotSame(MediaType produces, MediaType invalid) {
        if (produces == invalid) {
            throw new IllegalArgumentException(format(
                    "Handler's producing media type must not be \"{0}\". Maybe try \"{1}\"?",
                    invalid, __ALL));
        }
    }
    
    /**
     * Default implementation of {@link RequestHandler.Builder}.
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     */
    static class Builder implements RequestHandler.Builder {
        private final String m;
        
        Builder(String method) {
            if (method.isEmpty()) {
                throw new IllegalArgumentException("Empty method.");
            }
            
            if (method.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException(
                        "Whitespace in method \"" + method + "\".");
            }
            
            m = method;
        }
        
        @Override
        public NextStep consumes(MediaType c) {
            requireNonNull(c);
            return p -> {
                requireNonNull(p);
                return l-> {
                    requireNonNull(l);
                    return new DefaultRequestHandler(m, c, p, l);
                };
            };
        }
    }
}