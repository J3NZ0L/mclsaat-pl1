package hu.mclsaat.legacy.activation.process;

/** The process variable names, so the BPMN file and the delegates cannot drift apart silently. */
public final class ProcessVariables {

    /** Also the process instance's business key, which is what messages correlate on. */
    public static final String ORDER_NO = "orderNo";
    public static final String CHANGE_TYPE = "changeType";
    public static final String CUSTOMER_REF = "customerRef";
    public static final String OFFER_ID = "offerId";
    public static final String MSISDN = "msisdn";
    public static final String REQUESTED_START_DATE = "requestedStartDate";
    public static final String TARGET_SUBSCRIPTION_REF = "targetSubscriptionRef";
    public static final String SUBSCRIPTION_REF = "subscriptionRef";
    public static final String SIM_ICCID = "simIccid";
    public static final String INVOICE_REF = "invoiceRef";
    /** Fault injection for failure branch A: the provisioning platform never calls back. */
    public static final String SIMULATE_STUCK = "simulateStuck";
    /** ISO-8601 duration for the boundary timer, e.g. {@code PT30S}. */
    public static final String PROVISIONING_TIMEOUT = "provisioningTimeout";
    public static final String MONTHLY_FEE_HUF = "monthlyFeeHuf";
    public static final String DATA_ALLOWANCE_GB = "dataAllowanceGb";

    /** Set by validation just before it throws, read by {@code markRejected}. */
    public static final String REJECTION_CODE = "rejectionCode";
    public static final String REJECTION_MESSAGE = "rejectionMessage";

    public static final String MESSAGE_PROVISIONING_COMPLETED = "provisioningCompleted";
    public static final String PROCESS_KEY = "subscriptionActivation";

    private ProcessVariables() {
    }
}
