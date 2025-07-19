package alpha.nomagichttp;

import alpha.nomagichttp.action.AfterAction;
import alpha.nomagichttp.action.BeforeAction;
import alpha.nomagichttp.handler.ExceptionHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.handler.ResponseRejectedException;
import alpha.nomagichttp.message.BadHeaderException;
import alpha.nomagichttp.message.ContentHeaders;
import alpha.nomagichttp.message.IllegalResponseBodyException;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.util.FileLockTimeoutException;
import alpha.nomagichttp.util.ScopedValues;

import java.io.IOException;

/// A transmitter of data from a response to an underlying byte channel.
/// 
/// The life-cycle of the implementation is bound to an active HTTP exchange.
/// 
/// The implementation reference should not be cached, but rather retrieved anew
/// using [ScopedValues#channel()].
/// 
/// @implSpec
/// The implementation is not thread-safe.
/// 
/// The implementation inherits the identity-based implementations of
/// [Object#hashCode()] and [Object#equals(Object)].
/// 
/// @author Martin Andersson (webmaster at martinandersson.com)
public interface ChannelWriter
{
    /// Writes a response.
    /// 
    /// Application code should generally not have a need to use this method —
    /// normally, the call chain of [BeforeAction] and the [RequestHandler],
    /// and the call chain of [ExceptionHandler], returns a final response to
    /// the server which in turn uses this method to write the response.
    /// 
    /// An [AfterAction] must never use this method as it would result in
    /// infinite recursion and undefined application behavior. The writer
    /// implementation does not protect itself from recursion.
    /// 
    /// Only at most one 100 (Continue) response will be written. Repeated 100
    /// (Continue) responses are ignored.
    /// 
    /// Also ignored — unless configured differently by
    /// [Config#discardRejectedInformational()] — is any attempt to write a
    /// 1XX (Informational) to a client older than HTTP/1.1.
    /// 
    /// In both of the aforementioned cases, this method returns immediately
    /// with the value 0.
    /// 
    /// Otherwise, the method will invoke all applicable [AfterAction], and then
    /// write the response to the underlying byte channel. If there is a
    /// response body, the bytebuffers will have their position updated (to the
    /// limit).
    /// 
    /// The returned number of bytes written includes both the head and body,
    /// and it does not overflow; it is capped at `Long.MAX_VALUE`.
    /// 
    /// @apiNote
    /// The implementation does not limit how long time it may take to write a
    /// response, nor the number of bytes it may contain; this method will not
    /// return for as long as the response body keeps producing data.
    /// 
    /// @param response the response to write
    /// 
    /// @return the number of bytes written
    /// 
    /// @throws NullPointerException
    ///             if `response` is `null`
    /// @throws IllegalStateException
    ///             if the HTTP exchange to which the writer was bound, is over
    /// @throws IllegalStateException
    ///             if a previous response began writing
    ///             but never finished (channel is corrupt)
    /// @throws IllegalStateException
    ///             if `response.`[isFinal()][Response#isFinal()] returns `true`
    ///             and a final response has already been written
    /// @throws ResponseRejectedException
    ///             for a [Reason][ResponseRejectedException.Reason]
    /// @throws BadHeaderException
    ///             for the same reasons as specified in
    ///             [ContentHeaders#contentLength()]
    /// @throws IllegalArgumentException
    ///             if message framing is invalid (for example,
    ///             a Content-Length header has been set in a 1xx response)
    /// @throws IllegalResponseBodyException
    ///             if the status code is one of 1XX (Informational),
    ///             204 (No Content), 304 (Not Modified) — and the response body
    ///             is not knowingly empty
    /// @throws IllegalResponseBodyException
    ///             if the request — to which the response is a response — has
    ///             HTTP method `HEAD` or `CONNECT`
    /// @throws InterruptedException
    ///             if interrupted (could be from a file-backed response body)
    /// @throws FileLockTimeoutException
    ///             if a file lock is not acquired within an acceptable
    ///             time frame (could be from a file-backed response body)
    /// @throws IOException
    ///             if an I/O error occurs
    ///             (could be from a file-backed response body,
    ///              as well as from the underlying byte channel)
    /// @throws IdleConnectionException
    ///             if a write operation on the
    ///             underlying byte channel times out
    long write(Response response)
            throws InterruptedException, FileLockTimeoutException, IOException;
    
    /// {@return whether a final response has been written}
    boolean wroteFinal();
    
    /// {@return the number of bytes written to the underlying byte channel}
    /// 
    /// The count is never reset. It reflects the total number of bytes written
    /// since the beginning of the HTTP exchange until this method is called.
    long byteCount();
}
