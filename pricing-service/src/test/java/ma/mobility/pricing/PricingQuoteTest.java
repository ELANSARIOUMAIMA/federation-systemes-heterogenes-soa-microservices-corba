package ma.mobility.pricing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pricing-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class PricingQuoteTest {
    @Autowired MockMvc mvc;

    @Test
    void multimodalQuoteUsesIntegerMinorUnitsAndDocumentedRounding() throws Exception {
        mvc.perform(post("/v1/pricing/quote").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"segments\":[{\"mode\":\"TRAIN\",\"priceMinor\":8000,\"seats\":1,\"currency\":\"MAD\"},"
                                + "{\"mode\":\"AIR\",\"priceMinor\":180000,\"seats\":1,\"currency\":\"MAD\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotalMinor").value(188000))
                .andExpect(jsonPath("$.feesMinor").value(400))
                .andExpect(jsonPath("$.discountMinor").value(9400))
                .andExpect(jsonPath("$.totalMinor").value(179000));
    }

    @Test
    void rejectsNegativeAmountsAndUnsupportedCurrencies() throws Exception {
        mvc.perform(post("/v1/pricing/quote").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"segments\":[{\"mode\":\"TRAIN\",\"priceMinor\":-1,\"seats\":1,\"currency\":\"MAD\"}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/v1/pricing/quote").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"segments\":[{\"mode\":\"TRAIN\",\"priceMinor\":100,\"seats\":1,\"currency\":\"EUR\"}]}"))
                .andExpect(status().isBadRequest());
    }
}
