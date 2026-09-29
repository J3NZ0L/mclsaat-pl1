package hu.mclsaat.legacy.clients;

import hu.mclsaat.legacy.clients.canonical.CanonicalModel.CanonicalBatch;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.CanonicalInvoice;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.CanonicalOrder;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.CanonicalSubscription;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.OrderStatus;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.StuckActivation;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.SubscriptionStatus;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.UnconfirmedSettlement;
import hu.mclsaat.legacy.clients.canonical.Money;
import hu.mclsaat.legacy.clients.protocol.ActivationRestClient;
import hu.mclsaat.legacy.clients.protocol.BatchFileClient;
import hu.mclsaat.legacy.clients.protocol.BillingSoapClient;
import hu.mclsaat.legacy.clients.protocol.CatalogJdbcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The cross-subsystem correlation that neither failure branch can be diagnosed without.
 *
 * <p>This is the whole argument for the module. Each of the two failure branches is a state that exists
 * in one subsystem and is unrepresentable in its neighbour, so finding one means asking two different
 * systems, over two different protocols, in two different vocabularies, and joining the answers across
 * an identifier-space mismatch. No single query anywhere in the landscape returns it.
 *
 * <p>In phase 2 this class is what an ops MCP tool should call. See {@code docs/phase2-seams.md}.
 */
