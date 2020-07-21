package alpha.nomagichttp.handler;

import alpha.nomagichttp.message.MediaType;

/**
 * Factory methods for building the default handler implementation without any
 * regards related to media types. ALl handlers returned by this class consumes
 * {@link MediaType#NOTHING_AND_ALL} and produces {@link MediaType#ALL}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class Handlers
{
    private Handlers() {
        // Empty
    }
    
    public static HandlerBuilder.LastStep GET() {
        return method("GET");
    }
    
    public static HandlerBuilder.LastStep POST() {
        return method("POST");
    }
    
    public static HandlerBuilder.LastStep PUT() {
        return method("PUT");
    }
    
    public static HandlerBuilder.LastStep method(String method) {
        return new HandlerBuilder(method).consumesWhatever().producesAll();
    }
    
    // TODO: Lots of more stuff
    
    public static Handler noop() {
        return NOOP;
    }
    
    private static final Handler NOOP = GET().run(() -> {});
}