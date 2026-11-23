package ma.mobility.airbridge.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.transport.http.HttpUrlConnectionMessageSender;

/** Client SOAP vers le partenaire aérien (classes JAXB générées depuis air.xsd). */
@Configuration
public class SoapClientConfig {

    @Bean
    public Jaxb2Marshaller airMarshaller() {
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setContextPath("ma.mobility.airbridge.ws");
        return marshaller;
    }

    @Bean
    public WebServiceTemplate airWebServiceTemplate(Jaxb2Marshaller marshaller,
                                                    @Value("${air.soap.url}") String airUrl,
                                                    @Value("${air.soap.connect-timeout-ms:2000}") long connectTimeoutMs,
                                                    @Value("${air.soap.read-timeout-ms:3000}") long readTimeoutMs) {
        WebServiceTemplate template = new WebServiceTemplate();
        template.setMarshaller(marshaller);
        template.setUnmarshaller(marshaller);
        template.setDefaultUri(airUrl);
        // P3 : sans timeout, un partenaire bloqué figeait le thread HTTP du bridge indéfiniment.
        HttpUrlConnectionMessageSender sender = new HttpUrlConnectionMessageSender();
        sender.setConnectionTimeout(Duration.ofMillis(connectTimeoutMs));
        sender.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        template.setMessageSender(sender);
        return template;
    }
}
