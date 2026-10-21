/** Thrown when client-supplied data is invalid. Maps to HTTP 400. */
public class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}
