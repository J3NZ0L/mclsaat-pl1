package hu.mclsaat.legacy.catalog.service;

/** Base type for the two failure shapes this subsystem reports: not found, and illegal state. */
public abstract class CatalogException extends RuntimeException {

    private final String code;

    protected CatalogException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** Referenced row does not exist. Mapped to HTTP 404. */
    public static class NotFound extends CatalogException {
        public NotFound(String what, String key) {
            super("NOT_FOUND", what + " not found: " + key);
        }
    }

    /** The row exists but its current state forbids the requested transition. Mapped to HTTP 409. */
    public static class IllegalTransition extends CatalogException {
        public IllegalTransition(String message) {
            super("ILLEGAL_TRANSITION", message);
        }
    }

    /** The request itself is inconsistent. Mapped to HTTP 400. */
    public static class BadRequest extends CatalogException {
        public BadRequest(String message) {
            super("BAD_REQUEST", message);
        }
    }
}
