package co.edu.corhuila.csp.concessions.adapter.in.http;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The liveness route of {@code concessions-service.yaml}, with its contract answer. */
class HealthControllerTest {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new HealthController()).build();

    @Test
    void livenessAnswersOk() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }
}
