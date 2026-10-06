package ru.hse.search.metadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MetadataControllerTest {
    private MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new MetadataController()).build();
    }

    @Test void returnsKnownReferences() throws Exception {
        mvc.perform(get("/api/metadata").param("categories", "JAVA", "SPRING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.JAVA.url").value("https://docs.oracle.com/en/java/javase/21/"))
                .andExpect(jsonPath("$.SPRING.title").value("Spring Framework"));
    }

    @Test void failureAndRecoveryModesWork() throws Exception {
        mvc.perform(post("/admin/mode").param("mode", "error")).andExpect(status().isOk());
        mvc.perform(get("/api/metadata").param("categories", "JAVA")).andExpect(status().isServiceUnavailable());
        mvc.perform(post("/admin/mode").param("mode", "invalid")).andExpect(status().isOk());
        mvc.perform(get("/api/metadata").param("categories", "JAVA"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().string("{broken"));
        mvc.perform(post("/admin/mode").param("mode", "normal")).andExpect(status().isOk());
        mvc.perform(get("/api/metadata").param("categories", "JAVA")).andExpect(status().isOk());
    }

    @Test void rejectsUnknownModeAndCategory() throws Exception {
        mvc.perform(post("/admin/mode").param("mode", "unknown")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/metadata").param("categories", "UNKNOWN")).andExpect(status().isBadRequest());
    }

    @Test void delayedModeStillReturnsValidReferences() throws Exception {
        mvc.perform(post("/admin/mode").param("mode", "delayed")).andExpect(status().isOk());
        mvc.perform(get("/api/metadata").param("categories", "JAVA"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.JAVA.title").exists());
    }
}
