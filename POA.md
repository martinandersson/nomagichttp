# Plan Of Action

## About This Document

This document is a temporary scratchpad for the author's self-planned work. Its
function is to be an early-phase alternative to GitHub's milestones and issue
tracker.

When this document was first added to the repository, the NoMagicHTTP server was
only able to process a non-secured HTTP/1.1 exchange with no other HTTP features
supported than content negotiation. The planned work is to bring the server into
a state where most of all relevant HTTP/1.0, 1.1, and 2.0 specifications are
implemented before the first `1.0` release. The goal is to exemplify all
implemented features of HTTP, and document which features were never implemented
and why.

All ensuing subsections with a prefix "stage" are essentially grouped work that
will be completed in a new branch. The work branch is allowed to have
build-breaking commits. Also, commits may introduce radical API changes and
possibly even contribute in areas of functionality not limited to the specific
work item. When the group of work is complete, the branch will be merged to
master. The master branch must always build just fine.

## Outline

An item ~~crossed out~~ is complete, an item in __bold__ is work in progress.

[Stage: Project Enhancements](#stage-project-enhancements)  
[~~Stage: HTTP Constants~~](#stage-http-constants)  
[~~Stage: HTTP Versioning~~](#stage-http-versioning)  
[~~Stage: Improved Testing~~](#stage-improved-testing)  
[**Stage: Improved Content Negotiation**](#stage-improved-content-negotiation)  
[~~Stage: Pseudo-Mutable Types~~](#stage-pseudo-mutable-types)  
[~~Stage: Multiple Responses~~](#stage-multiple-responses)  
[~~Stage: Connection Life-Cycle/Management~~](#stage-connection-life-cyclemanagement)  
[Stage: Actions](#stage-actions)  
[Stage: Codings, Part 1/3 (Chunked Transfer)](#stage-codings-part-13-chunked-transfer)  
[Stage: Codings, Part 2/3 (Response Body Compression)](#stage-codings-part-23-response-body-compression)  
[Stage: Codings, Part 3/3 (Request Body Decompression)](#stage-codings-part-33-request-body-decompression)  
[Stage: Multipart Part 1/3 (Consuming "multipart/*")](#stage-multipart-part-13-consuming-"multipart/_")  
[Stage: Multipart Part 2/3 (Consuming "multipart/form-data")](#stage-multipart-part-23-consuming-"multipart/form-data")  
[Stage: Multipart Part 3/3 (Producing "multipart/byteranges")](#stage-multipart-part-33-producing-"multipart/byteranges")  
[Stage: Consuming Form Data](#stage-consuming-form-data)  
[Stage: Improved File Serving](#stage-improved-file-serving)  
[Stage: Cookies](#stage-cookies)  
[Stage: Session Management](#stage-session-management)  
[~~Stage: Timeouts~~](#stage-timeouts)  
[Stage: Logging](#stage-logging)  
[Stage: Misc](#stage-misc)  
[Upcoming](#upcoming)  
[Not Available](#not-available)  
[HTTP Specifications](#http-specifications)

## Stage: Project Enhancements

_Status: **Mostly Delivered**_

To deliver practical usefulness, the project must publish documentation and
artifacts.

- ~~Gradle tasks for JavaDoc generation and publication.
  Ideally we want to be able to generate JavaDoc for public consumtion (public +
  package access) versus docs for contributors (+ private + test classes).~~
- ~~Gradle must use specified JVM version(s) when building~~
- GitHub integrations (test build against many JVM vendors)  
  _Result: Added `build.yml` to build/test from Java 11 to 15_  
  _Remains: GitHub should notify JitPack on successful build?_
- ~~Gradle task to publish a `NoMagicHTTP-0.5-SNAPSHOT.jar` somewhere~~
  - ~~Consider splitting docs into separate jar - may be pushed into future.~~
- User guide (GitHub pages?) for examples and aggregated how-to's  
  _Result: Postponed_
- README.md for super quick introduction followed by project-building how-to's  
  _Result: Postponed_
- CONTRIBUTING.md for tech-heavy contributing how-to's  
  _Result: Postponed_
- DESIGN.md is plausible, linked from CONTRIBUTING.md  
  _Result: Postponed_

## ~~Stage: HTTP Constants~~

_Status: **Delivered**_

Constants - even when not used by the server itself - is important for
_discoverability_.

- ~~Create enums/constants for well-known HTTP methods. JavaDoc has:~~
  - ~~A summary.~~
  - ~~Semantics (safe, idempotent, cacheable, expected payload).~~
  - ~~How and when (if at all) are the methods used by the NoMagicHTTP server.~~
  - ~~If not used, is it expected/planned to become incorporated?~~
  - ~~References to RFC:s - where are the methods defined.~~
- ~~Similarly, enums/constants for well-known:~~
  - ~~Headers~~
  - ~~Status codes~~
  - ~~HTTP versions (named `HttpVersion`)~~

## ~~Stage: HTTP Versioning~~

_Status: **Delivered**_

The server must be in full control of which HTTP version is in use and
`Request.httpVersion()` will reliably expose the version.

- ~~Improved JavaDoc to state supported versions; 1.0 and 1.1, with planned
  support for 2.~~
- ~~Remove `Response.Builder.httpVersion(String)` (confusing for API user to
  have this option).~~
- ~~`Request.httpVersion()` returns a validated `HttpVersion`, perhaps not the
  first request of a protocol upgrade?~~
- ~~Server rejects all requests using HTTP less than 1.0 (with 426 (Upgrade
  Required)).~~
- ~~Add server config to reject HTTP/1.0 clients (false by default, docs
  recommend to enable for connection optimization)~~

~~Notes: Version is case-sensitive and server may respond 505 (HTTP Version Not
Supported) "for any reason, to refuse service of the client's major protocol
version" (RFC 7230 §2.6).~~

## ~~Stage: Improved Testing~~

_Status: **Delivered**_

- ~~Each end-to-end test should run over many combinations of different clients
  and HTTP versions.~~

## Stage: Improved Content Negotiation

_Status: **In Progress**_

- ~~Fix: media type parameter `q` is case insensitive.~~
- Revise `charset` parameter; can potentially be used in combination with `q`
  param to indicate charset preference?
- Revise `Accept-Charset`. Will most likely keep ignoring it. But if so, improve
  docs.

Currently, `NoHandlerFoundException` very broadly translates to 501 (Not
Implemented), which is wrong.

- `RouteRegistry.lookup()` should throw an exception when route exists but the
  method was not accepted, which is translated to 405 (Method Not Allowed).
  Response should have the "Allow: " header set and populated with the route's
  registered methods.
  - If the original request was `OPTIONS`, then the default error handler
    returns a `204 (No Content)` response with the "Allow" header set. May be
    disabled in configuration, `config.autoAllow()`. Handler also populates
    the list of values with "OPTIONS" if not already set.
- 501 should only be used when the server globally rejects a method, for example
  if TRACE is disabled by server configuration.
- Similarly, `Route.lookup()` should also introduce specialized exceptions;
  - one that signals the failure of content negotiation (handler's produced media
    type), translated to 406 (Not Acceptable),
  - and another one that signals no handler consumes the message payload,
    translated to 415 (Unsupported Media Type).

## ~~Stage: Pseudo-Mutable Types~~

_Status: **Delivered**_

In preparation of multiple responses and response-modifying post actions, we
need to open up `Response` for state-changes, except we keep the class
immutable.

End result: Builders's kept, but API footprint vastly reduced. Crucially, added
`Response.toBuilder()`.

- ~~`Response.Builder` is like super smart and keeps a record of changes
  "replayed" when building the response. This behavior we incorporate directly
  into `Response` keeping response immutable and setter methods return a new
  instance.~~
- ~~Replace `Response.builder()` with `Response.create(int statusCode)` and
  `create(int statusCode, String reasonPhrase)`. Note: status code eagerly
  required.~~
- ~~Move static util methods in `Response.Builder` to `Response`.~~
- ~~Anticipate lots of mutations to response so attempt make it more efficient.
  For example, cache `Response.completedStage()`.~~

~~Similarly, do the same for `RequestHandler` and delete `RequestHandlers`.
Client still uses `RequestHandler.Builder`, implicitly, because both method and
logic is required.~~

- ~~`RequestHandler.of("METHOD")` returns `RequestHandler.Builder`, populated
  with method. Builder asks for logic, the "next step". This always return a
  built handler. The application can then invoke consumes/produces to change
  defaults.  
  `RequestHandler.GET().apply(req -> ...).consumes(blabla).produces(blabla)`~~
- ~~Add METHDOS() using method constants from `HttpConstants`.~~
- ~~Go bananas and revise all builder types, perhaps we can apply the same
  pattern elsewhere. Ideally we scrap "builder" methods in favor of factory
  methods returning a builder at worst, or pseudo-mutable type.~~

## ~~Stage: Multiple Responses~~

_Status: **Delivered**_

The most common HTTP response is "final"; 2XX (Success). But final responses may
be preceeded (not obsoleted) by "interim" responses of class 1XX (Informational)
(since HTTP/1.1). This is sort of a server-side event mechanism for keeping the
client updated while processing lengthy requests.

End result: Introduced a `ClientChannel` which request handlers write to (any
number of responses) and a `ResponsePipeline` in the back-end.

### ~~API~~

- ~~Add `Response.thenRespond(CompletonStage<Response> next)`.  
  Schedules a subsequent response.~~
  - ~~If the first response is not 1XX (Informational), throw
    `UnsupportedOperationException`.~~
  - ~~Also throws an exception if HTTP version is < 1.1.~~
  - ~~For simplicity, client can use any reference in the chain to keep adding
    responses? Sort of like an "addLast" method. Not sure I like this.~~
  - ~~Add overload which accepts an unboxed/ready `Response`.~~
- ~~`Response.body()` throws exception if response is interim.~~
- ~~Add factory methods for `100 (Continue)` and `102 (Processing)`.~~

### ~~Server~~

- ~~"a server MUST NOT send a 1xx response to an HTTP/1.0 client" (RFC 7231 §6.2).  
  Can only fail just before writing response.~~

~~Multiple responses changes the `HttpExchange` life-cycle; interim responses
doesn't finish the exchange.~~

- ~~While response is interim; server pulls `Response.next()` which returns
  `CompletionStage<Response>`.~~
- ~~`Response.next()` throws `NoSuchElementException` if it is final.  
  Unnecessary noise to add "hasNext()" method.~~

~~Client may announce a pause before sending the request body.~~

- ~~Add `HttpServer.Config.autoContinueExpect100()`~~
  - ~~`false` by default. Meaning that by default, application code will have an
    opportunity to engage with a client sending a "Expect: 100-continue"
    request. If the application code doesn't explicitly respond a 100 (Continue)
    message to the client, then the server will automagically send the
    continue-reply as soon as the application access the request body. This
    ought to translate to virtually no pause at all for a majority of all cases
    and still leave the application in control.~~
  - ~~If set to `true`, the server will immediately respond to the continue-reply
    with no delay, effectively disabling the mechanism. It's interesting to note
    that this is how most other frameworks/libraries I have looked at behaves,
    and they don't even make it configurable, effectively killing the whole
    mechanism leaving applications that rely on it very surprised. Of course, to
    find that out, you'll have to look at the source code as the documentation
    says zilch about it. I guess that's the only thing that doesn't surprise me.~~
  - ~~Regardless of who initiates the 100 (Continue) response, it can safely be
    skipped by the server if when initiating the write-operation, the request
    body has already begun transmitting.~~

## ~~Stage: Connection Life-Cycle/Management~~

_Status: **Delivered**_

Goal is to make the connection life-cycle more solid and specified with docs. In
particular, docs should clarify when do the connection close and under what
circumstances. Future multiplexing in HTTP/2 will most likely abstract a
connection into one or many _channels_.

End result: Lots of the items listed here belongs to future work, specifically
chunked encoding and HTTP/2. The connection life-cycle is greatly improved,
however. Can not default to `Content-Length: 0` on empty body. Lots of response
variants have no body, and also no content-length. `Responses` will create valid
responses. `ResponsePipeline` will handle unknown length by setting
`Connection: close`. HTTP/1.0 keep-alive will likely never be implemented.

- ~~Default `Content-Length: 0` if no response body is set (RFC 7231 §3.3.2).  
  Will later be moved to action.~~
- ~~Auto-close connection after final response if no `Transfer-Encoding` nor
  `Content-Length` have been set, or if `Transfer-Encoding` is set but `chunked`
  is not the last token.  
  Should *not* be implemented as a post-action as this is a protocol- and
  therefor server-specific detail.~~
- ~~Unroll theoretically possible recursion in `HttpExchange`.~~
- ~~New exchange does not begin if `Connection: close`~~
- ~~Add `Response.mustCloseAfterWrite(boolean)` and rename to
  `thenCloseChannel`.~~
  - ~~Will close even if response is interim (future warning log may take
    place).~~
- ~~Graceful close~~
- ~~Add `request.channel().close/kill()` (graceful or hard).  
  Will be used in the future after a rewrite of `thenCloseChannel`.~~
- ~~Perhaps~~
  - ~~Split `HttpExchange` into different types depending on HTTP version.  
    May remove the need for some "if-version" checks in code?~~
- ~~Implement persistent connections (`Connection: keep-alive`) also for
  HTTP/1.0 clients.~~

## Stage: Actions

Pre- and post request handler actions which are able to intercept- and modify
the HTTP exchange. Naming not defined, either "filter", "action", "aspect" or
something to that effect. "Filter" - although common in other projects - is also
misleading. A pre action may have no opinion on whether or not a request is
routed through and it would probably be outright bizarre to have a post action
reject a prepared response.

Useful because

- Pre-actions can more cleanly address cross-cutting concerns, such as rate
  limiting and authentication.
- Post-actions are anticipated to be mostly used by the server itself, for
  example to apply response compression.

Semantically, actions co-exist with routes in a shared hierarchical namespace;
makes it easy to implement spaces/scopes (RFC 7235 §2.2, RFC 7617 §2.2). For
example, a pre action doing authentication can be scoped to "/admin".

### Usage

- Docs must specify
  - Semantics, intended usage.
  - When are they executed, under what conditions.
  - Execution order; especially the order between server's actions and the
    application's actions.
  - What actions are added by the server.
- Consider API `HttpServer.before/after("/my/path", myAction)`.  
  Implementation-wise, actions will most likely be stored in two distinct and
  separeted registries/trees; not co-exist in the same tree as routes.
- Both action types have a boolean metadata; indicating if the action is
  exclusive for the node or also applies recursively for the rest of the branch.
- In addition, pre action type has a second boolean indicating if it should be
  called pre- or post request handler resolution. Or in other words; whether or
  not the action is dependent on an actually matched resource.
  Example; `HttpServer.before("/", MyBeforeAction.create(true, true))` will
  always be called for all successfully parsed requests hitting the server.
- Post actions will always run just before a response is sent back to the client
  whatever happened before that point in time.
  - and so technically should probably not be called "post request handler",
    because there might never have been a request handler to start with.
  - Will not be called for responses not sent, for example a skipped 100
    (Continue).
  - They execute _after_ error handling.

### Signature

- **Pre action's logic** function has the same signature as
  `RequestHandler.logic()`, i.e. `Function<Request, CompletionStage<Response>>`.
  When implementing, same rules apply, in particular;
  - request arg is immutable and
  - body bytes can not be consumed more than once.
- The pre-action is expected to make heavy use of attributes, for example
  `req.attributes().set("app.user.authenticated", true)`.
- Action returns `null` if exchange should proceed (possibly more actions called
  followed by request handler), or alternatively
- a non-null `CompletionStage<Response>` which shortcuts the HTTP exchange and
  preempts the rest of the call chain.
  - No support for split work such as returning an interim response from the
    action and then delegate the exchange to the rest of the call chain.
  - Still legal for the action to respond an interim response, but action must
    also make sure to finalize that response. Docs must be clear on this.
- May return exceptionally, subject to standard error handling.

  .

- **Post action's logic** is a
  `BiFunction<Request, Response, CompletionStage<Response>>`.
- Post action must not return `null`. Returning null implicitly has the same
  effect as throwing NPE from within the action itself.
- In order to apply no effect, the action must return
  `secondArg.completedStage()`.
- Action can template a new response given the provided argument or build a
  completely new one from scratch.
- Action should never return exceptionally.
  - Action's run _after_ error handling; there's semantically speaking no error
    handling infrastructure in place for post actions. Post actions _are_ the
    last pieces of code which may impose an opinion on the response.
  - Rolling back the exchange progress to error handling would risk putting the
    request thread in an infinite loop if the same post action repeatedly keeps
    throwing the same exception.
  - If the post action does throw an exception (or return `null`), the server
    is hardwired to call the default exception handler which logs the exception
    and most likely returns a 500 (Internal Server Error) - no other
    post-actions called!

### Library-native actions

- DefaultServer should already default `Content-Length` to 0 for no response
  body. Move to post action.
- Add pre- and post-actions that rejects illegal message variants (see
  HttpServer JavaDoc).
- Rewrite `Response.thenCloseChannel()` to set header `Connection: close`.  
  Add post action which reacts to the header and calls
  `Request.channel().close()`.
- If not already set, automagically set `Vary: Accept` (?)
  - If there are multiple request handlers for that resource which produces
    different media types, and
  - status code is 200 or 304

## Stage: Codings, Part 1/3 (Chunked Transfer)

Chunking is a HTTP/1.1-specific protocol technique for streaming a message body
when the `Content-Length` is unknown. It also enables trailing headers _after_
the message body; good for lazily sending hashes based on transmitted content.
Future work may specifically deal with message integrity.

Chunked decoding and encoding is transparent to the application code. Inbound
`Transfer-Encoding` (e.g. "gzip, chunked") is fully decoded by the server and
there's no option to disable it. Similarly, outbound chunked transfer encoding
is applied by the server only if necessary.

### Request Dechunking

- Likely implemented using a concrete `AbstractOp` (semantically a
  `Flow.Processor`) installed in `DefaultRequest` or somewhere else.
- Request JavaDoc updated with a section on dechunking; advising against its
  use.
  - If trailers are not needed and length is already known, then chunking is
    just unnecessary overhead.
  - Server will be forced to move all body bytes through Java heap space and
    not be able to benefit from direct bytebuffers.
- Add `Request.trailers()` returning `CompletionStage<HttpHeaders>`.  
  If chunked encoding isn't in use, then the returned stage will already be
  completed with empty `HttpHeaders`, otherwise it will complete whenever the
  body and trailing headers have been received.  
  Implementation-wise, we prolly want to split RequestHead- subscriber+processor
  into RequestLine and Headers (perhaps then combined into RequestHead). After
  the split, trailing headers logic can simply resubscribe yet another Headers
  subscriber+processor.

### Response Chunking

Performed through a server-added post action decorating the body. Each published
bytebuffer = one chunk.

- Add `Responses.bytes(Flow.Publisher<ByteBuffer> bytes)` and overload
  `bytes(bytes, contentLength)`.  
  As with Request, JavaDoc explains chunked encoding and how providing the
  length is always prefered.
- Consider adding other "streaming" methods such as
  `text(Flow.Publisher<String> chunks)`.
- Add `Response.trailers()` returning `CompletableFuture<HttpHeaders>`.  
  The application uses the returned future to set the trailing headers whenever
  they are ready. The method enables the application to use trailing headers for
  "any message".
  - Using response trailers will switch to chunked encoding and the application
    _must_ complete the returned future at some point.
  - Returns same instance on repeated access.
  - Returns exceptionally if HTTP version != 1.1.  
    (not sure yet if HTTP/2 _fully_ obsoletes chunking?)
  - JavaDoc explains
    - Method must be polled _before_ Response is handed over to the server,
      otherwise `IllegalStateException` (can't apply transfer encoding if
      response already started).
    - The application should set the `Trailer` header to give a head's up to
      the client which headers exactly are trailing the message. This would be
      impossible for the server to know in advance.
    - Trailers should only be used if `TE: trailers` has been set in the request
      as otherwise the client or an HTTP intermediary (e.g. HTTP 1.0 client
      proxy) may discard the trailers (RFC 7230 §4.3).

Implementation-wise, `Response.trailers()` must be documented and implemented to
set temporary `X-Temp-???` headers which instructs the post action to apply
chunked encoding. Or, we add `Response.isUsingTrailers()`, or we set
`Request.attributes()`. But in some way or the other the post action must be
able to figure this out without pulling trailers() as this would always return
an instance regardless of application's intended use.

Armed with this capability, the post action applies chunked encoding only if:

- Request method was not `HEAD` (body not expected) and
- HTTP version == 1.1, and at least one of the following is true:
  - Trailing headers for the response have been initiated or
  - `Content-Length` is not set or
  - `Transfer-Encoding` compression has been applied (see next section)

The post action will also _remove_ `Content-Length` if it was set. The
specification allows for both `Transfer-Encoding` and `Content-Length` to be
present - the former overriding the latter - but the spec also recommendeds the
client to treat this as an "error" (RFC 7230 §3.3.3). Hmm.

## Stage: Codings, Part 2/3 (Response Body Compression)

Server-side response compression using `Transfer-Encoding` (hop-by-hop, since
HTTP/1.1) or `Content-Encoding` (end-to-end).

"On the fly" compression should only be done in `Transfer-Encoding` and should
then be followed by chunked encoding in order to save the connection (RFC 7230
§3.3.3). Alas, because the state of the world is pretty dire, the API will also
support compression using `Content-Encoding` (see
[StackOverflow question](https://stackoverflow.com/questions/11641923/transfer-encoding-gzip-vs-content-encoding-gzip)).

- Add `Response.compressTransfer(codecConfig)` (Transfer-Encoding)
  - Returns exceptionally if HTTP version is < 1.1.
- Add `Response.compressContent(codecConfig)` (Content-Encoding).
  - Returns exceptionally if a strong ETag value has already been set (RFC 7232
    §2.1).

The codec config may be produced using an API of sorts, e.g.
`StandardCodecs.GZIP.level(6)`.

Both compressXXX() methods represent a _command_ which does not check the
request's `TE` or `Accept-Encoding` headers; future logging improvements _may_
log a warning if the request headers are violated. These commands are intended
for applications in control of both the client and server who are also not
so much bothered with nuances of message semantics.

- Add `tryCompressTransfer` and `tryCompressContent` respectively.

The tryCompressXXX() methods does investigate request headers to determine which
codec - if anyone - is suitable to use. A boolean return value tells if the
operation was successful.

Neither flavor of methods will compress an already compressed body; an attempt
to do so throws an exception.

Internally, the methods doesn't actually compress the body but will schedule -
through means of temporary `X-Temp-???` header(s) - the work do be performed by
a server-added post action. This has the advantage that order and timing of
method invocations doesn't matter (e.g. body may be set after compression has
been signalled). The compressor action must run _before_ the chunking encoder
action.

_Auto-compression_ will also be implemented as a post action. This action
executes before the actual compression action. The auto-compress action will
simply automate the selection and invocation of a tryCompressXXX() method.

- Add `Config.autoCompress()`.  
  The value is a codec config to apply; feature disabled if `null`.  

The auto-compressor will only have an effect if all of the following conditions
are true:

- Content isn't already compressed.
- CPU load is within a certain threshold (don't overload/wait on CPU), < 80%?
- Only for long text, 4k+? - no other content type is compressed.

If the HTTP protocol version in use is 1.1+, then the preferred method will be
`tryCompressTransfer()`, falling back to `tryCompressContent()`, but only if
there's no strong ETag set.

Most likely, no aspects of the auto-compression strategy other than the codec
will be configurable. Simply because the application has a pretty straight
forward way to switch it out completely. Simply set `autoCompress()` to `null`
and add a custom post action which can then delegate to the response's
compression methods for the actual work or take over encoding ownership
entirely. The server's post action should be public and designed for
inheritance. This also suggest that the order must be defined such that server's
post actions run after application's post actions.

## Stage: Codings, Part 3/3 (Request Body Decompression)

The standard way of accessing body bytes (`Request.body()`) should return an API
for working with decoded/decompressed data. But API should also cater for users
that wish to access the bytes before decoding. For example, perhaps the
application wish to save pre-decoded bytes straight to a file instead of having
the server first decompress the data. Server must also fail decoding if it the
codec used is unrecognized, hence the application must have the ability to
access pre-decoded bytes to perform the decoding manually.

- Collect `Request.Body.convert()/subscribe()/toFile()` in new interface
  `Payload`.
  Payload does not define if data is decoded or not, this is done by subtype.
- Let `Request.body()` return new type `DecodedPayload` which extends/implements
  `Payload`, and delete `Request.Body`.  
  The contract of DecodedPayload states that encoded bytes are decoded. The
  interface works just fine for non-encoded bytes as well; there's no
  requirement that the bytes _must_ be encoded.  
  DecodedPayload adds utility methods such as toText().
  All methods of DecodedPayload returns exceptionally if the bytes were encoded
  using an unrecognized codec.  
- Improve DecodedPayload.toText()
  - must crash for all media types not explicitly set to "text/*".  
    Respond 415 (Unsupported Media Type).
  - overload boolean `force` may be set to skip content type check
  - overload charset may also be set, perhaps in combination with force
- Provide header utility method for `Content-Disposition`, returning a
  `ContentDisposition`.  
  Document in toFile() that this utility method may be used to extract the file
  name.
- Add `DecodedPayload.raw()` which returns an new interface `RawPayload` which
  also extends Payload.  
  RawPayload is documented to return the payload bytes after decoding
  `Transfer-Encoding` (user has no choice there) but before even attempting to
  decode `Content-Encoding`.  
  RawPayload is an empty marker interface; it inherits the byte-serving methods
  from Payload but does not declare toText() or anything else.

## Stage: Multipart Part 1/3 (Consuming "multipart/*")

Add support for consuming "Content-Type: multipart/*" requests.

Multipart is essentially a syntax where a boundary makes it possible to embedd
many distinct messages (or, "representations") within the request body, each
with its own header section.

Just as DecodedPayload has a `toText()` method, we should add a `multipart()`
method. Example client consumption:

    req.body().multipart().subscribe(new MyPartSubscriber());

This API can be used to consume any multipart subtype. Future work will make the
library provide specialized APIs for well-known subtypes. The specialized APIs
will most likely be built on top of the generic API with hardwired assumptions
pertaining to the contents of each part.

- Collect `Request.headers()/body()` to new interface `Message` which Request
  extends.
- Add `DecodedPayload.multipart()` (lowercase) which returns new type
  `MultiPart`,  
  crashes if content type isn't "multipart/*".  
  MultiPart extends `Flow.Publisher<MultiPart.Part>`.
- Add `MultiPart.Part` which extends Message, i.e. has headers and a decoded
  body - pretty slick!  
  Technically speaking, `part.body().multipart()` is legal but a bit bizarre.

## Stage: Multipart Part 2/3 (Consuming "multipart/form-data")

The "form-data" subtype is probably the most popular subtype, used a lot by
browsers to send HTML form data, especially when the transmission involves
binary data.

Example client consumption:

    req.body().multipart().formdata().subscribe(new MyFormPartSubscriber());

Each form-data part can contain any type of content and the application code
must chose how to consume each part; toText()/toFile()/whatever. The new API
additions simply makes that job a tiny bit easier (documentation will most
likely come to play a key role here with the greatest end-user value).

- Add `MultiPart.formdata()` which returns new type `MultiPart.FormData`,  
  crashes if content type isn't "multipart/form-data".
- `MultiPart.FormData` extends/implements `Flow.Publisher<MultiPart.FormPart>`.
- MultiPart.FormPart semantically extends `MultiPart.Part` with some added sugar:
  - The FormPart does not have a decoded body, only a raw body
    (see [StackOverflow question](https://stackoverflow.com/questions/47847491/gzip-content-encoding-with-multipart-form-data/66118265#66118265))  
    Either FormPart can not extend Part or Part is made generic in terms of what
    payload it carries (decoded or raw).
  - The server will set `Content-Type` to "text/plain" if not already provided.
  - The charset parameter may also be set following the "_charset_" trick
    outlined in RFC 7578 §4.6.
- Add `MultiPart.FormPart.contentDisposition()` which will always return the
  required Content-Diposition header in each part.

## Stage: Multipart Part 3/3 (Producing "multipart/byteranges")

"multipart/byteranges" is primarily used for serving parts of a file aka.
resumed/segmented download. Application's routes are expected to announce the
range-serving feature of a resource by setting the `Accept-Ranges: bytes` header
in the first response.

- Add new type `Range` extends `Flow.Publisher<ByteBuffer>`.  
  One part, or range, can be delivered by one or many bytebuffers.
- Add `Range.firstBytePos()` which returns the first byte position in the range.
- Add `Range.lastBytePos()` which returns the last byte position in the range.  
  Both positions needs to be given. The starting offset can not possibly be
  known by the server nor can it know the amount of bytes given how one range
  may be satisifed by many bytebuffers.
- Add static `Range.fromFile(long firstBytePos, long lastBytePos, Path file)`.  
  Probably piggybacks on recently added utility method(s) for reading files in
  the `Publishers` type.
- Add new factory `Responses.byteranges(String/MediaType contentType,
  long contentLength, long completeLength, Iterable<? extends Range> ranges)`.  
  The method will create a 206 (Partial Content) response with all parts
  delimited by a generated boundary.  
  _contentType_ = the Content-Type header value repeated for each part. The
  response header's Content-Type will be set to "multipart/byteranges;
  boundary=\<generated boundary>".  
  _contentLength_ = length of message. Consider also adding overload which
  doesn't use length and switches to chunking instead.  
  _ranges_ = all message parts.

## Stage: Consuming Form Data

Support decoding `application/x-www-form-urlencoded`. This is the default HTML
form `enctype` which essentially takes a giant query string and puts it in the
message body instead. Also see JavaDoc of `Request.Parameters`.

Since there's no library support for `application/json`, I don't really like
having to implement this encoding. But, it is the default type for HTML forms,
so there's pretty much no choice here.

Implementation-wise, add yet another decoding method to `DecodedPayload`:

    Map<String, String> data = req.body().formdata(); // URL decoded

Of course, JavaDoc must explain througouhly what the difference is between the
former and `req.body().multipart().formdata()`. Also update Request.Parameters
JavaDoc.

Note: adding a method for "raw" form data is probably just noise. Recent work
has made it super simple to force-convert a body to text or access the raw
bytes.

## Stage: Improved File Serving

Recent work should have added utility methods for producing byte publisher(s)
out of a file and serving byteranges. The missing piece of the API is how to
automate these responses, which is the purpose of the work outlined here.

- Add `Responses.file(Path)` with boolean overload `acceptByteRanges`,  
  creates a complete Response out of a file.  
  The given boolean translates to a an `Accept-Ranges` header set with value
  "bytes" or "none".  
  `Content-Type` is probed using `Files.probeContentType()`.
  Possibly also set other headers such as `Content-Disposition` and
  `Last-Modified`.  
  `ETag` and `Cache-Control` are semantics best left to the request handler or
  any other form of post-processor.
- Add `RequestHandlers.file(Function<Request, Path> path)` with overload
  accepting some form of a `FileServeConfig` object.  
  The function will provide the file path to serve, for example by reading
  dynamic path segments from the request:  
          Function<Request, Path> f = req -> Paths.of("/server-root", req.parameters().path("user"), "files", req.parameters("filepath"));
          server.add("/:user/files/*filepath", file(f));  
  Given how the path is provided by a function, it is completely up to
  application code how to navigate to the file and which files it wishes to
  serve. Super simple and super powerful. No need for complex pattern matching-
  and/or filter arguments.  
  The handler
  - crashes the request if file path is not a readable file.
  - may likely wrap `IOException` in `UncheckedIOException` - subject to
    unwrapping in error handling?
  - must be able to serve byte ranges.
- `FileServeConfig` contains configuration for serving pre-compressed files,
  generating ETag validator, evaluating conditional requests and produce
  Cache-Control directives.  
  - By default, config.`compressedSiblings` = [".br", ".gzip" ... ?] which
    defines an ordered list of sibling file extensions to prioritize. E.g. a
    request for "file.txt" may result in server instead sending "file.txt.br"
    with "Content-Type: text/plain;charset=utf-8" and "Content-Encoding: br"
    (Brotli), assuming this coding is accepted by the client and the
    pre-compressed alternative file exist.
  - By default, config.`etag` is a `Function<Path, String>` that eagerly
    generates (before response have been sent) an NGINX-styled ETag value
    akin to "hex(file_mod_time)-hex(file_size)". Consider also forced second
    precision on mod time and if request Date is within the second; mark ETag
    weak ("W/").
  - By default, config.`notModified` is a `BiPredicate<Request, Path>` that
    evaluates conditional requests (RFC 7232) and may pre-empt the file load
    responding a 304 (Not Modified) instead. The predicate may access the ETag
    function directly to generate the ETag value for comparison. The ETag
    function in turn may cache the result for the duration of the request.
  - By default, config.`cacheControl` is a `Function<Path, String>` that
    returns `null`, i.e., no `Cache-Control`. Application can provide a function
    called for each file to compute the cache directive. JavaDoc exemplifies how
    to set a directive for immutable files; max-age=1-year and token immutable.
    These files should basically never be reloaded by the client again. I think
    that ideally, we'd like direct API support for this as well; `.immutable()`?

The default NGINX-styled ETag strategy was chosen in short because it is fast to
compute, widely spread and sort of a standard given how NGINX is the most used
file server. Using the same strategy makes interoperability across HTTP server
vendors or even interoperability between different load balanced instances
_less_ of a concern. Interoperability is a concern though (because of the file
modification time input) and the best ETag value is always computed using a hash
over the entire file contents.

JavaDoc should emphasize that the ETag function is called eagerly and the
default condition evaluation function depend on the ETag function. I.e., an
application that wishes to modify the ETag for a more computationally expensive
hash ought to replace both functions, for example by setting the ETag function
to null and then generate the ETag lazily when the body publisher completes. The
lazy value can then also be appended to the client as a trailing header.

Having said that, caching the ETag hash alongside the file and _atomicically_
cascade modifications across both files on top of an abstract Java machine is
not even possible. A better alternative would probably be to go straight for a
revision control system instead where each change results in a unique revision
identifier.

Note: Directory serving doesn't need explicit API support. The path-function may
navigate the file system however it wants. Alas, lots of other frameworks and
libraries don't have that flexibility. The standard API design out there is
directory-centric with a whole slew of complex overloads and method arguments
for how to select and filter files within a given directory. Consequently, a new
user to the NoMagicHTTP library not finding a "directory" method may actually
end up being confused. JavaDoc must therefore provide lots of examples including
the most basic version of a "directory serving" function; keyword _directory_
especially emphasized.

## Stage: Cookies

Client-side storage through `Cookie` and `Set-Cookie` headers. API to be
defined, but most likely cookie will be retrievable from Request headers and
settable on Response headers.

- Perhaps reuse `java.net.HttpCookie` with modification (allows the '='
  character in cookie name).
- Contemplate API support for
  - session/persistent cookies (matter of expiration)
  - special flag cookies; secure, HTTP Only and Google's same-site

## Stage: Session Management

API to be defined. Essentially only a matter of server-side storage mapped by a
session ID/key saved in cookie.

- API should support clustering/replication, most likely done by abstracting the
  backing store.
- Research but probably do not use less secure fallbacks such as storing session
  id in request path/query if client disabled cookies.

## ~~Stage: Timeouts~~

_Status: **Delivered**_

End result: Added `HttpServer.Config.timeoutIdleConnection()`,
`RequestHeadTimeoutException`, `RequestBodyTimeoutException`, and
`ResponseTimeoutException`.

~~Most timeouts should probably result in a 408 (Request Timeout).~~

~~For many other servers, timeouts are often quite short, specifically designed
that way to save server threads. Fotunately, NoMagicHTTP is fully asynchronous
so even if a request/exchange "hangs" and doesn't make any progress, no thread
is blocked. But it's still not good for a million reasons to have idle
connections and loaded buffers sitting around doing nothing. The upside is that
the timeout configuration can be quite lenient and forgiving.~~

- ~~Max time spent receiving request head- and body respectively.
  Currently, send a request that doesn't end with `CRLF` + `CRLF` and the
  exchange "hangs" indefinetely.~~
- ~~Same for response.  
  Default for receiving/writing head should be low.  
  Default for receiving/writing body should be super high.~~
- ~~Max _idle_ time for receiving/writing request/response.  
  No need to split timers for head and body.~~

## Stage: Logging

- Configuration to enable low-level logging of requests and responses.  
  Meaning we probably have to have new utils for decorating both.  
  Docs should warn for interleaving and propose this option only for
  development.
- Configurable WARNING if  
  - HTTP/1.0 client connects, default false.
  - Outbound response has a body but no Content-Type, default true.
- Add JavaDoc.
- Add user guide on how to plug in an ELK stack.

## Stage: Misc

- CORS
- Configuration to add `Server:` header to responses.
- Server should set default `application/octet-stream` for missing
  `Content-Type` in request.  
  - Code that check for Content-Type can then be rewritten to assume it has been
  given.
- Add config to disable TRACE, default false.
- Perhaps already implemented at this point, but if not, support for the `Date`
  header.
- Programmatically schedule/enable caching of the request body, for example:  
  `DecodedPayload self = req.body().cache(int maxSize);`  
  Useful if a pre action wants to inspect the body and still leave it consumable
  by a request handler, or a request handler would like to leave the body
  consumable for an error handler, and so on.
- Provide localization examples
  - Endpoint for setting language in session (`PUT /settings?lang=en`)
  - Dynamic segment (`https://en.example.com/page`,
    `https://www.example.com/us_EN/page`)
- Add a public pre action "auto head" that if the request is HEAD, rewrittes the
  request to a GET and subsequently, the server never subscribes to the response
  body. Could be very useful, but then, the pre action would need API to
  re-write an immutable request, and a method to signal to the server that he
  should ignore the response body! So, perhaps complicated to implement.  
  The alternative is to add `Config.autoHead()` with all that behavior baked in.
  Alas this config would be global and application would not be able to scope it
  to a particular resource namespace.
- Research
  - Controlled request queues to enable fair use.
- Improved security
  - Cross-Site Scripting (XSS) (see `HeaderKey.CONTENT_SECURITY_POLICY`)
  - Cross-Site Tracing (XST)
  - Cross-Site Request Forgery (CSRF)
  - Detection of denial-of-service attacks.
- Put a mechanism in place to unblock an indefinetely blocked request thread.  
  Subject to configuration.  
  Idle connection times out after 90 sec, the unblock should happen shortly
  after?  
  Log warning, then attempt unblock by interrupt?
- Mechanism to not accept more connections when server already "too busy"
  serving requests.

## Upcoming

It is anticipated that, once all of the above is completed (the list is subject
to change, of course), the project will get its first tagged version `0.5`. At
this point, the API ought to be semi-stable. Certainly, the most critical bits
of HTTP has then been delivered.

What should be introduced with 0.5 is a changelog as well as more planning -
perhaps in the form of real GitHub milestones - leading up to version `1.0`.
Anticipated to be included in the first final release:

- Revise
  - HTTP Authentication (we'll likely only support the Basic scheme)
  - Caching
  - Message integrity (`Digest` and `Want-Digest` headers)
- Server-Sent Events
- WebSocket
- HTTPS/TLS
- HTTP/2

HTTP/2 is included in the 1.0 release because version 2 of the protocol doesn't
change any semantics and ought to be easy to implement compared with the work
that has already gone into the project. It's essentially only a matter of binary
message framing. HTTP/3 will likely be the next project milestone after version
1.0.

## Not Available

It's important to not just document what a library can do, but also what it can
not do. The following list details what hasn't- and never will be implemented
(needs to go to JavaDoc pretty much as soon as possible):

- Magical format conversions from POJOs to JSON/YAML/Whatever.  
  To each his own.
- HTTP/1.1 Pipelining.  
  Pipelining is problematic, therefore not used by many browsers, and
  superseeded by multiplexing in HTTP/2, and HTTP/2 is gaining rapid adoption.  
  So, implementing pipelining is pretty much a waste of time.  
  Note that pipelining is optional and not having it doesn't break clients (RFC
  7230 §6.3.2).
- HTTP Digest Authentication.  
  Not very useful. Use Basic over HTTPS instead.
- Producing generic "multipart/*" responses.  
  Not sure how relevant this is.

Not a completely sacked idea, but postponed indefinitely:

- Consuming "multipart/byteranges"  
  Most applications use their own custom scheme for "resumed upload".  
  If there's ever a demand for it, we'll prolly do something like
  [this](https://stackoverflow.com/a/56641911/1268003) and
  [this](https://tools.ietf.org/id/draft-wright-http-partial-upload-01.html) or
  [this](https://web.archive.org/web/20151013212135/http://code.google.com/p/gears/wiki/ResumableHttpRequestsProposal).
- Producing `103 (Early Hints)`.

Finally - for the record - most items above _can_ be done with the NoMagicHTTP
library. It's just that the particular feature has no first-class API support
and the application would have to implement the functionality itself.

## HTTP Specifications

For reference.

HTTP/1.0

- [RFC 1945][rfc-1945] - HTTP/1.0  
  Not really useful other than as a historical reference.

HTTP/1.1

- [RFC 7230][rfc-7230] - Message Syntax and Routing  
  Core protocol and server requirements.
- [RFC 7231][rfc-7231] - Semantics and Content  
  Sugar on top of the protocol; methods, status codes, this and that.
- [RFC 5789][rfc-5789] - PATCH Method for HTTP
- [RFC 7232][rfc-7232] - Conditional Requests
- [RFC 2046 §5.1][rfc-2046] - Multipart Media Type
- [RFC 7233][rfc-7233] - Range Requests
- [RFC 7578][rfc-7578] - Returning Values from Forms
- [RFC 6266][rfc-6266] - Content-Disposition Header Field
- [RFC 7235][rfc-7235] - Authentication
- [RFC 7617][rfc-7617] - The 'Basic' Authentication Scheme
- [RFC 7616][rfc-7616] - Digest Access Authentication
- [RFC 7615][rfc-7615] - Authentication-Info and Proxy-Authentication-Info
- [RFC 6265][rfc-6265] - HTTP State Management Mechanism  
  Cookies. They meant to say Cookies.
- [RFC 7234][rfc-7234]: Caching

HTTP/2

- [RFC 7540][rfc-7540] - HTTP/2

HTTP/0.9 was never standardized.

[rfc-1945]: https://tools.ietf.org/html/rfc1945
[rfc-7230]: https://tools.ietf.org/html/rfc7230
[rfc-7231]: https://tools.ietf.org/html/rfc7231
[rfc-5789]: https://tools.ietf.org/html/rfc5789
[rfc-7232]: https://tools.ietf.org/html/rfc7232
[rfc-2046]: https://tools.ietf.org/html/rfc2046#section-5.1
[rfc-7233]: https://tools.ietf.org/html/rfc7233
[rfc-7578]: https://tools.ietf.org/html/rfc7578
[rfc-6266]: https://tools.ietf.org/html/rfc6266
[rfc-7235]: https://tools.ietf.org/html/rfc7235
[rfc-7617]: https://tools.ietf.org/html/rfc7617
[rfc-7616]: https://tools.ietf.org/html/rfc7616
[rfc-7615]: https://tools.ietf.org/html/rfc7615
[rfc-6265]: https://tools.ietf.org/html/rfc6265
[rfc-7234]: https://tools.ietf.org/html/rfc7234
[rfc-7540]: https://tools.ietf.org/html/rfc7540
