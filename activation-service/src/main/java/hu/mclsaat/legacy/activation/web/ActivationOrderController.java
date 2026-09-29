package hu.mclsaat.legacy.activation.web;

import hu.mclsaat.legacy.activation.service.ActivationOrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Services 2, 3 and 4, plus the callback an external system uses.
 *
 * <p>{@code POST /orders} returns {@code 202 Accepted} with the order in {@code RECEIVED}. It cannot
 * do better: the process runs on the job executor and the SIM has not been ordered yet, let alone
 * activated. Everything after that is discovered by polling.
 *
 * <p><b>Why the order number is a query parameter and not a path variable.</b> Order numbers contain
 * slashes ({@code ORD/2026/0000001}) because that is what this subsystem's numbering scheme looks
 * like. A path variable cannot carry one: Tomcat rejects an encoded {@code %2F} by default, and
 * configuring it to decode turns the order number into three path segments that no longer match the
 * mapping. So the legacy identifier format dictates the shape of the API - which is itself a fair
 * example of what working with one of these systems is like.
 */
@RestController
@RequestMapping("/activation/v1")
public class ActivationOrderController {

    private final ActivationOrderService activation;

    public ActivationOrderController(ActivationOrderService activation) {
        this.activation = activation;
    }

    /** Services 2 and 4: one endpoint, three {@code changeType} variants of the same process. */
    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ActivationDtos.OrderView start(@Valid @RequestBody ActivationDtos.StartOrderRequest request) {
        return ActivationDtos.OrderView.of(activation.startOrder(
                request.changeType(), request.customerRef(), request.offerId(), request.msisdn(),
                request.requestedStartDate(), request.targetSubscriptionRef(),
                request.simulateStuck(), request.provisioningTimeout()));
    }

    /**
     * Service 3: poll one order.
     *
     * <p>{@code GET /activation/v1/orders?orderNo=ORD/2026/0000001}. The slashes go through fine as a
     * query parameter value.
     */
    @GetMapping(value = "/orders", params = "orderNo")
    public ActivationDtos.OrderStatusView status(@RequestParam String orderNo) {
        return ActivationDtos.OrderStatusView.of(activation.status(orderNo.trim()));
    }

    /** Every order for one customer. {@code GET /activation/v1/orders?customerRef=42}. */
    @GetMapping(value = "/orders", params = "customerRef")
    public List<ActivationDtos.OrderView> ordersOf(@RequestParam int customerRef) {
        return activation.ordersOf(customerRef).stream()
                .map(ActivationDtos.OrderView::of)
                .toList();
    }

    /**
     * The network provisioning platform reporting back.
     *
     * <p>This is the asynchronous seam of the whole system: the HTTP request that started the order
     * is long gone, and this call correlates into a process instance that has been waiting on a
     * message subscription ever since.
     */
    @PostMapping("/callbacks/provisioning")
    public ActivationDtos.OrderView provisioningCallback(
            @Valid @RequestBody ActivationDtos.ProvisioningCallbackRequest request) {
        if (request.outcome() != null && !"COMPLETED".equalsIgnoreCase(request.outcome())) {
            throw new hu.mclsaat.legacy.activation.service.ActivationException.BadRequest(
                    "only the COMPLETED outcome is handled, got: " + request.outcome());
        }
        return ActivationDtos.OrderView.of(activation.correlateProvisioningCompleted(
                request.orderNo(), request.simIccid(), "the provisioning platform"));
    }
}
