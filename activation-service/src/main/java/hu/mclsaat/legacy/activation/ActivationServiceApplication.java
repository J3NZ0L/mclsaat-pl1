package hu.mclsaat.legacy.activation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Subsystem 2 - subscription activation process. Flowable embedded as a library, asynchronous, message-correlated.
 */
// @EnableScheduling is what gives us a TaskScheduler, which the simulated provisioning platform
// uses to call back on its own thread after a delay rather than inline.
@SpringBootApplication
@EnableScheduling
public class ActivationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ActivationServiceApplication.class, args);
    }
}
