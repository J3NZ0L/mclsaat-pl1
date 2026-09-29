package hu.mclsaat.legacy.billing;

import hu.mclsaat.legacy.billing.batch.BatchExchangeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Subsystem 3 - billing and payment. SOAP, batch fixed-width file exchange and Stripe.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(BatchExchangeProperties.class)
public class BillingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillingServiceApplication.class, args);
    }
}
