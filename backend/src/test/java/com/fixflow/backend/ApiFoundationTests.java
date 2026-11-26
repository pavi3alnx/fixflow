package com.fixflow.backend;

import com.fixflow.backend.category.*;
import com.fixflow.backend.common.api.*;
import com.fixflow.backend.request.RequestDetails;
import jakarta.validation.Valid;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = CategoryController.class)
@org.springframework.test.context.ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false) // FF-03 MVC contracts; real security is covered by AuthIntegrationTests.
@Import({ApiExceptionHandler.class, ApiFoundationTests.ContractController.class})
class ApiFoundationTests {
    @Autowired MockMvc mvc;
    @MockitoBean CategoryService service;

    @Test
    void responseUsesExplicitPublicDto() throws Exception {
        when(service.get(1)).thenReturn(new CategoryResponse(1L, "ELECTRICAL", "Electrical", null, true));
        mvc.perform(get("/api/categories/1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("ELECTRICAL"))
            .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }
    @Test
    void invalidDtoAndMalformedInputUseProblemDetails() throws Exception {
        mvc.perform(post("/ff03-contract").contentType(MediaType.APPLICATION_JSON).content("""
            {"title":" ","description":" ","buildingId":0,"roomId":-1,"categoryId":null}
            """))
            .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(400)).andExpect(jsonPath("$.title").value("Bad Request"))
            .andExpect(jsonPath("$.errors").isArray());
        mvc.perform(post("/ff03-contract").contentType(MediaType.APPLICATION_JSON).content("{bad"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Request validation failed."));
        verifyNoInteractions(service);
    }
    @Test
    void acceptsBuildingOnlyDto() throws Exception {
        mvc.perform(post("/ff03-contract").contentType(MediaType.APPLICATION_JSON).content("""
            {"title":"Fault","description":"Details","buildingId":1,"categoryId":1,"suggestedPriority":"LOW"}
            """)) .andExpect(status().isOk());
    }
    @Test
    void missingResourcesAndIntegrityConflictsAreSanitized() throws Exception {
        when(service.get(404)).thenThrow(new ResourceNotFoundException());
        mvc.perform(get("/api/categories/404")).andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.detail").value("The requested resource was not found."));
        when(service.get(409)).thenThrow(new DataIntegrityViolationException("secret SQL password_hash"));
        mvc.perform(get("/api/categories/409")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.detail").value("The operation conflicts with existing data or database constraints."));
    }
    @RestController
    static class ContractController {
        @PostMapping("/ff03-contract")
        void validate(@RequestBody @Valid RequestDetails request) { }
    }
}
