package hu.mclsaat.legacy.clients;

import com.sun.net.httpserver.HttpServer;
import hu.mclsaat.legacy.clients.canonical.CanonicalModel.InvoiceStatus;
import hu.mclsaat.legacy.clients.protocol.BillingSoapClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The typed {@code payInvoice} call over a real HTTP socket, answered with hand-written SOAP. */
class BillingSoapClientPayInvoiceTest {

    private static final String NS = "http://mclsaat.hu/legacy/billing/v1";

    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private volatile String responseBody;
    private volatile int responseStatus = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ws", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/xml; charset=utf-8");
            exchange.sendResponseHeaders(responseStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private BillingSoapClient client() {
        return new BillingSoapClient("http://127.0.0.1:" + server.getAddress().getPort() + "/ws");
    }

    private static String envelope(String body) {
        return "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<SOAP-ENV:Header/><SOAP-ENV:Body>" + body + "</SOAP-ENV:Body></SOAP-ENV:Envelope>";
    }

    @Test
    void payInvoiceSendsThePayInvoiceRequestAndMapsTheStatusToTheCanonicalVocabulary() {
        responseBody = envelope("<ns2:payInvoiceResponse xmlns:ns2=\"" + NS + "\">"
                + "<ns2:invoiceNo>2026/INV/000009</ns2:invoiceNo>"
                + "<ns2:paymentRef>pi_sim_000001abc</ns2:paymentRef>"
                + "<ns2:invoiceStatus>SETTLEMENT_PENDING</ns2:invoiceStatus>"
                + "<ns2:changed>true</ns2:changed>"
                + "<ns2:batchId>BATCH-20261006-001</ns2:batchId>"
                + "<ns2:message>payment taken</ns2:message>"
                + "</ns2:payInvoiceResponse>");

        var result = client().payInvoice("2026/INV/000009");

        assertThat(requestBody.get()).contains("payInvoiceRequest").contains("<ns2:invoiceNo>2026/INV/000009<");
        assertThat(result.invoiceNo()).isEqualTo("2026/INV/000009");
        assertThat(result.paymentRef()).isEqualTo("pi_sim_000001abc");
        assertThat(result.status()).isEqualTo(InvoiceStatus.SETTLEMENT_PENDING);
        assertThat(result.changed()).isTrue();
        assertThat(result.batchId()).isEqualTo("BATCH-20261006-001");
    }

    @Test
    void anAlreadyPaidInvoiceComesBackWithNoBatchAndNothingChanged() {
        responseBody = envelope("<ns2:payInvoiceResponse xmlns:ns2=\"" + NS + "\">"
                + "<ns2:invoiceNo>2026/INV/000001</ns2:invoiceNo>"
                + "<ns2:invoiceStatus>PAID</ns2:invoiceStatus>"
                + "<ns2:changed>false</ns2:changed>"
                + "<ns2:message>already PAID; nothing was done</ns2:message>"
                + "</ns2:payInvoiceResponse>");

        var result = client().payInvoice("2026/INV/000001");

        assertThat(result.status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(result.changed()).isFalse();
        assertThat(result.paymentRef()).isNull();
        assertThat(result.batchId()).isNull();
    }

    @Test
    void aSoapFaultKeepsItsCodeSoACallerCanBranchOnIt() {
        responseStatus = 500;
        responseBody = envelope("<SOAP-ENV:Fault><faultcode>SOAP-ENV:Client</faultcode>"
                + "<faultstring xml:lang=\"en\">NOT_FOUND</faultstring></SOAP-ENV:Fault>");

        assertThatThrownBy(() -> client().payInvoice("2026/INV/999999"))
                .isInstanceOfSatisfying(BillingSoapClient.BillingClientException.class,
                        ex -> assertThat(ex.faultCode()).isEqualTo("NOT_FOUND"));
    }
}
