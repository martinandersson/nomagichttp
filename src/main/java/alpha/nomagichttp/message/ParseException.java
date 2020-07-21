package alpha.nomagichttp.message;

// TODO: Rename to RequestHeadParseException (should be treated as 400 Bad Request)
//       We will likely also need a BadRequestException for when parsing completes
//       in an acceptable fashion but is semantically not correct.
public final class ParseException extends RuntimeException {
    private final char prev, curr;
    
    private final int pos;
    
    public ParseException(String message, char prev, char curr, int pos) {
        super(message);
        this.prev = prev;
        this.curr = curr;
        this.pos  = pos;
    }
    
    @Override
    public String toString() {
        return ParseException.class.getSimpleName() + '{' + String.join(", ",
                "prev=" + Char.toDebugString(prev),
                "curr=" + Char.toDebugString(curr),
                "pos=" + pos,
                "msg=" + getMessage()) + '}';
    }
}