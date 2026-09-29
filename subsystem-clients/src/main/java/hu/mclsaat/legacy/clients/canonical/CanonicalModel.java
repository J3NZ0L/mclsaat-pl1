package hu.mclsaat.legacy.clients.canonical;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One vocabulary for the whole landscape.
 *
 * <p>These types belong to none of the three subsystems. Where the catalog says {@code "PA"},
 * activation says {@code "AWAITING_PROVISIONING"} and billing says nothing at all, this model says
 * {@link SubscriptionStatus#PENDING_ACTIVATION} once. Where the catalog says {@code "00000042"},
 * activation says {@code 42} and billing says {@code "BA-00042"}, this model carries a
 * {@link CustomerRef} and lets {@code SemanticMappers} produce whichever form a given subsystem
 * insists on.
 *
 * <p>Each record keeps the originating subsystem's raw value alongside the canonical one wherever it
 * is not recoverable - {@code catalogStatusCode}, {@code billingAccountNo} - because an ops operator
 * troubleshooting a mismatch needs to see what the subsystem actually said, not only what this layer
 * made of it.
 *
 * <p>This is the model phase 2's MCP tools are expected to expose. See {@code docs/phase2-seams.md}.
 */
public final class CanonicalModel {

    private CanonicalModel() {
    }

    /**
     * A customer, identified the only way all three subsystems can agree on: as a number.
     *
     * <p>{@code catalogCustNo()} and the integer form are two spellings of the same fact.
     * {@code billingAccountNo} is *not* derivable - it is a lookup in billing's own table - so it is
     * carried when known and null otherwise.
     */
    public record CustomerRef(int reference, String billingAccountNo) {

        public CustomerRef {
            if (reference <= 0) {
                throw new IllegalArgumentException("customer reference must be positive");
            }
        }

        public static CustomerRef of(int reference) {
            return new CustomerRef(reference, null);
        }

        /** The catalog's zero-padded {@code CHAR(8)} spelling. */
        public String catalogCustNo() {
            if (reference > 99_999_999) {
                throw new IllegalArgumentException(
                        "customer " + reference + " does not fit the catalog's CHAR(8) cust_no");
            }
            return "%08d".formatted(reference);
        }

        public CustomerRef withBillingAccount(String billingAccountNo) {
            return new CustomerRef(reference, billingAccountNo);
        }

        @Override
        public String toString() {
            return billingAccountNo == null
                    ? "customer " + reference
                    : "customer " + reference + " (" + billingAccountNo + ")";
        }
    }

    /**
     * A phone number, stored as bare digits.
     *
     * <p>The leading {@code +} is presentation: the catalog forbids it and activation requires it, so
     * neither of their spellings can be the canonical one.
     */
    public record PhoneNumber(String digits) {

        public static PhoneNumber of(String anySpelling) {
            if (anySpelling == null || anySpelling.isBlank()) {
                return null;
            }
            String trimmed = anySpelling.trim();
            String digits = trimmed.startsWith("+") ? trimmed.substring(1) : trimmed;
            if (!digits.matches("^[0-9]{8,15}$")) {
                throw new IllegalArgumentException("not an E.164 number: " + anySpelling);
            }
            return new PhoneNumber(digits);
        }

        /** The catalog's spelling. */
        public String withoutPlus() {
            return digits;
        }

        /** Activation's spelling. */
        public String withPlus() {
            return "+" + digits;
        }

        @Override
        public String toString() {
            return withPlus();
        }
    }

    /** The subscription lifecycle, once. The catalog's two-character codes map onto this. */
    public enum SubscriptionStatus {
        NEW,
        PENDING_ACTIVATION,
        ACTIVE,
        SUSPENDED,
        TERMINATED,
        /** The catalog said something this model does not know about. Never silently coerced. */
        UNKNOWN
    }

    /** The activation order lifecycle, once. */
    public enum OrderStatus {
        RECEIVED,
        VALIDATED,
        AWAITING_PROVISIONING,
        /** The provisioning SLA was breached and nobody called back. Failure branch A. */
        STUCK,
        PROVISIONED,
        FAILED,
        CANCELLED,
        UNKNOWN
    }

    /** The invoice lifecycle, once. */
    public enum InvoiceStatus {
        OPEN,
        /** Stripe has the money; the clearing house has not confirmed the batch. Failure branch B. */
        SETTLEMENT_PENDING,
        PAID,
        CANCELLED,
        UNKNOWN
    }

    /** The settlement batch lifecycle, once. */
    public enum BatchStatus {
        OPEN,
        /** Handed to the clearing house; an acknowledgement is owed. */
        SENT,
        ACKNOWLEDGED,
        FAILED,
        UNKNOWN
    }

    public enum ServiceKind {
        INTERNET,
        MOBILE,
        UNKNOWN
    }

    public enum ProductKind {
        BASE,
        ADDON,
        UNKNOWN
    }

    /**
     * A sellable product.
     *
     * @param productCode the catalog's code, which is the registry of record ({@code MOB-VOICE-0010})
     * @param offerId     activation's spelling of the same product ({@code mob.voice.0010})
     * @param monthlyFee  gross, because that is what the catalogue quotes to a consumer
     */
    public record CanonicalPlan(
            String productCode,
            String offerId,
            ServiceKind serviceKind,
            String displayName,
            ProductKind productKind,
            String addonCategory,
            Money monthlyFee,
            DataVolume dataAllowance,
            Integer speedKbps,
            boolean sellable) {
    }

    /**
     * A subscription.
     *
     * @param catalogStatusCode the raw two-character code, kept because an operator needs to see what
     *                          the catalog actually said
     * @param dataAllowance     the *effective* allowance: base plan plus every active add-on
     */
    public record CanonicalSubscription(
            String subscriptionId,
            CustomerRef customer,
            String productCode,
            SubscriptionStatus status,
            String catalogStatusCode,
            LocalDate activatedOn,
            PhoneNumber phoneNumber,
            String simIccid,
            DataVolume dataAllowance,
            String parentSubscriptionId,
            Instant updatedAt) {

        public boolean isAddon() {
            return parentSubscriptionId != null;
        }
    }

    public record CanonicalSubscriber(
            CustomerRef customer,
            String fullName,
            String email,
            PhoneNumber phoneNumber) {
    }

    /**
     * An activation order.
     *
     * @param waitingForCallback whether a live provisioning message subscription exists, which is
     *                           what decides whether the order can still be repaired by injecting the
     *                           callback rather than cancelled
     */
    public record CanonicalOrder(
            String orderNo,
            String changeType,
            CustomerRef customer,
            String productCode,
            OrderStatus status,
            String subscriptionId,
            String simIccid,
            String invoiceNo,
            String failureReason,
            boolean processRunning,
            boolean waitingForCallback,
            String currentActivity,
            Instant updatedAt) {
    }

    /**
     * An invoice.
     *
     * <p>All three amounts are carried rather than derived. The catalogue quotes gross and billing
     * invoices net, and for two of the nine seeded plans {@code net.plus(vat)} does not equal the
     * price the customer was shown - so recomputing either from the other would hide a real
     * discrepancy. See {@code docs/semantic-mismatches.md}.
     */
    public record CanonicalInvoice(
            String invoiceNo,
            String billingAccountNo,
            CustomerRef customer,
            String subscriptionId,
            LocalDate periodStart,
            LocalDate periodEnd,
            Money netAmount,
            Money vatAmount,
            Money grossAmount,
            InvoiceStatus status,
            String paymentRef,
            String batchId,
            Instant issuedAt) {
    }

    /** A settlement batch handed to the clearing house. */
    public record CanonicalBatch(
            String batchId,
            String fileName,
            BatchStatus status,
            int itemCount,
            Money totalAmount,
            Instant createdAt,
            Instant sentAt,
            Instant acknowledgedAt,
            String ackFileName,
            Long ageMinutes) {

        /** Sent, and still owed an acknowledgement. */
        public boolean isUnconfirmed() {
            return status == BatchStatus.SENT;
        }
    }

    /**
     * One stuck activation, as the ops console sees it: activation's view of the process joined to the
     * catalog's view of the subscription.
     *
     * <p>Neither half is enough on its own, and they arrive over different protocols from different
     * databases. This record existing is the argument for the whole module.
     *
     * @param order        activation's side, over REST; null when the catalog row has no live order
     * @param subscription the catalog's side, over direct JDBC; null when the order never reserved one
     */
    public record StuckActivation(
            String orderNo,
            CanonicalOrder order,
            CanonicalSubscription subscription,
            Category category,
            boolean repairableByCallback,
            String diagnosis) {

        public enum Category {
            /** A live process instance is parked and the catalog subscription is pending. Repairable. */
            STUCK_PROCESS,
            /**
             * A catalog subscription is stale in {@code PA} with no live activation order behind it -
             * the process was lost, deleted or never existed. Not repairable by a callback: the
             * message subscription is gone, so it needs cancelling and re-ordering.
             */
            ORPHANED_PENDING_SUBSCRIPTION,
            /** An order reported stuck whose process instance has since gone. */
            ORPHANED_STUCK_ORDER
        }
    }

    /** One unconfirmed batch, joined to the settlement file that was actually sent. */
    public record UnconfirmedSettlement(
            CanonicalBatch batch,
            List<CanonicalInvoice> invoices,
            Money strandedAmount,
            boolean settlementFilePresent,
            List<String> settlementFileLines,
            String diagnosis) {
    }
}
