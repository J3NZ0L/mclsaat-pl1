package hu.mclsaat.legacy.billing;

import hu.mclsaat.legacy.billing.ws.gen.CreateInvoiceRequest;
import hu.mclsaat.legacy.billing.ws.gen.CreateInvoiceResponse;
import hu.mclsaat.legacy.billing.ws.gen.GetInvoicesRequest;
import hu.mclsaat.legacy.billing.ws.gen.GetInvoicesResponse;
import hu.mclsaat.legacy.billing.ws.gen.InvoiceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.SoapFaultClientException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives subsystem 3 over real SOAP on a real HTTP port, the way the activation process and the
 * ops console do. Nothing here short-circuits the marshalling: if the WSDL, the namespaces or
 * the fault mapping were wrong, these tests would fail.
 */
class BillingSoapApiIT extends BillingIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;

    private WebServiceTemplate soap;

    @BeforeEach
    void setUpSoapClient() {
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setContextPath("hu.mclsaat.legacy.billing.ws.gen");
        soap = new WebServiceTemplate(marshaller);
        soap.setDefaultUri("http://localhost:" + port + "/ws");
    }

    @Test
    void theWsdlIsServedAndDescribesTheOperations() {
        var response = rest.getForEntity("/ws/billing.wsdl", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String wsdl = response.getBody();
        assertThat(wsdl).contains("http://mclsaat.hu/legacy/billing/v1");
        assertThat(wsdl).contains("BillingPort");
        assertThat(wsdl).contains("getInvoicesRequest");
        assertThat(wsdl).contains("createInvoiceRequest");
        assertThat(wsdl).contains("startPaymentRequest");
        assertThat(wsdl).contains("reconcileBatchRequest");
    }

    @Test
    void invoicesCanBeFetchedByBillingAccountNumber() {
        GetInvoicesRequest request = new GetInvoicesRequest();
        request.setBillingAccountNo("BA-00042");

        GetInvoicesResponse response = (GetInvoicesResponse) soap.marshalSendAndReceive(request);

        assertThat(response.getInvoice()).isNotEmpty();
        InvoiceType first = response.getInvoice().get(0);
        assertThat(first.getInvoiceNo()).isEqualTo("2026/INV/000001");
        assertThat(first.getCustomerRef()).isEqualTo(42);
        // major units with two decimals, not the catalog's integer fillér
        assertThat(first.getGrossAmount()).isEqualByComparingTo("12990.00");
        assertThat(first.getNetAmount()).isEqualByComparingTo("10228.35");
        assertThat(first.getVatRate()).isEqualByComparingTo("0.27");
        assertThat(first.getCurrency()).isEqualTo("HUF");
        // billing's date format, not the activation subsystem's yyyyMMdd
        assertThat(first.getPeriodStart()).isEqualTo("2026-08-01");
    }

    @Test
    void invoicesCanAlsoBeFetchedByTheIntegerCustomerRef() {
        GetInvoicesRequest request = new GetInvoicesRequest();
        request.setCustomerRef(42);
        request.setStatus("OPEN");

        GetInvoicesResponse response = (GetInvoicesResponse) soap.marshalSendAndReceive(request);

        // other tests in this class issue further invoices for customer 42, so assert on the
        // seeded ones being present and on the filter being honoured, not on an exact count
        assertThat(response.getInvoice()).allSatisfy(invoice ->
                assertThat(invoice.getStatus()).isEqualTo("OPEN"));
        assertThat(response.getInvoice()).extracting(InvoiceType::getInvoiceNo)
                .contains("2026/INV/000002", "2026/INV/000003")
                .doesNotContain("2026/INV/000001", "2026/INV/000005");
    }

    @Test
    void theSeededStrandedInvoiceIsVisibleAndCarriesItsBatch() {
        GetInvoicesRequest request = new GetInvoicesRequest();
        request.setCustomerRef(42);
        request.setStatus("SETTLEMENT_PENDING");

        GetInvoicesResponse response = (GetInvoicesResponse) soap.marshalSendAndReceive(request);

        assertThat(response.getInvoice()).hasSize(1);
        InvoiceType stranded = response.getInvoice().get(0);
        assertThat(stranded.getInvoiceNo()).isEqualTo("2026/INV/000005");
        assertThat(stranded.getBatchId()).isEqualTo("BATCH-20260925-001");
        assertThat(stranded.getPaymentRef()).isEqualTo("pi_seed_000005");
    }

    @Test
    void theCatalogsPaddedCustomerNumberIsNotAcceptedHere() {
        GetInvoicesRequest request = new GetInvoicesRequest();
        request.setBillingAccountNo("00000042"); // the catalog's identifier, not billing's

        assertThatThrownBy(() -> soap.marshalSendAndReceive(request))
                .isInstanceOf(SoapFaultClientException.class)
                .hasMessageContaining("NOT_FOUND");
    }

    @Test
    void askingForNeitherOrBothIdentifiersIsAFault() {
        assertThatThrownBy(() -> soap.marshalSendAndReceive(new GetInvoicesRequest()))
                .isInstanceOf(SoapFaultClientException.class)
                .hasMessageContaining("BAD_REQUEST");

        GetInvoicesRequest both = new GetInvoicesRequest();
        both.setBillingAccountNo("BA-00042");
        both.setCustomerRef(42);
        assertThatThrownBy(() -> soap.marshalSendAndReceive(both))
                .isInstanceOf(SoapFaultClientException.class)
                .hasMessageContaining("BAD_REQUEST");
    }

    @Test
    void createInvoiceAddsVatToTheNetAmountItIsGiven() {
        CreateInvoiceRequest request = newInvoiceRequest(43, "SUB-2026-000003",
                new BigDecimal("4716.54"), UUID.randomUUID().toString());

        CreateInvoiceResponse response = (CreateInvoiceResponse) soap.marshalSendAndReceive(request);

        assertThat(response.isAlreadyExisted()).isFalse();
        InvoiceType invoice = response.getInvoice();
        assertThat(invoice.getInvoiceNo()).matches("^\\d{4}/INV/\\d{6}$");
        assertThat(invoice.getBillingAccountNo()).isEqualTo("BA-00043");
        assertThat(invoice.getNetAmount()).isEqualByComparingTo("4716.54");
        assertThat(invoice.getVatAmount()).isEqualByComparingTo("1273.47");
        // the 5990.01 artefact: the catalog quoted this plan at 5990.00 gross
        assertThat(invoice.getGrossAmount()).isEqualByComparingTo("5990.01");
        assertThat(invoice.getStatus()).isEqualTo("OPEN");
    }

    @Test
    void createInvoiceIsIdempotentOnRequestRefSoRetriedJobsCannotDoubleInvoice() {
        String requestRef = "flowable-job-" + UUID.randomUUID();
        CreateInvoiceRequest request = newInvoiceRequest(42, "SUB-2026-000002",
                new BigDecimal("4716.54"), requestRef);

        CreateInvoiceResponse first = (CreateInvoiceResponse) soap.marshalSendAndReceive(request);
        CreateInvoiceResponse second = (CreateInvoiceResponse) soap.marshalSendAndReceive(
                newInvoiceRequest(42, "SUB-2026-000002", new BigDecimal("4716.54"), requestRef));

        assertThat(first.isAlreadyExisted()).isFalse();
        assertThat(second.isAlreadyExisted()).isTrue();
        assertThat(second.getInvoice().getInvoiceNo()).isEqualTo(first.getInvoice().getInvoiceNo());
    }

    @Test
    void anUnknownCustomerRefGetsABillingAccountOpenedWhenANameIsSupplied() {
        CreateInvoiceRequest request = newInvoiceRequest(777, "SUB-2026-000777",
                new BigDecimal("1000.00"), UUID.randomUUID().toString());
        request.setAccountName("Új Előfizető");
        request.setBillingEmail("uj.elofizeto@example.hu");

        CreateInvoiceResponse response = (CreateInvoiceResponse) soap.marshalSendAndReceive(request);

        assertThat(response.getInvoice().getBillingAccountNo()).matches("^BA-\\d{5}$");
        assertThat(response.getInvoice().getCustomerRef()).isEqualTo(777);
    }

    @Test
    void anUnknownCustomerRefWithoutANameIsAFault() {
        CreateInvoiceRequest request = newInvoiceRequest(888, "SUB-2026-000888",
                new BigDecimal("1000.00"), UUID.randomUUID().toString());

        assertThatThrownBy(() -> soap.marshalSendAndReceive(request))
                .isInstanceOf(SoapFaultClientException.class)
                .hasMessageContaining("BAD_REQUEST");
    }

    @Test
    void aMisformattedDateIsAFaultBecauseBillingOnlySpeaksYyyyMmDd() {
        CreateInvoiceRequest request = newInvoiceRequest(42, "SUB-2026-000001",
                new BigDecimal("1000.00"), UUID.randomUUID().toString());
        request.setPeriodStart("20261001"); // the activation subsystem's format

        assertThatThrownBy(() -> soap.marshalSendAndReceive(request))
                .isInstanceOf(SoapFaultClientException.class)
                .hasMessageContaining("BAD_REQUEST");
    }

    private static CreateInvoiceRequest newInvoiceRequest(int customerRef, String subscriptionRef,
                                                          BigDecimal netAmount, String requestRef) {
        CreateInvoiceRequest request = new CreateInvoiceRequest();
        request.setCustomerRef(customerRef);
        request.setSubscriptionRef(subscriptionRef);
        request.setPeriodStart("2026-10-01");
        request.setPeriodEnd("2026-10-31");
        request.setNetAmount(netAmount);
        request.setCurrency("HUF");
        request.setRequestRef(requestRef);
        return request;
    }
}
