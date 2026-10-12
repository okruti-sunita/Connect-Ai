package com.connectai;

import com.connectai.domain.Investigation;
import com.connectai.repository.InvestigationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers GET /api/investigations, the endpoint behind the Investigations list screen.
 * Each test tags its rows with a unique marker and searches for it, so rows created by
 * other tests in the shared in-memory database never affect the assertions.
 */
@SpringBootTest(properties = {"connectai.seed-demo-data=false", "connectai.tools.mode=fake"})
@AutoConfigureMockMvc
class InvestigationListApiTest {

    private static final String LIST = "/api/investigations";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private InvestigationRepository repository;

    private void save(String question, boolean failed, boolean archived) {
        Investigation investigation = new Investigation(question);
        if (failed) {
            investigation.markFailed("test failure");
        } else {
            investigation.markAnswered("test answer");
        }
        if (archived) {
            investigation.archive();
        }
        repository.save(investigation);
    }

    @Test
    void list_returnsTheFieldsTheScreenNeeds() throws Exception {
        String marker = "list-fields-" + System.nanoTime();
        mvc.perform(post(LIST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Why did payment fail? " + marker + "\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get(LIST).param("q", marker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").isString())
                .andExpect(jsonPath("$.items[0].title", containsString(marker)))
                .andExpect(jsonPath("$.items[0].status").value("ANSWERED"))
                .andExpect(jsonPath("$.items[0].archived").value(false))
                .andExpect(jsonPath("$.items[0].evidenceCount").value(4))
                .andExpect(jsonPath("$.items[0].sources.length()").value(4))
                .andExpect(jsonPath("$.items[0].sources[0]").value("GITHUB"))
                .andExpect(jsonPath("$.items[0].createdAt").isString());
    }

    @Test
    void list_filtersByStatusAndArchived() throws Exception {
        String marker = "list-filter-" + System.nanoTime();
        save(marker + " answered", false, false);
        save(marker + " failed", true, false);
        save(marker + " archived", false, true);

        mvc.perform(get(LIST).param("q", marker))
                .andExpect(jsonPath("$.totalElements").value(3));

        mvc.perform(get(LIST).param("q", marker).param("status", "FAILED"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].status").value("FAILED"));

        mvc.perform(get(LIST).param("q", marker).param("archived", "true"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].archived").value(true));

        mvc.perform(get(LIST).param("q", marker).param("archived", "false"))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void list_searchIgnoresCaseAndTreatsPercentAsPlainText() throws Exception {
        String marker = "list-search-" + System.nanoTime();
        save(marker + " Disk 100% full", false, false);
        save(marker + " Disk 100X full", false, false);

        mvc.perform(get(LIST).param("q", marker.toUpperCase()))
                .andExpect(jsonPath("$.totalElements").value(2));

        mvc.perform(get(LIST).param("q", marker + " disk 100% full"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void list_pagesAndCapsThePageSize() throws Exception {
        String marker = "list-page-" + System.nanoTime();
        for (int i = 0; i < 3; i++) {
            save(marker + " #" + i, false, false);
        }

        mvc.perform(get(LIST).param("q", marker).param("size", "2"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));

        mvc.perform(get(LIST).param("q", marker).param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.items.length()").value(1));

        mvc.perform(get(LIST).param("size", "1000"))
                .andExpect(jsonPath("$.size").value(50));
    }

    @Test
    void list_rejectsAnUnknownStatus() throws Exception {
        mvc.perform(get(LIST).param("status", "BANANA"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void detail_includesTitleAndArchivedFlag() throws Exception {
        String marker = "detail-fields-" + System.nanoTime();
        // POST answers 201 with a Location header: /api/investigations/{id}
        String location = mvc.perform(post(LIST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Why did payment fail? " + marker + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");

        mvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", containsString(marker)))
                .andExpect(jsonPath("$.archived").value(false));
    }
}
