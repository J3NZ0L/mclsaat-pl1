package hu.mclsaat.legacy.billing;

import hu.mclsaat.legacy.billing.payment.PaymentService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The last hop of the money representation chain: NUMERIC(12,2) back to integer minor units. */
class PaymentServiceTest {

    @Test
    void grossAmountsBecomeStripeMinorUnits() {
        assertThat(PaymentService.toMinorUnits(new BigDecimal("12990.00"))).isEqualTo(1_299_000L);
        assertThat(PaymentService.toMinorUnits(new BigDecimal("5990.01"))).isEqualTo(599_001L);
        assertThat(PaymentService.toMinorUnits(new BigDecimal("0.00"))).isZero();
    }

    @Test
    void theCatalogsFillerValueComesBackUnchangedWhenTheRoundTripWasClean() {
        // INET-FIB-0500 is stored in the catalog as 1299000 fillér and arrives at Stripe as
        // 1299000 minor units, having been a 10228.35 + 2761.65 NUMERIC(12,2) pair in between
        assertThat(PaymentService.toMinorUnits(new BigDecimal("10228.35").add(new BigDecimal("2761.65"))))
                .isEqualTo(1_299_000L);
    }

    @Test
    void afractionOfAMinorUnitIsRefusedRatherThanSilentlyRounded() {
        assertThatThrownBy(() -> PaymentService.toMinorUnits(new BigDecimal("1000.005")))
                .isInstanceOf(ArithmeticException.class);
    }
}
