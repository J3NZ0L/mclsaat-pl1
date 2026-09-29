package hu.mclsaat.legacy.ops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Internal ops console. Detects and resolves the two deliberate failure branches across all three subsystems.
 */
@SpringBootApplication
public class OpsConsoleApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpsConsoleApplication.class, args);
    }
}
