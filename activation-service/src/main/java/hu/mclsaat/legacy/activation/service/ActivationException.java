package hu.mclsaat.legacy.activation.service;

/** The failure shapes subsystem 2 reports, mapped to HTTP statuses by the REST advice. */
public abstract class ActivationException extends RuntimeException {

    private final String code;

    protected ActivationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** HTTP 404. */
    public static class NotFound extends ActivationException {
        public NotFound(String what, String key) {
            super("NOT_FOUND", what + " not found: " + key);
        }
    }

    /** HTTP 400. */
    public static class BadRequest extends ActivationException {
        public BadRequest(String message) {
            super("BAD_REQUEST", message);
        }
    }

    /** HTTP 409: the order exists but is not in a state where this makes sense. */
    public static class IllegalState extends ActivationException {
        public IllegalState(String message) {
            super("ILLEGAL_STATE", message);
        }
    }
}
