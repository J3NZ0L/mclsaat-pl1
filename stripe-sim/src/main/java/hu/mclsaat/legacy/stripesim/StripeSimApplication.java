package hu.mclsaat.legacy.stripesim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal Stripe-API-shaped simulator for the non-Docker local path. Docker compose uses the official stripe/stripe-mock image instead.
 */
@SpringBootApplication
public class StripeSimApplication {

    public static void main(String[] args) {
        SpringApplication.run(StripeSimApplication.class, args);
    }
}
