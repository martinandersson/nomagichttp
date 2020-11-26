package alpha.nomagichttp.handler;

import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static alpha.nomagichttp.message.MediaType.ALL;
import static alpha.nomagichttp.message.MediaType.NOTHING;
import static alpha.nomagichttp.message.MediaType.NOTHING_AND_ALL;
import static alpha.nomagichttp.message.MediaType.TEXT_PLAIN;
import static alpha.nomagichttp.message.MediaType.parse;
import static alpha.nomagichttp.message.Responses.accepted;
import static java.util.Objects.requireNonNull;

/**
 * Builds the default implementation of {@link RequestHandler}.<p>
 * 
 * This class guides the user through a series of steps along the process of
 * building a handler.<p>
 * 
 * The first step is the constructor which requires an HTTP method such as
 * "GET", "POST" or anything else - it's just a string after all
 * (case-sensitive).<p>
 * 
 * The HandlerBuilder instance will then expose methods that specifies what
 * media-type the handler consumes.<p>
 * 
 * Once the consumption media-type has been specified, the next step is to
 * specify what media-type the handler produces.<p>
 * 
 * The last step will be to specify the logic of the handler. The adapter
 * methods offered by this class to do so, comes in many flavors which accepts
 * different functional types depending on the needs of the application.<p>
 * 
 * {@code run()} receives a no-args {@code Runnable} which represents logic that
 * does not need to access the request object and has no need to customize the
 * "202 Accepted" response sent back to the client. This flavor is useful for
 * handlers that will accept all requests as a command to initiate processes on
 * the server.<p>
 * 
 * {@code accept()} is very much similar to {@code run()}, except the logic is
 * represented by a {@code Consumer} who will receive the request object and can
 * therefore read meaningful data out of it.<p>
 * 
 * {@code supply()} receives a {@code Supplier} which represents logic that is
 * not interested in the request object but does have the need to return a fully
 * customizable response.<p>
 * 
 * {@code apply()} receives a {@code Function} which has access to the request
 * object <i>and</i> returns a fully customizable response.
 */
public final class HandlerBuilder
{
    private final String method;
    
    public HandlerBuilder(String method) {
        this.method = requireNonNull(method);
    }
    
    private MediaType consumes;
    
    public NextStep consumesNothing() {
        return consumes(NOTHING);
    }
    
    public NextStep consumesAll() {
        return consumes(ALL);
    }
    
    public NextStep consumesNothingAndAll() {
        return consumes(NOTHING_AND_ALL);
    }
    
    public NextStep consumesTextPlain() {
        return consumes(TEXT_PLAIN);
    }
    
    public NextStep consumes(String mediaType) {
        return consumes(parse(mediaType));
    }
    
    public NextStep consumes(MediaType mediaType) {
        consumes = mediaType;
        return new NextStep(this);
    }
    
    // TODO: Lots more
    
    public static final class NextStep extends Link<HandlerBuilder> {
        NextStep(HandlerBuilder builder) {
            super(builder);
        }
        
        private MediaType produces;
        
        public LastStep producesAll() {
            return produces(ALL);
        }
        
        public LastStep producesTextPlain() {
            return produces(TEXT_PLAIN);
        }
        
        public LastStep produces(String mediaType) {
            return produces(parse(mediaType));
        }
        
        public LastStep produces(MediaType mediaType) {
            produces = mediaType;
            return new LastStep(this);
        }
        
        // TODO: Lots more
    }
    
    /**
     * @see HandlerBuilder
     */
    public static final class LastStep extends Link<NextStep> {
        LastStep(NextStep prev) {
            super(prev);
        }
        
        public RequestHandler run(Runnable logic) {
            requireNonNull(logic);
            return accept(requestIgnored -> logic.run());
        }
        
        public RequestHandler accept(Consumer<Request> logic) {
            requireNonNull(logic);
            return apply(req -> {
                logic.accept(req);
                return accepted().asCompletedStage();
            });
        }
        
        public RequestHandler supply(Supplier<CompletionStage<Response>> logic) {
            requireNonNull(logic);
            return apply(requestIgnored -> logic.get());
        }
        
        public RequestHandler apply(Function<Request, CompletionStage<Response>> logic) {
            return new DefaultRequestHandler(
                    prev.prev.method,
                    prev.prev.consumes,
                    prev.produces,
                    logic);
        }
    }
    
    private static abstract class Link<P> {
        final P prev;
        
        Link(P prev) {
            this.prev = prev;
        }
    }
}