package ma.mobility.airbridge.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.core.WebServiceTemplate;

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
                                                    @Value("${air.soap.url}") String airUrl) {
        WebServiceTemplate template = new WebServiceTemplate();
        template.setMarshaller(marshaller);
        template.setUnmarshaller(marshaller);
        template.setDefaultUri(airUrl);
        return template;
    }
}