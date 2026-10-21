package ba.telco.ocs.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void openApiDocumentIsPublished() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Telco Charging Engine API"))
                .andExpect(jsonPath("$.paths['/api/charging/sessions']").exists());
    }

    @Test
    void fullPrepaidCallFlow() throws Exception {
        mockMvc.perform(post("/api/subscribers").contentType(MediaType.APPLICATION_JSON).content("""
                        {"msisdn":"38761555001","name":"Test","tariffCode":"BASIC","initialBalance":1.00}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.availableBalance").value(1.0));

        MvcResult reserved = mockMvc.perform(post("/api/charging/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"38761555001","serviceType":"VOICE","requestedUnits":600}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.grantedUnits").value(480))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        String sessionId = com.jayway.jsonpath.JsonPath.read(reserved.getResponse().getContentAsString(), "$.sessionId");

        mockMvc.perform(post("/api/charging/sessions/{id}/commit", sessionId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usedUnits\":90}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andExpect(jsonPath("$.chargedAmount").value(0.24));

        mockMvc.perform(post("/api/charging/sessions/{id}/release", sessionId))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/subscribers/38761555001"))
                .andExpect(jsonPath("$.balance").value(0.76))
                .andExpect(jsonPath("$.reservedAmount").value(0.0));
    }

    @Test
    void cdrBatchIsRatedAndUsageAggregated() throws Exception {
        mockMvc.perform(post("/api/subscribers").contentType(MediaType.APPLICATION_JSON).content("""
                        {"msisdn":"38761555002","name":"Cdr","tariffCode":"SMART","initialBalance":0}"""))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/cdrs/batches").param("waitMillis", "5000").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cdrs":[
                                  {"recordId":"1","msisdn":"38761555002","serviceType":"VOICE","units":120,"eventTime":"2026-10-21T10:00:00Z"},
                                  {"recordId":"2","msisdn":"38761555002","serviceType":"SMS","units":2,"eventTime":"2026-10-21T10:01:00Z"},
                                  {"recordId":"3","msisdn":"38769999999","serviceType":"SMS","units":1,"eventTime":"2026-10-21T10:02:00Z"}
                                ]}"""))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.rated").value(2))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.revenue").value(0.26));

        mockMvc.perform(get("/api/cdrs/usage/38761555002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voiceSeconds").value(120))
                .andExpect(jsonPath("$.smsCount").value(2));
    }

    @Test
    void validationErrorsAreLocalized() throws Exception {
        mockMvc.perform(post("/api/subscribers").contentType(MediaType.APPLICATION_JSON).content("""
                        {"msisdn":"123","name":"","tariffCode":"BASIC","initialBalance":-1}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.msisdn").value(containsString("387")))
                .andExpect(jsonPath("$.errors.initialBalance").value("Initial balance cannot be negative."));
    }

    @Test
    void unknownSubscriberGives404WithMessage() throws Exception {
        mockMvc.perform(get("/api/subscribers/38760000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Subscriber with number 38760000000 does not exist."));
    }

    @Test
    void monitoringExposesPipelineAndJvmStats() throws Exception {
        mockMvc.perform(get("/api/monitoring/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cdrPipeline.running").value(true))
                .andExpect(jsonPath("$.jvm.availableProcessors").isNumber());
    }
}
