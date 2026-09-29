package hu.mclsaat.legacy.activation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The mocked external network provisioning platform.
 *
 * <p>Real SIM and line provisioning means talking to an OSS that owns physical inventory, so this is
 * one of the two things in the whole system that genuinely has to be faked. It is faked honestly: it
 * calls back over HTTP to the same public callback endpoint an external system would use, after a
 * delay, from a different thread. Nothing short-circuits into the engine.
 *
 * <p>It can be told to stay silent, either globally or per order. That silence is failure branch A:
 * the process waits, the boundary timer fires, the order goes {@code STUCK}, and the catalog is left
 * holding a subscription that never went live.
 */
@Component
public class ProvisioningPlatformSimulator {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningPlatformSimulator.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TaskScheduler scheduler;
    private final RestClient rest;
    private final String callbackPath;
    private final Duration callbackDelay;
    /** Global off switch, so the demo can make every order stick without touching each one. */
    private final AtomicBoolean callbacksEnabled = new AtomicBoolean(true);

    public ProvisioningPlatformSimulator(
            TaskScheduler scheduler,
            RestClient.Builder builder,
            @Value("${activation.provisioning.callback-base-url}") String callbackBaseUrl,
            @Value("${activation.provisioning.callback-path:/activation/v1/callbacks/provisioning}")
            String callbackPath,
            @Value("${activation.provisioning.callback-delay:PT2S}") Duration callbackDelay) {
        this.scheduler = scheduler;
        this.rest = builder.baseUrl(callbackBaseUrl).build();
        this.callbackPath = callbackPath;
        this.callbackDelay = callbackDelay;
        log.info("provisioning platform simulator will call back to {}{} after {}",
                callbackBaseUrl, callbackPath, callbackDelay);
    }

    public boolean isCallbacksEnabled() {
        return callbacksEnabled.get();
    }

    /** @return the previous setting */
    public boolean setCallbacksEnabled(boolean enabled) {
        boolean previous = callbacksEnabled.getAndSet(enabled);
        log.warn("provisioning callbacks {} (was {})", enabled ? "ENABLED" : "SUPPRESSED",
                previous ? "enabled" : "suppressed");
        return previous;
    }

    /**
     * @param needsSim      a new mobile subscription needs a SIM, so the callback carries an ICCID;
     *                      a plan change or an add-on only needs the network profile reloaded
     * @param simulateStuck this order specifically is to be ignored
     */
    public void requestProvisioning(String orderNo, String msisdn, boolean needsSim,
                                    boolean simulateStuck) {
        if (simulateStuck || !callbacksEnabled.get()) {
            log.warn("provisioning platform is ignoring order {} on purpose; no callback will arrive",
                    orderNo);
            return;
        }

        String iccid = needsSim ? issueIccid() : null;
        scheduler.schedule(() -> callBack(orderNo, iccid), Instant.now().plus(callbackDelay));
        log.info("provisioning platform accepted order {}{}; it will call back in {}", orderNo,
                iccid == null ? "" : " and allocated SIM " + iccid, callbackDelay);
    }

    private void callBack(String orderNo, String iccid) {
        Map<String, Object> body = iccid == null
                ? Map.of("orderNo", orderNo, "outcome", "COMPLETED")
                : Map.of("orderNo", orderNo, "outcome", "COMPLETED", "simIccid", iccid);
        try {
            rest.post().uri(callbackPath).body(body).retrieve().toBodilessEntity();
            log.info("provisioning platform called back for order {}", orderNo);
        } catch (RuntimeException ex) {
            // A real platform would retry. This one logs, because a lost callback is indistinguishable
            // from a suppressed one and that is exactly the situation ops has to be able to repair.
            log.error("provisioning callback for order {} failed: {}", orderNo, ex.getMessage());
        }
    }

    /** A plausible Hungarian-operator ICCID: 89 36 01 00 + 11 digits, 19 characters in total. */
    private static String issueIccid() {
        StringBuilder iccid = new StringBuilder("89360100");
        for (int i = 0; i < 11; i++) {
            iccid.append(RANDOM.nextInt(10));
        }
        return iccid.toString();
    }
}
