package hu.mclsaat.legacy.billing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Subsystem 3 - billing and payment. SOAP, batch fixed-width file exchange and Stripe.
 */
@SpringBootApplication
public class BillingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillingServiceApplication.class, args);
    }
}
