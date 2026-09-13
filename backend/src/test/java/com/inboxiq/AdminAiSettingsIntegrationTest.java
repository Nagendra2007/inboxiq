package com.inboxiq;

import com.inboxiq.ai.AiConfig;
import com.inboxiq.ai.AiProvider;
import com.inboxiq.entity.AiSettings;
import com.inboxiq.entity.User;
import com.inboxiq.repository.AiSettingsRepository;
import com.inboxiq.repository.UserRepository;
import com.inboxiq.service.AiSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.admin-emails=admin@example.com",
        "app.ai.provider=openrouter",
        "app.ai.base-url=",
        "app.ai.api-key=env-key-0000-6789",
        "app.ai.model=openai/gpt-4o-mini"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminAiSettingsIntegrationTest {

    private static final String ADMIN = "admin@example.com";
    private static final String MEMBER = "member@example.com";

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private AiSettingsRepository aiSettingsRepository;
    @Autowired private AiSettingsService aiSettingsService;

    @BeforeEach
    void setUp() {
        aiSettingsService.reset();
        for (String email : new String[]{ADMIN, MEMBER}) {
            if (users.findByEmail(email).isEmpty()) users.save(new User(email, email));
        }
    }

    private static RequestPostProcessor as(String email) {
        return oidcLogin().idToken(token -> token.claim("email", email));
    }

    private static String body(String provider, String model, String apiKey) {
        return "{\"provider\":\"" + provider + "\",\"model\":\"" + model + "\""
                + (apiKey == null ? "" : ",\"apiKey\":\"" + apiKey + "\"") + "}";
    }

    @Test
    void onlyTheAdministratorCanSeeOrChangeAiSettings() throws Exception {
        mvc.perform(get("/api/admin/ai-settings").with(as(MEMBER))).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/ai-settings").with(as(MEMBER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body("OPENAI", "gpt-4o-mini", "sk-member-key-1234")))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/auth/me").with(as(ADMIN))).andExpect(jsonPath("$.admin").value(true));
        mvc.perform(get("/api/auth/me").with(as(MEMBER))).andExpect(jsonPath("$.admin").value(false));
    }

    @Test
    void defaultsComeFromTheEnvironment() throws Exception {
        mvc.perform(get("/api/admin/ai-settings").with(as(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("ENVIRONMENT"))
                .andExpect(jsonPath("$.provider").value("OPENROUTER"))
                .andExpect(jsonPath("$.model").value("openai/gpt-4o-mini"))
                .andExpect(jsonPath("$.apiKeyHint").value("…6789"))
                .andExpect(content().string(not(containsString("env-key-0000-6789"))));
    }

    @Test
    void adminSwitchesProviderModelAndKeyForEveryone() throws Exception {
        mvc.perform(put("/api/admin/ai-settings").with(as(ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body("GEMINI", "gemini-2.5-flash", "AIza-secret-key-9876")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("APP"))
                .andExpect(jsonPath("$.provider").value("GEMINI"))
                .andExpect(jsonPath("$.model").value("gemini-2.5-flash"))
                .andExpect(jsonPath("$.apiKeyHint").value("…9876"))
                .andExpect(jsonPath("$.usingEnvironmentKey").value(false))
                .andExpect(jsonPath("$.updatedBy").value(ADMIN))
                .andExpect(content().string(not(containsString("AIza-secret-key-9876"))));

        AiConfig current = aiSettingsService.current();
        assertThat(current.provider()).isEqualTo(AiProvider.GEMINI);
        assertThat(current.baseUrl()).isEqualTo(AiProvider.GEMINI.baseUrl());
        assertThat(current.apiKey()).isEqualTo("AIza-secret-key-9876");
        assertThat(current.model()).isEqualTo("gemini-2.5-flash");

        AiSettings stored = aiSettingsRepository.findById(AiSettings.SINGLETON_ID).orElseThrow();
        assertThat(stored.getEncryptedApiKey()).isNotBlank().doesNotContain("AIza-secret-key-9876");
    }

    @Test
    void changingOnlyTheModelKeepsTheEnvironmentKey() throws Exception {
        mvc.perform(put("/api/admin/ai-settings").with(as(ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body("OPENROUTER", "nvidia/nemotron-3-super-120b-a12b:free", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usingEnvironmentKey").value(true));

        assertThat(aiSettingsService.current().apiKey()).isEqualTo("env-key-0000-6789");
        assertThat(aiSettingsService.current().model()).isEqualTo("nvidia/nemotron-3-super-120b-a12b:free");
    }

    @Test
    void aBlankKeyKeepsTheSavedKeyForTheSameProvider() throws Exception {
        mvc.perform(put("/api/admin/ai-settings").with(as(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body("GEMINI", "gemini-2.5-flash", "AIza-first-key-1111")))
                .andExpect(status().isOk());
        mvc.perform(put("/api/admin/ai-settings").with(as(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body("GEMINI", "gemini-2.5-pro", "")))
                .andExpect(status().isOk());

        assertThat(aiSettingsService.current().apiKey()).isEqualTo("AIza-first-key-1111");
        assertThat(aiSettingsService.current().model()).isEqualTo("gemini-2.5-pro");
    }

    @Test
    void switchingProviderRequiresAKeyForIt() throws Exception {
        mvc.perform(put("/api/admin/ai-settings").with(as(ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body("OPENAI", "gpt-4o-mini", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Enter an API key for OpenAI."));
        mvc.perform(put("/api/admin/ai-settings").with(as(ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body("NOT_A_PROVIDER", "x", "k")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resetReturnsToTheEnvironment() throws Exception {
        mvc.perform(put("/api/admin/ai-settings").with(as(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body("GROQ", "llama-3.3-70b-versatile", "gsk-groq-key-5555")))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/admin/ai-settings").with(as(ADMIN)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("ENVIRONMENT"));
        assertThat(aiSettingsService.current().provider()).isEqualTo(AiProvider.OPENROUTER);
    }

    @Test
    void changesRequireTheCsrfToken() throws Exception {
        mvc.perform(put("/api/admin/ai-settings").with(as(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(body("OPENROUTER", "openai/gpt-4o-mini", null)))
                .andExpect(status().isForbidden());
    }
}
