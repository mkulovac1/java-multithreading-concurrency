package ba.telco.ocs.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiEndpointsTest {

    @Autowired
    private MockMvc mockMvc;

    private ResultActions postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions putJson(String url, String body) throws Exception {
        return mockMvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions patchJson(String url, String body) throws Exception {
        return mockMvc.perform(patch(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void createSubscriber(String msisdn, String tariff, String balance) throws Exception {
        postJson("/api/subscribers", """
                {"msisdn":"%s","name":"Test","tariffCode":"%s","initialBalance":%s}""".formatted(msisdn, tariff, balance))
                .andExpect(status().isCreated());
    }

    private String read(ResultActions actions, String path) throws Exception {
        return JsonPath.read(actions.andReturn().getResponse().getContentAsString(), path);
    }

    @Test
    void tariffCatalogLifecycle() throws Exception {
        mockMvc.perform(get("/api/tariffs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='BASIC')]").exists());
        mockMvc.perform(get("/api/tariffs/BASIC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voiceBillingIncrementSeconds").value(60));
        mockMvc.perform(get("/api/tariffs/NOPE")).andExpect(status().isNotFound());

        String create = """
                {"code":"API_PLAN","name":"Api plan","voicePricePerMinute":0.1,"smsPrice":0.05,
                 "dataPricePerMb":0.01,"voiceBillingIncrementSeconds":30}""";
        postJson("/api/tariffs", create)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("API_PLAN"));
        postJson("/api/tariffs", create).andExpect(status().isConflict());

        putJson("/api/tariffs/API_PLAN", """
                {"name":"Renamed","voicePricePerMinute":0.2,"smsPrice":0.06,
                 "dataPricePerMb":0.02,"voiceBillingIncrementSeconds":1}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.voiceBillingIncrementSeconds").value(1));
        putJson("/api/tariffs/MISSING", """
                {"name":"X","voicePricePerMinute":0.2,"smsPrice":0.06,
                 "dataPricePerMb":0.02,"voiceBillingIncrementSeconds":1}""")
                .andExpect(status().isNotFound());
    }

    @Test
    void tariffPricesAreCapped() throws Exception {
        String template = """
                {"code":"%s","name":"Cap","voicePricePerMinute":%s,"smsPrice":0.05,
                 "dataPricePerMb":0.01,"voiceBillingIncrementSeconds":1}""";
        postJson("/api/tariffs", template.formatted("CAP_OK", "1000"))
                .andExpect(status().isCreated());
        postJson("/api/tariffs", template.formatted("CAP_BAD", "1000.01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.voicePricePerMinute").value("Price per minute can be at most 1000."));
        putJson("/api/tariffs/CAP_OK", """
                {"name":"Cap","voicePricePerMinute":1,"smsPrice":1000.01,
                 "dataPricePerMb":1,"voiceBillingIncrementSeconds":1}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.smsPrice").value("SMS price can be at most 1000."));
    }

    @Test
    void subscriberLifecycle() throws Exception {
        createSubscriber("38761800001", "BASIC", "5.00");
        postJson("/api/subscribers", """
                {"msisdn":"38761800001","name":"Dup","tariffCode":"BASIC","initialBalance":1}""")
                .andExpect(status().isConflict());
        postJson("/api/subscribers", """
                {"msisdn":"38761800002","name":"Bad","tariffCode":"NOPE","initialBalance":1}""")
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/subscribers").param("offset", "0").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/subscribers/38761800001"))
                .andExpect(jsonPath("$.tariffCode").value("BASIC"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        postJson("/api/subscribers/38761800001/top-up", "{\"amount\":2.50}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(7.5))
                .andExpect(jsonPath("$.availableBalance").value(7.5));

        patchJson("/api/subscribers/38761800001/tariff", "{\"tariffCode\":\"SMART\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tariffCode").value("SMART"));
        patchJson("/api/subscribers/38761800001/tariff", "{\"tariffCode\":\"NOPE\"}")
                .andExpect(status().isBadRequest());

        patchJson("/api/subscribers/38761800001/status", "{\"status\":\"SUSPENDED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        postJson("/api/charging/sessions", """
                {"msisdn":"38761800001","serviceType":"SMS","requestedUnits":1}""")
                .andExpect(status().isConflict());
        patchJson("/api/subscribers/38761800001/status", "{\"status\":\"ACTIVE\"}")
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void transfersMoveCreditAndRejectInvalidOnes() throws Exception {
        createSubscriber("38761810001", "BASIC", "10.00");
        createSubscriber("38761810002", "BASIC", "1.00");

        postJson("/api/subscribers/transfers", """
                {"fromMsisdn":"38761810001","toMsisdn":"38761810002","amount":4.00}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(4.0))
                .andExpect(jsonPath("$.from.balance").value(6.0))
                .andExpect(jsonPath("$.to.balance").value(5.0));
        postJson("/api/subscribers/transfers", """
                {"fromMsisdn":"38761810001","toMsisdn":"38761810001","amount":1.00}""")
                .andExpect(status().isBadRequest());
        postJson("/api/subscribers/transfers", """
                {"fromMsisdn":"38761810002","toMsisdn":"38761810001","amount":500.00}""")
                .andExpect(status().isPaymentRequired());

        patchJson("/api/subscribers/38761810002/status", "{\"status\":\"SUSPENDED\"}").andExpect(status().isOk());
        postJson("/api/subscribers/transfers", """
                {"fromMsisdn":"38761810001","toMsisdn":"38761810002","amount":1.00}""")
                .andExpect(status().isConflict());
    }

    @Test
    void chargingSessionEndpoints() throws Exception {
        createSubscriber("38761820001", "SMART", "1.00");
        createSubscriber("38761820002", "BASIC", "0");

        postJson("/api/charging/sessions", """
                {"msisdn":"38761820002","serviceType":"SMS","requestedUnits":1}""")
                .andExpect(status().isPaymentRequired());
        mockMvc.perform(get("/api/charging/sessions/unknown")).andExpect(status().isNotFound());

        String id = read(postJson("/api/charging/sessions", """
                {"msisdn":"38761820001","serviceType":"SMS","requestedUnits":2}""")
                .andExpect(status().isCreated()), "$.sessionId");
        mockMvc.perform(get("/api/charging/sessions/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        postJson("/api/charging/sessions/" + id + "/commit", "{\"usedUnits\":5}")
                .andExpect(status().isBadRequest());
        postJson("/api/charging/sessions/" + id + "/release", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
        mockMvc.perform(get("/api/subscribers/38761820001"))
                .andExpect(jsonPath("$.reservedAmount").value(0.0));
    }

    @Test
    void chargingSessionCanBeExtended() throws Exception {
        createSubscriber("38761825001", "SMART", "1.00");
        String id = read(postJson("/api/charging/sessions", """
                {"msisdn":"38761825001","serviceType":"SMS","requestedUnits":2}""")
                .andExpect(status().isCreated()), "$.sessionId");

        postJson("/api/charging/sessions/" + id + "/extend", "{\"additionalUnits\":3}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grantedUnits").value(5))
                .andExpect(jsonPath("$.reservedAmount").value(0.25))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        postJson("/api/charging/sessions/" + id + "/extend", "{\"additionalUnits\":0}")
                .andExpect(status().isBadRequest());
        postJson("/api/charging/sessions/unknown/extend", "{\"additionalUnits\":1}")
                .andExpect(status().isNotFound());
        postJson("/api/charging/sessions/" + id + "/extend", "{\"additionalUnits\":10000000}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grantedUnits").value(20));
        postJson("/api/charging/sessions/" + id + "/extend", "{\"additionalUnits\":1}")
                .andExpect(status().isPaymentRequired());
        postJson("/api/charging/sessions/" + id + "/commit", "{\"usedUnits\":5}")
                .andExpect(status().isOk());
        postJson("/api/charging/sessions/" + id + "/extend", "{\"additionalUnits\":1}")
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/subscribers/38761825001"))
                .andExpect(jsonPath("$.balance").value(0.75))
                .andExpect(jsonPath("$.reservedAmount").value(0.0));
    }

    @Test
    void cdrEndpointsExposeBatchesUsageAndRerating() throws Exception {
        createSubscriber("38761830001", "SMART", "0");
        String batchId = read(postJson("/api/cdrs/batches", """
                {"cdrs":[{"recordId":"r1","msisdn":"38761830001","serviceType":"DATA","units":2048,
                          "eventTime":"2026-10-21T10:00:00Z"}]}""")
                .andExpect(status().isAccepted()), "$.batchId");
        mockMvc.perform(get("/api/cdrs/batches/{id}", batchId).param("waitMillis", "5000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.rated").value(1));
        mockMvc.perform(get("/api/cdrs/batches/unknown")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/cdrs/batches/x").param("waitMillis", "abc")).andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/cdrs/usage/38761830001"))
                .andExpect(jsonPath("$.dataKb").value(2048));
        mockMvc.perform(get("/api/cdrs/usage/38760000999")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/cdrs/usage/top").param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.lessThanOrEqualTo(3)));
        mockMvc.perform(get("/api/cdrs/usage/top").param("limit", "0")).andExpect(status().isBadRequest());

        postJson("/api/cdrs/rerating-simulations", """
                {"tariffCode":"BASIC","cdrCount":2000,"threads":1,"seed":7}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultsMatch").value(true))
                .andExpect(jsonPath("$.cdrCount").value(2000));
        postJson("/api/cdrs/rerating-simulations", """
                {"tariffCode":"BASIC","cdrCount":2000,"threads":100000,"seed":7}""")
                .andExpect(status().isBadRequest());
    }

    @Test
    void campaignEndpoints() throws Exception {
        String listId = read(postJson("/api/campaigns", """
                {"name":"List","message":"Hello","recipients":["38761900001","38761900002"]}""")
                .andExpect(status().isAccepted()), "$.campaignId");
        String rangeId = read(postJson("/api/campaigns", """
                {"name":"Range","message":"Hello","numberRange":{"firstMsisdn":"38761900100","count":3}}""")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.totalRecipients").value(3)), "$.campaignId");

        postJson("/api/campaigns", """
                {"name":"Both","message":"Hello","recipients":["38761900001"],
                 "numberRange":{"firstMsisdn":"38761900100","count":3}}""")
                .andExpect(status().isBadRequest());
        postJson("/api/campaigns", """
                {"name":"Neither","message":"Hello"}""")
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/campaigns"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.campaignId=='%s')]".formatted(listId)).exists());
        mockMvc.perform(get("/api/campaigns/{id}", rangeId)).andExpect(status().isOk());
        mockMvc.perform(get("/api/campaigns/unknown")).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/campaigns/unknown")).andExpect(status().isNotFound());

        awaitCampaign(listId);
        mockMvc.perform(delete("/api/campaigns/{id}", listId)).andExpect(status().isConflict());

        String longId = read(postJson("/api/campaigns", """
                {"name":"Long","message":"Hello","numberRange":{"firstMsisdn":"38761910000","count":3000}}""")
                .andExpect(status().isAccepted()), "$.campaignId");
        mockMvc.perform(delete("/api/campaigns/{id}", longId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    private void awaitCampaign(String id) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            String status = read(mockMvc.perform(get("/api/campaigns/{id}", id)), "$.status");
            if (!"RUNNING".equals(status)) {
                return;
            }
            Thread.sleep(20);
        }
    }

    @Test
    void fraudAlertsCanBeListedAndAcknowledged() throws Exception {
        postJson("/api/fraud/alerts/acknowledge-all", "").andExpect(status().isOk());
        mockMvc.perform(post("/api/fraud/alerts/acknowledge-latest")).andExpect(status().isNotFound());

        createSubscriber("38761840001", "SMART", "0");
        StringBuilder cdrs = new StringBuilder("{\"cdrs\":[");
        for (int i = 0; i < 31; i++) {
            cdrs.append(i == 0 ? "" : ",").append("""
                    {"recordId":"f%d","msisdn":"38761840001","serviceType":"SMS","units":1,
                     "eventTime":"2026-10-21T10:00:10Z"}""".formatted(i));
        }
        cdrs.append("]}");
        postJson("/api/cdrs/batches?waitMillis=5000", cdrs.toString())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.rated").value(31));

        mockMvc.perform(get("/api/fraud/alerts").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].msisdn").value("38761840001"))
                .andExpect(jsonPath("$[0].description").value(containsString("Wangiri")));
        mockMvc.perform(post("/api/fraud/alerts/acknowledge-latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msisdn").value("38761840001"));
        mockMvc.perform(post("/api/fraud/alerts/acknowledge-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void absurdCdrUnitsAreRejectedInsteadOfCorruptingRevenue() throws Exception {
        postJson("/api/cdrs/batches", """
                {"cdrs":[{"recordId":"big","msisdn":"38761000001","serviceType":"VOICE","units":9223372036854775807,
                          "eventTime":"2026-10-21T10:00:00Z"}]}""")
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedAndInvalidRequestsAreRejected() throws Exception {
        postJson("/api/subscribers", "{not json").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Request body is not valid JSON."));
        mockMvc.perform(get("/api/subscribers").param("limit", "0")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid request parameters."));
        mockMvc.perform(get("/api/subscribers").param("limit", "abc")).andExpect(status().isBadRequest());
    }
}
