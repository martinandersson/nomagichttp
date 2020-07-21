package alpha.nomagichttp.route;

/**
 * TODO: Docs
 */
// https://www.w3.org/Protocols/rfc2616/rfc2616-sec5.html#sec5.1.1
// Server should respond "501 (Not Implemented) if the method is unrecognized or not implemented by the origin server"
public class NoHandlerFoundException extends RuntimeException {
    NoHandlerFoundException(String message) {
        super(message);
    }
}