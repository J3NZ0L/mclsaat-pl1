package hu.mclsaat.legacy.activation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Subsystem 2 - subscription activation process. Flowable embedded as a library, asynchronous, message-correlated.
 */
@SpringBootApplication
public class ActivationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ActivationServiceApplication.class, args);
    }
}
