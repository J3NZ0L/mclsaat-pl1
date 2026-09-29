package hu.mclsaat.legacy.activation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards the checked-in copy of billing's schema against drift.
 *
 * <p>Activation generates its SOAP client from its own copy of the contract, because that is what an
 * integration team handed a WSDL actually does. The cost of that realism is that the copy can go
 * stale, so this test compares it against billing's original byte for byte. If billing changes its
 * contract, this fails and the copy has to be refreshed deliberately.
 */
class BillingContractCopyTest {

    private static final Path OUR_COPY =
            Path.of("src", "main", "resources", "xsd", "billing-v1-vendor-copy.xsd");
    private static final Path BILLINGS_ORIGINAL =
            Path.of("..", "billing-service", "src", "main", "resources", "xsd", "billing-v1.xsd");

    @Test
    void ourCopyOfTheBillingContractMatchesBillingsOriginal() throws IOException {
        assertThat(OUR_COPY).as("activation's copy of the billing contract").exists();
        assumeTrue(Files.isReadable(BILLINGS_ORIGINAL),
                "billing-service is not on disk next to this module, so drift cannot be checked");

        assertThat(Files.readString(OUR_COPY))
                .as("""
                        activation's copy of the billing contract has drifted from billing's original.
                        Refresh it deliberately:
                          cp billing-service/src/main/resources/xsd/billing-v1.xsd \\
                             activation-service/src/main/resources/xsd/billing-v1-vendor-copy.xsd
                        then re-run the build so the JAXB stubs are regenerated.""")
                .isEqualTo(Files.readString(BILLINGS_ORIGINAL));
    }
}