public class LandscapeDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(LandscapeDiagnostics.class);

    private final CatalogJdbcClient catalog;
    private final ActivationRestClient activation;
    private final BillingSoapClient billing;
    private final BatchFileClient batchFiles;

    public LandscapeDiagnostics(CatalogJdbcClient catalog, ActivationRestClient activation,
                                BillingSoapClient billing, BatchFileClient batchFiles) {
        this.catalog = catalog;
        this.activation = activation;
        this.billing = billing;
        this.batchFiles = batchFiles;
    }

    /**
     * Failure branch A: activations that are not going to finish on their own.
     *
     * <p>Two sources, deliberately incomplete on their own:
     *
     * <ul>
     *   <li><b>Activation, over REST.</b> Knows its process instance is parked, and whether the message
     *       subscription still exists. Does not know whether a catalog subscription was left behind.
     *   <li><b>The catalog, over direct JDBC.</b> Knows it has a subscription that never went live. Has
     *       no word for "stuck" — a row in {@code PA} for four days looks exactly like one that is
     *       about to succeed.
     * </ul>
     *
     * <p>Joining them produces three distinguishable situations, and the distinction is what decides the
     * remedy: a parked process can be pushed through by injecting the callback, whereas a catalog row
     * with no process behind it has no subscription left to correlate into and can only be cancelled and
     * re-ordered.
     *
     * @param olderThanMinutes ignore anything younger, so a freshly placed order is not reported as a
     *                         problem while the platform is still thinking about it
     */
    public List<StuckActivation> stuckActivations(int olderThanMinutes) {
        List<ActivationRestClient.StuckOrderReport> reports = activation.stuckOrders(olderThanMinutes);
        List<CanonicalSubscription> stale = catalog.stalePendingActivation(olderThanMinutes);

        // the join key is the catalog's subscription id, which activation carries as opaque text
        Map<String, CanonicalSubscription> staleBySubscriptionId = new LinkedHashMap<>();
        stale.forEach(subscription -> staleBySubscriptionId.put(subscription.subscriptionId(), subscription));

        List<StuckActivation> findings = new ArrayList<>();

        for (var report : reports) {
            CanonicalOrder order = report.order();
            CanonicalSubscription subscription = order.subscriptionId() == null ? null
                    : staleBySubscriptionId.remove(order.subscriptionId());

            // the order may name a subscription that is no longer stale - look it up to say so
            if (subscription == null && order.subscriptionId() != null) {
                subscription = catalog.findSubscription(order.subscriptionId()).orElse(null);
            }

            StuckActivation.Category category = report.processRunning()
                    ? StuckActivation.Category.STUCK_PROCESS
                    : StuckActivation.Category.ORPHANED_STUCK_ORDER;

            findings.add(new StuckActivation(order.orderNo(), order, subscription, category,
                    report.repairableByCallback(),
                    diagnose(order, subscription, category, report.repairableByCallback())));
        }

        // whatever is left in the catalog has no activation order behind it at all
        for (CanonicalSubscription orphan : staleBySubscriptionId.values()) {
            findings.add(new StuckActivation(null, null, orphan,
                    StuckActivation.Category.ORPHANED_PENDING_SUBSCRIPTION, false,
                    "catalog subscription " + orphan.subscriptionId() + " has been pending activation "
                            + "since " + orphan.updatedAt() + " and no activation order refers to it. "
                            + "There is no process instance to correlate a callback into, so this can "
                            + "only be terminated in the catalog and re-ordered."));
        }

        log.info("stuck-activation diagnosis: {} finding(s) from {} activation report(s) and {} stale "
                + "catalog row(s)", findings.size(), reports.size(), stale.size());
        return findings;
    }

    /**
     * Failure branch B: money Stripe has taken that nobody has posted.
     *
     * <p>Two sources again. Billing, over SOAP, knows which batches were sent and never acknowledged and
     * which invoices are in them. The filesystem knows whether the settlement file those batches name
     * still exists — which is what decides whether {@code RE_DRIVE_ACK} is honest (the file is the record
     * that the money moved) or impossible.
     */
    public List<UnconfirmedSettlement> unconfirmedSettlements(Integer olderThanMinutes) {
        List<UnconfirmedSettlement> findings = new ArrayList<>();

        for (CanonicalBatch batch : billing.unconfirmedBatches(olderThanMinutes)) {
            var detail = billing.paymentBatch(batch.batchId());
            List<CanonicalInvoice> invoices = detail.invoices();

            Money stranded = invoices.stream()
                    .map(CanonicalInvoice::grossAmount)
                    .reduce(Money::plus)
                    .orElse(Money.zero(batch.totalAmount().currency()));

            Optional<List<String>> fileLines = batchFiles.readSettlementFile(batch.fileName());

            findings.add(new UnconfirmedSettlement(detail.batch(), invoices, stranded,
                    fileLines.isPresent(), fileLines.orElse(List.of()),
                    diagnose(detail.batch(), stranded, fileLines.isPresent())));
        }

        log.info("unconfirmed-settlement diagnosis: {} finding(s)", findings.size());
        return findings;
    }

    /** Whether each subsystem can be reached at all, over the protocol it actually uses. */
    public Map<String, Boolean> reachability() {
        Map<String, Boolean> health = new LinkedHashMap<>();
        health.put("catalog (direct JDBC)", catalog.reachable());
        health.put("activation (REST)", activation.reachable());
        health.put("billing (SOAP)", billing.reachable());
        health.put("settlement outbox (files)", batchFiles.reachable());
        return health;
    }

    // ----------------------------------------------------------------- diagnoses

    private static String diagnose(CanonicalOrder order, CanonicalSubscription subscription,
                                  StuckActivation.Category category, boolean repairable) {
        StringBuilder diagnosis = new StringBuilder();

        if (order.status() == OrderStatus.STUCK) {
            diagnosis.append("order ").append(order.orderNo())
                    .append(" was reported stuck: the network platform never confirmed provisioning");
        } else {
            diagnosis.append("order ").append(order.orderNo()).append(" is still ")
                    .append(order.status())
                    .append(" and has been for longer than the reporting threshold");
        }

        if (subscription == null) {
            diagnosis.append(". Nothing was reserved in the catalog, so there is no inconsistency "
                    + "to repair there");
        } else if (subscription.status() == SubscriptionStatus.PENDING_ACTIVATION) {
            diagnosis.append(". Catalog subscription ").append(subscription.subscriptionId())
                    .append(" is left in PA (pending activation)")
                    .append(subscription.phoneNumber() == null ? ""
                            : " for " + subscription.phoneNumber())
                    .append(", so the customer is paying for a service that is not live");
        } else {
            diagnosis.append(". Catalog subscription ").append(subscription.subscriptionId())
                    .append(" is already ").append(subscription.status())
                    .append(", so the catalog side is consistent and only the order needs closing");
        }

        if (repairable) {
            diagnosis.append(". The process is still waiting for provisioningCompleted, so injecting "
                    + "the callback (force-provision) will finish it normally");
        } else if (category == StuckActivation.Category.ORPHANED_STUCK_ORDER) {
            diagnosis.append(". The process instance is gone, so there is nothing left to correlate "
                    + "into and the order can only be cancelled");
        }

        return diagnosis.append('.').toString();
    }

    private static String diagnose(CanonicalBatch batch, Money stranded, boolean filePresent) {
        StringBuilder diagnosis = new StringBuilder("batch ")
                .append(batch.batchId())
                .append(" was handed to the clearing house")
                .append(batch.sentAt() == null ? "" : " at " + batch.sentAt())
                .append(" and has not been acknowledged");

        if (batch.ageMinutes() != null) {
            diagnosis.append(" for ").append(batch.ageMinutes()).append(" minute(s)");
        }
        diagnosis.append(". ").append(stranded)
                .append(" has already been taken at Stripe but is not posted.");

        if (filePresent) {
            diagnosis.append(" The settlement file ").append(batch.fileName())
                    .append(" is present in the outbox, so it is the record of what was sent: "
                            + "RE_DRIVE_ACK can rebuild the acknowledgement from it, or RESEND can "
                            + "hand it over again if the clearing house is back.");
        } else {
            diagnosis.append(" The settlement file ")
                    .append(batch.fileName() == null ? "(none recorded)" : batch.fileName())
                    .append(" is NOT in the outbox, so there is no record of what was sent. "
                            + "Neither RESEND nor RE_DRIVE_ACK can be trusted here - this one needs "
                            + "a human and the clearing house's own statement.");
        }

        return diagnosis.toString();
    }
}
