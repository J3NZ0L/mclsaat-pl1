package hu.mclsaat.legacy.activation.web;

import hu.mclsaat.legacy.activation.service.ActivationOrderService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The activation side of service 6, for internal ops only.
 *
 * <p>Kept under its own path prefix so the boundary between what a customer may reach and what a
 * back-office operator may reach is visible in the URL. Nothing enforces it at this layer - there is
 * no authentication anywhere in this system - but the separation is the seam phase 2's ops agent
 * gets its wider permissions from.
 */
@RestController
@RequestMapping("/activation/v1/ops")
public class ActivationOpsController {

    private final ActivationOrderService activation;

    public ActivationOpsController(ActivationOrderService activation) {
        this.activation = activation;
    }

    /**
     * Orders that are waiting on a provisioning callback or have already been reported stuck.
     *
     * @param olderThanMinutes ignore orders younger than this, so a demo that just started an order
     *                         does not report it as a problem
     */
    @GetMapping("/stuck-orders")
    public List<ActivationDtos.StuckOrderView> stuckOrders(
            @RequestParam(defaultValue = "1") int olderThanMinutes) {
        return activation.stuckOrders(olderThanMinutes).stream()
                .map(ActivationDtos.StuckOrderView::of)
                .toList();
    }

    /**
     * Pushes a stuck order through by supplying the callback the platform never sent.
     *
     * <p>This is the same correlation the platform's own callback performs, which is why it works at
     * all: the boundary timer was non-interrupting, so the message subscription is still there.
     *
     * <p>The order number is in the body rather than the path for the reason set out on
     * {@code ActivationOrderController}: it contains slashes.
     */
    @PostMapping("/orders/force-provision")
    public ActivationDtos.OrderView forceProvision(
            @Valid @RequestBody ActivationDtos.ForceProvisionRequest request) {
        return ActivationDtos.OrderView.of(activation.correlateProvisioningCompleted(
                request.orderNo().trim(), request.simIccid(), "ops"));
    }

    /** Gives up on the order. Not reversible: the process instance and its subscription are gone. */
    @PostMapping("/orders/cancel")
    public ActivationDtos.OrderView cancel(
            @Valid @RequestBody ActivationDtos.CancelOrderRequest request) {
        String reason = request.reason() == null || request.reason().isBlank()
                ? "cancelled by ops" : request.reason();
        return ActivationDtos.OrderView.of(activation.cancelOrder(request.orderNo().trim(), reason));
    }

    /**
     * The control surface of the mocked provisioning platform: the global off switch for callbacks.
     * Turning it off makes every subsequent order strand, which is how the demo triggers failure
     * branch A without touching individual orders.
     */
    @GetMapping("/provisioning-platform")
    public Map<String, Object> platformConfig() {
        return Map.of("callbacksEnabled", activation.platform().isCallbacksEnabled());
    }

    @PostMapping("/provisioning-platform")
    public Map<String, Object> configurePlatform(@RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.parseBoolean(
                String.valueOf(body.getOrDefault("callbacksEnabled", true)));
        boolean previous = activation.platform().setCallbacksEnabled(enabled);
        return Map.of("callbacksEnabled", enabled, "previousCallbacksEnabled", previous);
    }
}
