package hu.mclsaat.legacy.billing.ws;

import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.ws.config.annotation.EnableWs;
import org.springframework.ws.config.annotation.WsConfigurerAdapter;
import org.springframework.ws.transport.http.MessageDispatcherServlet;
import org.springframework.ws.wsdl.wsdl11.DefaultWsdl11Definition;
import org.springframework.xml.xsd.SimpleXsdSchema;
import org.springframework.xml.xsd.XsdSchema;

/**
 * Contract-first SOAP over HTTP.
 *
 * <p>The XSD in {@code src/main/resources/xsd/billing-v1.xsd} is the contract. The WSDL is
 * generated from it and served at {@code /ws/billing.wsdl}; the endpoint itself lives at
 * {@code /ws}. Nothing here is RESTful, and that is the point: one of the three subsystems has
 * to be reachable only through a WSDL, the way a twenty-year-old billing system would be.
 */
@EnableWs
@Configuration
public class WebServiceConfig extends WsConfigurerAdapter {

    @Bean
    public ServletRegistrationBean<MessageDispatcherServlet> messageDispatcherServlet(
            ApplicationContext applicationContext) {
        MessageDispatcherServlet servlet = new MessageDispatcherServlet();
        servlet.setApplicationContext(applicationContext);
        servlet.setTransformWsdlLocations(true);
        return new ServletRegistrationBean<>(servlet, "/ws/*");
    }

    @Bean(name = "billing")
    public DefaultWsdl11Definition billingWsdl(XsdSchema billingSchema) {
        DefaultWsdl11Definition wsdl = new DefaultWsdl11Definition();
        wsdl.setPortTypeName("BillingPort");
        wsdl.setLocationUri("/ws");
        wsdl.setTargetNamespace("http://mclsaat.hu/legacy/billing/v1");
        wsdl.setSchema(billingSchema);
        return wsdl;
    }

    @Bean
    public XsdSchema billingSchema() {
        return new SimpleXsdSchema(new ClassPathResource("xsd/billing-v1.xsd"));
    }
}
