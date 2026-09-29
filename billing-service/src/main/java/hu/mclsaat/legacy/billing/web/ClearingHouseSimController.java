package hu.mclsaat.legacy.billing.web;

import hu.mclsaat.legacy.billing.batch.ClearingHouseSimulator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The control surface of the mocked external clearing house.
 *
 * <p>It lives under {@code /sim/} rather than in the business API because it is not part of the
 * business: it is the knob that injects failure branch B. Switching acknowledgement off makes the
 * next settlement batch strand in {@code SENT} with its invoices in {@code SETTLEMENT_PENDING},
 * which is exactly the situation the ops console then has to find and repair.
 */
@RestController
@RequestMapping("/sim/clearing-house")
public class ClearingHouseSimController {

    private final ClearingHouseSimulator clearingHouse;

    public ClearingHouseSimController(ClearingHouseSimulator clearingHouse) {
        this.clearingHouse = clearingHouse;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("ackEnabled", clearingHouse.isAckEnabled());
    }

    /** @param body {@code {"ackEnabled": false}} to make the clearing house go quiet */
    @PostMapping("/config")
    public Map<String, Object> configure(@RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.parseBoolean(String.valueOf(body.getOrDefault("ackEnabled", true)));
        boolean previous = clearingHouse.setAckEnabled(enabled);
        return Map.of("ackEnabled", enabled, "previousAckEnabled", previous);
    }
}
