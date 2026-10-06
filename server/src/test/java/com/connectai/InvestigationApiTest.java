package com.connectai;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "connectai.seed-demo-data=false")
@AutoConfigureMockMvc
class InvestigationApiTest {

    @Autowired
    private MockMvc mvc;
    @Test
    void createInvestigation_runsTheAgentAndReturnsGroundedEvidence() throws Exception {
        mvc.perform(post("/api/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Why did payment fail?\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.evidence.length()").value(4))
                .andExpect(jsonPath("$.evidence[0].sourceType").value("SPLUNK"));
    }

    @Test
    void blankQuestion_returns400() throws Exception {
        mvc.perform(post("/api/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownInvestigation_returns404() throws Exception {
        mvc.perform(get("/api/investigations/00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isNotFound());
    }
}
