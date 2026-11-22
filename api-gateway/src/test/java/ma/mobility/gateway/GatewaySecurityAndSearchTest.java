package ma.mobility.gateway;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import ma.mobility.canonical.Offer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@SpringBootTest(properties = {"gateway.auth.username=test-user", "gateway.auth.password=test-password"})
@AutoConfigureMockMvc
class GatewaySecurityAndSearchTest {
    @Autowired MockMvc mvc;
    @MockBean RestTemplate http;

    @Test
    void requiresBasicAuthenticationAndReturnsAvailableSourcesWhenAirIsDown() throws Exception {
        mvc.perform(get("/api/v1/search").param("origin", "Casablanca")
                        .param("destination", "Rabat").param("date", "2026-11-15"))
                .andExpect(status().isUnauthorized());
        when(http.getForObject(anyString(), eq(Offer[].class))).thenAnswer(invocation -> {
            String uri = invocation.getArgument(0, String.class);
            if (uri.contains("localhost:8083")) throw new ResourceAccessException("test outage");
            Offer offer = new Offer();
            offer.offerId = uri.contains("localhost:8082") ? "RAIL:TEST" : "CAR:TEST";
            offer.departure = "2026-11-15T10:00:00";
            offer.priceMinor = 1000;
            return new Offer[] { offer };
        });
        mvc.perform(get("/api/v1/search").param("origin", "Casablanca")
                        .param("destination", "Rabat").param("date", "2026-11-15")
                        .header("Authorization", basicAuth()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Partial-Results", "air"))
                .andExpect(jsonPath("$.length()").value(2));
    }

    private String basicAuth() {
        return "Basic " + Base64.getEncoder().encodeToString(
                "test-user:test-password".getBytes(StandardCharsets.UTF_8));
    }
}
