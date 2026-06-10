package com.slackmsg.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests — run against real PostgreSQL + Redis (Docker).
 * Requires: docker-compose up -d
 * Run with: RUN_INTEGRATION_TESTS=true mvn test -Dtest=MessagingIntegrationTest
 */
/**
 * Integration tests — run against real PostgreSQL + Redis (Docker).
 * Skipped in unit test suite. Run manually:
 *   mvn test -Dtest=MessagingIntegrationTest -Dspring.profiles.active=default
 *
 * Requires: docker-compose up -d (PostgreSQL + Redis + MinIO)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class MessagingIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private String registerAndGetToken(String slug, String email) throws Exception {
        String body = String.format(
                "{\"tenantName\":\"%s\",\"tenantSlug\":\"%s\",\"email\":\"%s\",\"displayName\":\"Admin\",\"password\":\"password123\"}",
                slug, slug, email);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("token").asText();
    }

    private String login(String slug, String email) throws Exception {
        String body = String.format(
                "{\"tenantSlug\":\"%s\",\"email\":\"%s\",\"password\":\"password123\"}", slug, email);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("token").asText();
    }

    @Test
    void healthEndpoint_works() throws Exception {
        mockMvc.perform(get("/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UP"));
    }

    @Test
    void actuatorHealth_works() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void registerAndLogin_fullFlow() throws Exception {
        String token = registerAndGetToken("company1", "admin@company1.com");
        String token2 = login("company1", "admin@company1.com");
        org.junit.jupiter.api.Assertions.assertNotNull(token);
        org.junit.jupiter.api.Assertions.assertNotNull(token2);
    }

    @Test
    void duplicateRegistration_fails() throws Exception {
        registerAndGetToken("dup1", "admin@dup1.com");

        String body = "{\"tenantName\":\"dup1\",\"tenantSlug\":\"dup1\",\"email\":\"admin@dup1.com\",\"displayName\":\"Admin\",\"password\":\"password123\"}";
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/channels")).andExpect(status().isUnauthorized());
    }

    @Test
    void createChannelAndSendMessage_fullFlow() throws Exception {
        String token = registerAndGetToken("msgtest", "admin@msgtest.com");

        // Create channel
        MvcResult chResult = mockMvc.perform(post("/api/v1/channels")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"general\",\"type\":\"PUBLIC\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("general"))
                .andReturn();

        String channelId = objectMapper.readTree(chResult.getResponse().getContentAsString())
                .get("data").get("id").asText();

        // Send text message
        mockMvc.perform(post("/api/v1/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Hello integration test!\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.content").value("Hello integration test!"))
                .andExpect(jsonPath("$.data.messageType").value("TEXT"));

        // Send media-only message
        mockMvc.perform(post("/api/v1/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaUrl\":\"https://s3.example.com/img.png\",\"mediaType\":\"image/png\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.messageType").value("MEDIA"));

        // Get history — should have 2 messages
        mockMvc.perform(get("/api/v1/channels/" + channelId + "/messages?limit=10")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void sendEmptyMessage_fails() throws Exception {
        String token = registerAndGetToken("emptytest", "admin@emptytest.com");

        MvcResult chResult = mockMvc.perform(post("/api/v1/channels")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ch\",\"type\":\"PUBLIC\"}"))
                .andExpect(status().isCreated()).andReturn();

        String chId = objectMapper.readTree(chResult.getResponse().getContentAsString())
                .get("data").get("id").asText();

        // Empty message (no content, no media) should fail
        mockMvc.perform(post("/api/v1/channels/" + chId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void crossTenantIsolation_cannotAccessOtherChannel() throws Exception {
        String token1 = registerAndGetToken("tenant-a", "admin@a.com");
        String token2 = registerAndGetToken("tenant-b", "admin@b.com");

        // Tenant A creates channel
        MvcResult chResult = mockMvc.perform(post("/api/v1/channels")
                        .header("Authorization", "Bearer " + token1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"secret\",\"type\":\"PRIVATE\"}"))
                .andExpect(status().isCreated()).andReturn();

        String channelId = objectMapper.readTree(chResult.getResponse().getContentAsString())
                .get("data").get("id").asText();

        // Tenant B tries to send message to Tenant A's channel → forbidden
        mockMvc.perform(post("/api/v1/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + token2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Trying to snoop!\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void addUserAndMembership_fullFlow() throws Exception {
        String token = registerAndGetToken("teamtest", "admin@teamtest.com");

        // Add user
        MvcResult userResult = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bob@teamtest.com\",\"displayName\":\"Bob\",\"password\":\"pass123\"}"))
                .andExpect(status().isCreated()).andReturn();

        String userId2 = objectMapper.readTree(userResult.getResponse().getContentAsString())
                .get("data").get("userId").asText();

        // Create channel
        MvcResult chResult = mockMvc.perform(post("/api/v1/channels")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"team\",\"type\":\"PUBLIC\"}"))
                .andExpect(status().isCreated()).andReturn();

        String channelId = objectMapper.readTree(chResult.getResponse().getContentAsString())
                .get("data").get("id").asText();

        // Add Bob to channel
        mockMvc.perform(post("/api/v1/channels/" + channelId + "/members")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[\"" + userId2 + "\"]}"))
                .andExpect(status().isOk());

        // List members — should have 2
        mockMvc.perform(get("/api/v1/channels/" + channelId + "/members")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void dmCreation_idempotent() throws Exception {
        String token = registerAndGetToken("dmtest", "admin@dmtest.com");

        // Add second user
        MvcResult userResult = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@dmtest.com\",\"displayName\":\"Alice\",\"password\":\"pass123\"}"))
                .andExpect(status().isCreated()).andReturn();

        String aliceId = objectMapper.readTree(userResult.getResponse().getContentAsString())
                .get("data").get("userId").asText();

        // Create DM
        MvcResult dm1 = mockMvc.perform(post("/api/v1/dm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + aliceId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("DM"))
                .andReturn();

        String dmId1 = objectMapper.readTree(dm1.getResponse().getContentAsString())
                .get("data").get("id").asText();

        // Create DM again — should return SAME channel
        MvcResult dm2 = mockMvc.perform(post("/api/v1/dm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + aliceId + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String dmId2 = objectMapper.readTree(dm2.getResponse().getContentAsString())
                .get("data").get("id").asText();

        org.junit.jupiter.api.Assertions.assertEquals(dmId1, dmId2, "DM should be idempotent");
    }
}
