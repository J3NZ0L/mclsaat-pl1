package hu.mclsaat.legacy.activation.client;

/**
 * A call to the catalog failed.
 *
 * <p>{@code retryable} decides whether Flowable's async executor should be allowed to try again.
 * A 4xx from the catalog means the order is wrong and retrying will not help; a 5xx or a connection
 * failure means the catalog is having a bad minute and retrying is exactly right.
 */
public class CatalogClientException extends RuntimeException {

    private final boolean retryable;

    public CatalogClientException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
