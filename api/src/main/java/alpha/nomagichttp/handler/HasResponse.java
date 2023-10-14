package alpha.nomagichttp.handler;

import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;

/**
 * Adds {@link #getResponse()}.<p>
 * 
 * This interface is intended to be exclusively implemented by exception classes
 * from components aware of its HTTP environment (i.e., an exception class from
 * MyMathLibrary should not implement this interface).<p>
 * 
 * Implementing this interface is not mandatory, and the response is
 * advisory.<p>
 * 
 * The exception handler is free to derive a new response or return a different
 * one. Maybe the handler is configured to deploy "security by obscurity" hacks
 * for certain routes. Maybe the handler can actually resolve the problem and
 * return a successful response.<p>
 * 
 * Currently, {@link ExceptionHandler} does not support content negotiation.
 * Therefore, unless the application can make appropriate assumptions about the
 * client, the implementation should return a response without a body, or at
 * least default to "text/plain".<p>
 * 
 * For a more elaborative setup, the implementation can expose an API used by a
 * custom exception handler to build a body/representation most suitable for the
 * client.<p>
 * 
 * Implementing this interface has some benefits. The exception handler becomes
 * more cohesive as it will only contain meaningful logic, with no need to
 * interpret exceptions. Meanwhile, each implementing class is inherently a
 * fully documented and encapsulated HTTP problem.<p>
 * 
 * The developer learning a new code base — more specifically, what does this
 * random exception do — will not have to deploy advanced text search hacks, or
 * waste time jumping through hoops picking pieces together. It's one click to
 * open up the type, et voilà, everything [of relevance] is there.<p>
 * 
 * If the application enforces a convention to implement this interface for
 * <i>all</i> exceptions designated for global handlers, then the type alone
 * informs the apprentice whether the exception should be caught and handled by
 * a call site. With such a convention in place; semantically, any class that
 * does not implement this interface becomes a kind of checked exception, but
 * without signature boilerplate. Again, saving time for the apprentice.<p>
 * 
 * For the application developer, it'll be easy to discover which implementing
 * exception types the classpath provides, which encourages reuse and minimizes
 * accidental repetition. If no reusable type is fitting, the developer will
 * only have to create and commit one new type to the code base. There will be
 * no ceremony of also having to add a new exception handler, or copy and paste
 * repeated patterns in an obscured, and forever bloating handler somewhere
 * (applications with 100+ exception types suffer tremendously from this).<p>
 * 
 * If one does not see a web application as a well-coordinated set of related
 * components, but rather as a cake of unrelated layers, then it may be
 * provocative if a layer beneath the uppermost layer of endpoints, throws an
 * exception with an HTTP response.<p>
 * 
 * Maybe, in such an application, a request should be fully validated before the
 * <i>lowest</i> layer is reached. But, washing out every notion of HTTP from
 * all lower layers of what de facto is a web application, often leads to
 * pseudo-alternative constructs which in practice is the same thing.<p>
 * 
 * For example, creating and throwing a {@code PreconditionFailedException}
 * (<i>obviously</i> translated to {@code 412}). Or creating just one single
 * global, ill-defined type with an "error code" (<i>obviously</i> the status
 * code in disguise).<p>
 * 
 * Here is an example of an exception class with a clear responsibility
 * ("precondition failed"), which frees the application developer from having to
 * mind about any kind of code, whilst paving the way for discoverability,
 * traceability, maintainability, and extensibility:<p>
 * 
 * {@snippet :
 *   final class PreconditionFailedException
 *         extends RuntimeException implements HasResponse
 *   {
 *       // ifMatch from header, eTag from entity
 *       // (... in the future, more factories are added)
 *       static void requireEquals(String ifMatch, String eTag) {
 *           // @link substring="requireNonNull" target="java.util.Objects#requireNonNull(Object)" :
 *           if (!ifMatch.equals(requireNonNull(eTag))) {
 *               throw new PreconditionFailedException(
 *                     // @link substring="formatted" target="String#formatted(Object[])" :
 *                     "Expected version %s, actual %s".formatted(ifMatch, eTag));
 *           }
 *       }
 *       
 *       private PreconditionFailedException(String messageAndDetail) {
 *           super(messageAndDetail);
 *       }
 *       
 *       @Override
 *       public Response getResponse() {
 *           // @link substring="preconditionFailed" target="alpha.nomagichttp.message.Responses#preconditionFailed()" region
 *           // @link substring="toBuilder" target="alpha.nomagichttp.message.Response#toBuilder()" region
 *           // @link substring="CONTENT_TYPE" target="alpha.nomagichttp.HttpConstants.HeaderName#CONTENT_TYPE" region
 *           // @link substring="ofStringUnsafe" target="alpha.nomagichttp.util.ByteBufferIterables#ofStringUnsafe(java.lang.String)" region
 *           Responses.preconditionFailed().toBuilder()
 *                    .setHeader(CONTENT_TYPE, "application/problem+json")
 *                    .body(ByteBufferIterables.ofStringUnsafe("""
 *                        {
 *                          "detail": "%s"
 *                        }""".formatted(getMessage())))
 *                    .build();
 *           // @end
 *           // @end
 *           // @end
 *           // @end
 *       }
 *   }
 * }
 * 
 * This example assumed that the client accepts "application/problem+json" (
 * <a href="https://datatracker.ietf.org/doc/html/rfc9457">RFC 9457</a>).
 * Arguably, this could be made more elegant if the application provides its own
 * abstraction (e.g., {@code WithProblemDetail}) coupled with an exception
 * handler that queries the type, and with the request at hand, produces a
 * response based on what the client accepts.<p>
 * 
 * The NoMagicHTTP's {@linkplain ExceptionHandler#BASE base exception handler} —
 * if given an exception that implements {@code HasResponse} — returns the
 * response provided, unmodified. No HTTP-aware exception classes in the library
 * produce a response with a body.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface HasResponse {
    /**
     * Returns an advisory, fallback response for the exception handler.<p>
     * 
     * The exception class should never return a response indicating success.<p>
     * 
     * <i>Handling</i> of an exception is the job of {@link ExceptionHandler}. The
     * server's {@linkplain ExceptionHandler#BASE base handler} will respond
     * {@link Responses#teapot()}, if the response returned from this method has
     * a status code which is not in the 3XX (Redirection), 4XX (Client Error),
     * nor 5XX (Server Error) series.
     * 
     * @apiNote
     * The "get" prefix is to be consistent with {@code Throwable}'s API design.
     * It does not impose subjective/interpretive, and unnecessary
     * implementation requirements.
     * 
     * @return an advisory fallback response (never {@code null})
     */
    Response getResponse();
}
