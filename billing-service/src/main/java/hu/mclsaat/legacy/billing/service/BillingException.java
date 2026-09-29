package hu.mclsaat.legacy.billing.service;

import org.springframework.ws.soap.server.endpoint.annotation.FaultCode;
import org.springframework.ws.soap.server.endpoint.annotation.SoapFault;

/**
 * Billing reports errors as SOAP faults, which is how the rest of the enterprise expects to
 * hear about them. The REST subsystems report the same conditions as JSON bodies with an HTTP
 * status - another difference a caller has to absorb.
 */
public abstract class BillingException extends RuntimeException {

    protected BillingException(String message) {
        super(message);
    }

    @SoapFault(faultCode = FaultCode.CLIENT, faultStringOrReason = "NOT_FOUND")
    public static class NotFound extends BillingException {
        public NotFound(String what, String key) {
            super(what + " not found: " + key);
        }
    }

    @SoapFault(faultCode = FaultCode.CLIENT, faultStringOrReason = "BAD_REQUEST")
    public static class BadRequest extends BillingException {
        public BadRequest(String message) {
            super(message);
        }
    }

    @SoapFault(faultCode = FaultCode.CLIENT, faultStringOrReason = "ILLEGAL_STATE")
    public static class IllegalState extends BillingException {
        public IllegalState(String message) {
            super(message);
        }
    }

    @SoapFault(faultCode = FaultCode.RECEIVER, faultStringOrReason = "DOWNSTREAM_FAILURE")
    public static class DownstreamFailure extends BillingException {
        public DownstreamFailure(String message, Throwable cause) {
            super(message);
            initCause(cause);
        }
    }
}
