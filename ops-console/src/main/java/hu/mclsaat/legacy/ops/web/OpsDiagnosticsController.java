package hu.mclsaat.legacy.ops.web;

import hu.mclsaat.legacy.clients.LandscapeDiagnostics;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.UnconfirmedSettlement;
import hu.mclsaat.legacy.clients.canonical.Money;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Service 6, detection half. Internal ops only.
 *
 * <p>Every endpoint here spans subsystems, and each uses a different protocol to do it. That is not
 * decoration: both failure branches are states that exist in one subsystem and cannot be expressed in
 * its neighbour, so neither can be seen from inside a single system.
 */
@RestController
@RequestMapping("/ops/v1/diagnostics")
public class OpsDiagnosticsController {

    private final LandscapeDiagnostics diagnostics;

    public OpsDiagnosticsController(LandscapeDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    /**
     * Failure branch A. Joins activation's parked process instances (REST) with the catalog's stale
     * pending subscriptions (direct JDBC), across an identifier-space mismatch.
     *
     * @param olderThanMinutes ignore anything younger, so an order placed seconds ago is not reported
     *                         while the provisioning platform is still thinking about it
     */
    @GetMapping("/stuck-activations")
    public List<OpsDtos.StuckActivationView> stuckActivations(
            @RequestParam(defaultValue = "1") int olderThanMinutes) {
        return diagnostics.stuckActivations(olderThanMinutes).stream()
                .map(OpsDtos.StuckActivationView::of)
                .toList();
    }

    /**
     * Failure branch B. Joins billing's unacknowledged batches (SOAP) with the settlement files on
     * disk, because whether the file still exists is what decides which remedy is honest.
     *
     * @param olderThanMinutes {@code null} uses billing's own configured threshold
     */
    @GetMapping("/unconfirmed-batches")
    public List<OpsDtos.UnconfirmedSettlementView> unconfirmedBatches(
            @RequestParam(required = false) Integer olderThanMinutes) {
        return diagnostics.unconfirmedSettlements(olderThanMinutes).stream()
                .map(OpsDtos.UnconfirmedSettlementView::of)
                .toList();
    }

    /** Both branches plus subsystem reachability: "is anything wrong right now?" in one call. */
    @GetMapping("/overview")
    public OpsDtos.OverviewView overview(
            @RequestParam(defaultValue = "1") int olderThanMinutes,
            @RequestParam(required = false) Integer batchesOlderThanMinutes) {

        var stuck = diagnostics.stuckActivations(olderThanMinutes);
        var settlements = diagnostics.unconfirmedSettlements(batchesOlderThanMinutes);

        Money stranded = settlements.stream()
                .map(UnconfirmedSettlement::strandedAmount)
                .reduce(Money::plus)
                .orElse(Money.zero("HUF"));

        return new OpsDtos.OverviewView(
                diagnostics.reachability(),
                stuck.size(),
                settlements.size(),
                stranded.toString(),
                stuck.stream().map(OpsDtos.StuckActivationView::of).toList(),
                settlements.stream().map(OpsDtos.UnconfirmedSettlementView::of).toList());
    }
}
