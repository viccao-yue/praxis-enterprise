package com.kaiwu.praxis.admin;

import jakarta.servlet.http.Cookie;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaiwu.praxis.admin.license.LicenseDocument;
import com.kaiwu.praxis.admin.license.LicenseService;
import com.kaiwu.praxis.admin.license.LicenseTexts;
import com.kaiwu.praxis.admin.license.LicenseVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:license-control;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "workdsh.bootstrap.admin-email=license-owner@example.test",
        "workdsh.bootstrap.admin-password=LicenseControlPassword123!",
        "workdsh.license.enforced=true",
        "workdsh.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(LicenseControlTest.TestKeys.class)
class LicenseControlTest {
    private static final String PASSWORD = "LicenseControlPassword123!";
    private static final KeyPair PAIR = keys();

    @TestConfiguration
    static class TestKeys {
        @Bean
        @Primary
        PublicKey testLicensePublicKey() { return PAIR.getPublic(); }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired AuthService auth;
    @Autowired LicenseService licenses;
    @Autowired PlatformTransactionManager transactions;

    @Test @Transactional void unlicensedMemberCannotLogin() throws Exception {
        String owner = login("license-owner@example.test", PASSWORD);
        String org = auth.actor("Bearer " + owner).organizationId();
        insertMember(org, "member-login@example.test", "MEMBER");
        JsonNode denied = call(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "member-login@example.test", "password", PASSWORD))), 403);
        assertEquals(LicenseTexts.INVALID, denied.path("detail").asText());
        call(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "license-owner@example.test", "password", PASSWORD))), 200);
    }

    @Test @Transactional void unlicensedCreateIsRejectedAndStatusIsNone() throws Exception {
        String owner = login("license-owner@example.test", PASSWORD);
        JsonNode denied = call(bearer(post("/api/admin/members"), owner).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "new@example.test", "displayName", "新人", "role", "MEMBER"))), 403);
        assertEquals(LicenseTexts.INVALID, denied.path("detail").asText());
        JsonNode license = call(get("/api/admin/license").header("Authorization", "Bearer " + owner), 200);
        assertEquals("none", license.path("status").asText());
        assertEquals(true, license.path("seatsUsed").asInt() >= 1);
    }

    @Test @Transactional void seatLimitBlocksTheThirdActiveMember() throws Exception {
        String owner = login("license-owner@example.test", PASSWORD);
        upload(owner, license(installation(owner), Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(20, ChronoUnit.DAYS), 2, true, true, true), 200);
        call(bearer(post("/api/admin/members"), owner).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "seat-a@example.test", "displayName", "甲", "role", "MEMBER"))), 201);
        JsonNode full = call(bearer(post("/api/admin/members"), owner).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "seat-b@example.test", "displayName", "乙", "role", "MEMBER"))), 403);
        assertEquals(LicenseTexts.SEATS, full.path("detail").asText());
        assertEquals("active", call(get("/api/admin/license").header("Authorization", "Bearer " + owner), 200).path("status").asText());
    }

    @Test @Transactional void reactivatingAMemberNeedsAFreeSeat() throws Exception {
        String owner = login("license-owner@example.test", PASSWORD);
        upload(owner, license(installation(owner), Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(20, ChronoUnit.DAYS), 2, true, true, true), 200);
        JsonNode created = call(bearer(post("/api/admin/members"), owner).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "seat-stop@example.test", "displayName", "甲", "role", "MEMBER"))), 201);
        String id = created.path("member").path("id").asText();
        JsonNode disabled = call(bearer(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/admin/members/" + id), owner)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("active", false, "expectedRevision", 1))), 200);
        call(bearer(post("/api/admin/members"), owner).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "seat-next@example.test", "displayName", "乙", "role", "MEMBER"))), 201);
        JsonNode blocked = call(bearer(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/admin/members/" + id), owner)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("active", true, "expectedRevision", disabled.path("revision").asInt()))), 403);
        assertEquals(LicenseTexts.SEATS, blocked.path("detail").asText());
    }

    @Test @Transactional void tamperedSignatureDoesNotReplaceTheStoredLicense() throws Exception {
        String owner = login("license-owner@example.test", PASSWORD);
        String body = license(installation(owner), Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(20, ChronoUnit.DAYS), 5, true, true, true);
        upload(owner, body, 200);
        String payload = json.readTree(body).path("payload").asText();
        String signature = json.readTree(body).path("signature").asText();
        String tampered = json.writeValueAsString(Map.of("v", 1, "payload", payload, "signature", "A" + signature.substring(1)));
        JsonNode denied = upload(owner, tampered, 400);
        assertEquals(LicenseTexts.BAD_SIGNATURE, denied.path("detail").asText());
        assertEquals(payload, db.queryForObject("select payload from deployment_licenses where installation_id=?", String.class, installation(owner)));
    }

    @Test @Transactional void licenseForAnotherInstallationIsRejected() throws Exception {
        String owner = login("license-owner@example.test", PASSWORD);
        String body = license(UUID.randomUUID().toString(), Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(20, ChronoUnit.DAYS), 5, true, true, true);
        JsonNode denied = upload(owner, body, 400);
        assertEquals(LicenseTexts.BAD_INSTALLATION, denied.path("detail").asText());
    }

    @Test @Transactional void licenseCannotShrinkBelowActiveMembers() throws Exception {
        String owner = login("license-owner@example.test", PASSWORD);
        String id = installation(owner);
        upload(owner, license(id, Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(20, ChronoUnit.DAYS), 5, true, true, true), 200);
        call(bearer(post("/api/admin/members"), owner).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "shrink@example.test", "displayName", "甲", "role", "MEMBER"))), 201);
        JsonNode denied = upload(owner, license(id, Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(20, ChronoUnit.DAYS), 1, true, true, true), 409);
        assertEquals(LicenseTexts.SEATS_LOW, denied.path("detail").asText());
    }

    @Test @Transactional void expiredLicenseStopsAnExistingMemberSession() throws Exception {
        String owner = login("license-owner@example.test", PASSWORD);
        String id = installation(owner);
        Instant start = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Instant end = Instant.now().plus(20, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        upload(owner, license(id, start, end, 5, true, true, true), 200);
        JsonNode created = call(bearer(post("/api/admin/members"), owner).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "session-member@example.test", "displayName", "甲", "role", "MEMBER"))), 201);
        String member = login("session-member@example.test", created.path("temporaryPassword").asText());
        upload(owner, license(id, start, Instant.now().minus(1, ChronoUnit.MINUTES), 5, true, true, true), 200);
        JsonNode denied = call(get("/api/auth/me").header("Authorization", "Bearer " + member), 403);
        assertEquals(LicenseTexts.INVALID, denied.path("detail").asText());
        call(get("/api/auth/me").header("Authorization", "Bearer " + owner), 200);
        call(get("/api/admin/license").header("Authorization", "Bearer " + owner), 200);
    }

    @Test @Transactional void existingShareCanBeRetriedAfterSharingIsDisabled() throws Exception {
        String owner = login("license-owner@example.test", PASSWORD);
        String org = auth.actor("Bearer " + owner).organizationId();
        String recipient = insertMember(org, "handoff-retry@example.test", "MEMBER");
        String id = installation(owner);
        Instant start = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Instant end = Instant.now().plus(20, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        upload(owner, license(id, start, end, 5, true, true, true), 200);
        String key = UUID.randomUUID().toString();
        JsonNode created = handoff(owner, recipient, "同一摘要", key, 201);
        upload(owner, license(id, start, end, 5, false, true, true), 200);
        JsonNode retried = handoff(owner, recipient, "同一摘要", key, 201);
        assertEquals(created.path("id").asText(), retried.path("id").asText());
        JsonNode denied = handoff(owner, recipient, "同一摘要", UUID.randomUUID().toString(), 403);
        assertEquals(LicenseTexts.FEATURE, denied.path("detail").asText());
    }

    @Test @Transactional void materialsCannotBeAddedAfterSharingIsDisabled() throws Exception {
        String owner = login("license-owner@example.test", PASSWORD);
        String org = auth.actor("Bearer " + owner).organizationId();
        String recipient = insertMember(org, "material-block@example.test", "MEMBER");
        String id = installation(owner);
        Instant start = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Instant end = Instant.now().plus(20, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        upload(owner, license(id, start, end, 5, true, true, true), 200);
        String key = UUID.randomUUID().toString();
        JsonNode created = handoff(owner, recipient, "材料摘要", key, 201);
        upload(owner, license(id, start, end, 5, false, true, true), 200);
        JsonNode denied = call(bearer(post("/api/collaboration/materials"), owner).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("recipientId", recipient, "requestKey", key, "summary", "材料摘要", "context", "正文", "files", java.util.List.of()))), 403);
        assertEquals(LicenseTexts.FEATURE, denied.path("detail").asText());
        assertEquals(0, db.queryForObject("select count(*) from collaboration_materials where handoff_id=?", Integer.class, created.path("id").asText()));
    }

    @Test @Transactional void modelCallsRequireTheGatewayFeature() throws Exception {
        String owner = login("license-owner@example.test", PASSWORD);
        upload(owner, license(installation(owner), Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(20, ChronoUnit.DAYS), 5, true, false, true), 200);
        String key = "abcdefghijklmnopqrstuvwxyz012345";
        call(bearer(put("/api/admin/model-gateway"), owner).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("accessKey", key))), 200);
        JsonNode denied = call(get("/api/model-gateway/v1/models").header("Authorization", "Bearer " + key), 403);
        assertEquals(LicenseTexts.FEATURE, denied.path("detail").asText());
    }

    @Test @Transactional void sessionReviewRequiresItsFeature() throws Exception {
        String owner = login("license-owner@example.test", PASSWORD);
        upload(owner, license(installation(owner), Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(20, ChronoUnit.DAYS), 5, true, true, false), 200);
        JsonNode denied = call(get("/api/admin/sessions").header("Authorization", "Bearer " + owner), 403);
        assertEquals(LicenseTexts.FEATURE, denied.path("detail").asText());
    }

    @Test void concurrentCreatesStopAtTheSeatLimit() throws Exception {
        String owner = login("license-owner@example.test", PASSWORD);
        String org = auth.actor("Bearer " + owner).organizationId();
        upload(owner, license(installation(owner), Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(40, ChronoUnit.DAYS), 2, true, true, true), 200);
        TransactionTemplate tx = new TransactionTemplate(transactions);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> attempts = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String email = "race-" + i + "@example.test";
                attempts.add(pool.submit(() -> occupySeat(tx, org, email)));
            }
            int created = 0;
            for (Future<Integer> attempt : attempts) {
                if (attempt.get(30, TimeUnit.SECONDS) == 201) created++;
            }
            Integer active = db.queryForObject("select count(*) from members where organization_id=? and active=true", Integer.class, org);
            assertEquals(1, created);
            assertEquals(2, active);
        } finally {
            pool.shutdownNow();
            db.update("delete from members where organization_id=? and email like 'race-%@example.test'", org);
            db.update("""
                    update deployment_licenses set payload=null,signature=null,customer_name=null,not_before=null,not_after=null,seats=null,sharing=null,model_gateway=null,session_view=null,uploaded_at=null
                    where organization_id=?
                    """, org);
        }
    }

    @Test @Transactional void overflowMembersCannotLoginUntilTheOwnerFreesASeat() throws Exception {
        String owner = login("license-owner@example.test", PASSWORD);
        String org = auth.actor("Bearer " + owner).organizationId();
        upload(owner, license(installation(owner), Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(40, ChronoUnit.DAYS), 2, true, true, true), 200);
        insertMember(org, "kept@example.test", "MEMBER");
        String extra = insertMember(org, "extra@example.test", "MEMBER");
        JsonNode denied = call(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "kept@example.test", "password", PASSWORD))), 403);
        assertEquals(LicenseTexts.SEATS_OVER, denied.path("detail").asText());
        String key = "abcdefghijklmnopqrstuvwxyz012345";
        call(bearer(put("/api/admin/model-gateway"), owner).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("accessKey", key))), 200);
        JsonNode gateway = call(get("/api/model-gateway/v1/models").header("Authorization", "Bearer " + key), 403);
        assertEquals(LicenseTexts.SEATS_OVER, gateway.path("detail").asText());
        String ownerAgain = login("license-owner@example.test", PASSWORD);
        call(bearer(patch("/api/admin/members/" + extra), ownerAgain).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("active", false, "expectedRevision", 1))), 200);
        login("kept@example.test", PASSWORD);
    }

    @Test @Transactional void memberLoginReplacesThePreviousSession() throws Exception {
        String owner = login("license-owner@example.test", PASSWORD);
        String org = auth.actor("Bearer " + owner).organizationId();
        upload(owner, license(installation(owner), Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(40, ChronoUnit.DAYS), 2, true, true, true), 200);
        String memberId = insertMember(org, "kept@example.test", "MEMBER");
        String first = login("kept@example.test", PASSWORD);
        String second = login("kept@example.test", PASSWORD);
        call(get("/api/auth/me").header("Authorization", "Bearer " + first), 401);
        call(get("/api/auth/me").header("Authorization", "Bearer " + second), 200);
        assertEquals(1, db.queryForObject("select count(*) from auth_sessions where member_id=?", Integer.class, memberId));
    }

    @Test @Transactional void consoleOperatorsAreNotConstrainedByTheLicense() throws Exception {
        String owner = login("license-owner@example.test", PASSWORD);
        String org = auth.actor("Bearer " + owner).organizationId();
        insertMember(org, "console-admin@example.test", "ADMIN");
        insertMember(org, "console-member@example.test", "MEMBER");
        JsonNode desktop = call(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "console-admin@example.test", "password", PASSWORD))), 403);
        assertEquals(LicenseTexts.INVALID, desktop.path("detail").asText());
        String console = browserLogin("console-admin@example.test");
        call(get("/api/admin/members").cookie(new Cookie("workdsh-admin-session", console)), 200);
        call(get("/api/admin/sessions").cookie(new Cookie("workdsh-admin-session", console)), 200);
        assertEmployeeSideBlocked(console, LicenseTexts.INVALID);
        call(post("/api/admin/members").cookie(new Cookie("workdsh-admin-session", console)).header("Origin", "http://127.0.0.1:11520")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "from-console@example.test", "displayName", "控制台", "role", "MEMBER"))), 201);
        JsonNode member = call(post("/api/auth/browser-login").header("Origin", "http://127.0.0.1:11520")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "console-member@example.test", "password", PASSWORD))), 403);
        assertEquals(LicenseTexts.INVALID, member.path("detail").asText());
        String installation = installation(owner);
        upload(owner, license(installation, Instant.now().plus(2, ChronoUnit.DAYS), Instant.now().plus(40, ChronoUnit.DAYS), 5, true, true, true), 200);
        assertEmployeeSideBlocked(console, LicenseTexts.INVALID);
        upload(owner, license(installation, Instant.now().minus(40, ChronoUnit.DAYS), Instant.now().minus(1, ChronoUnit.DAYS), 5, true, true, true), 200);
        assertEmployeeSideBlocked(console, LicenseTexts.INVALID);
        upload(owner, license(installation, Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(40, ChronoUnit.DAYS), 5, true, true, true), 200);
        db.update("update deployment_licenses set signature=? where organization_id=?", "aaaa", org);
        assertEmployeeSideBlocked(console, LicenseTexts.INVALID);
        upload(owner, license(installation, Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(10, ChronoUnit.DAYS), 5, true, false, false), 200);
        call(get("/api/admin/sessions").cookie(new Cookie("workdsh-admin-session", console)), 200);
        call(get("/api/collaboration/inbox").cookie(new Cookie("workdsh-admin-session", console)), 200);
        String key = "abcdefghijklmnopqrstuvwxyz012345";
        call(bearer(put("/api/admin/model-gateway"), owner).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("accessKey", key))), 200);
        JsonNode gateway = call(get("/api/model-gateway/v1/models").header("Authorization", "Bearer " + key), 403);
        assertEquals(LicenseTexts.FEATURE, gateway.path("detail").asText());
        String employee = login("console-admin@example.test", PASSWORD);
        String consoleAgain = browserLogin("console-admin@example.test");
        call(get("/api/auth/me").header("Authorization", "Bearer " + employee), 200);
        insertMember(org, "extra-seat@example.test", "MEMBER");
        insertMember(org, "extra-seat-2@example.test", "MEMBER");
        assertEmployeeSideBlocked(consoleAgain, LicenseTexts.SEATS_OVER);
        call(get("/api/admin/members").cookie(new Cookie("workdsh-admin-session", consoleAgain)), 200);
    }

    @Test @Transactional void futureLicenseIsPendingAndDoesNotAdmitMembers() throws Exception {
        String owner = login("license-owner@example.test", PASSWORD);
        String org = auth.actor("Bearer " + owner).organizationId();
        insertMember(org, "pending-member@example.test", "MEMBER");
        upload(owner, license(installation(owner), Instant.now().plus(2, ChronoUnit.DAYS), Instant.now().plus(40, ChronoUnit.DAYS), 5, true, true, true), 200);
        JsonNode license = call(get("/api/admin/license").header("Authorization", "Bearer " + owner), 200);
        assertEquals("pending", license.path("status").asText());
        assertEquals(false, license.path("features").path("sharing").asBoolean());
        JsonNode denied = call(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "pending-member@example.test", "password", PASSWORD))), 403);
        assertEquals(LicenseTexts.INVALID, denied.path("detail").asText());
    }

    @Test @Transactional void versionMustBeExactlyOne() throws Exception {
        String owner = login("license-owner@example.test", PASSWORD);
        String forged = json.writeValueAsString(Map.of("v", 4294967297L, "payload", "customer=x", "signature", "abcd"));
        JsonNode denied = upload(owner, forged, 400);
        assertEquals(LicenseTexts.BAD_FORMAT, denied.path("detail").asText());
    }

    @Test @Transactional void onlyTheOwnerCanReplaceTheLicense() throws Exception {
        String owner = login("license-owner@example.test", PASSWORD);
        String org = auth.actor("Bearer " + owner).organizationId();
        upload(owner, license(installation(owner), Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(20, ChronoUnit.DAYS), 5, true, true, true), 200);
        insertMember(org, "license-admin@example.test", "ADMIN");
        insertMember(org, "license-member@example.test", "MEMBER");
        String admin = login("license-admin@example.test", PASSWORD);
        String member = login("license-member@example.test", PASSWORD);
        call(get("/api/admin/license").header("Authorization", "Bearer " + admin), 200);
        JsonNode denied = upload(admin, license(installation(owner), Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(20, ChronoUnit.DAYS), 5, true, true, true), 403);
        assertEquals(LicenseTexts.OWNER_ONLY, denied.path("detail").asText());
        JsonNode memberDenied = call(get("/api/admin/license").header("Authorization", "Bearer " + member), 403);
        assertEquals("Admin role required", memberDenied.path("detail").asText());
    }

    private int occupySeat(TransactionTemplate tx, String organizationId, String email) {
        try {
            tx.executeWithoutResult(status -> {
                licenses.requireSeat(organizationId);
                db.update("insert into members(id,organization_id,email,display_name,role,password_hash,must_change_password,active,revision) values (?,?,?,?,?,?,true,true,1)",
                        UUID.randomUUID().toString(), organizationId, email, "并发", "MEMBER", auth.encode(PASSWORD));
                licenses.confirmSeats(organizationId);
            });
            return 201;
        } catch (RuntimeException ex) {
            Throwable cause = ex;
            while (cause != null) {
                if (cause instanceof ResponseStatusException response) return response.getStatusCode().value();
                cause = cause.getCause();
            }
            throw ex;
        }
    }

    private String installation(String owner) throws Exception {
        return call(get("/api/admin/license").header("Authorization", "Bearer " + owner), 200).path("installationId").asText();
    }

    private JsonNode upload(String owner, String body, int status) throws Exception {
        return call(bearer(put("/api/admin/license"), owner).contentType(MediaType.APPLICATION_JSON).content(body), status);
    }

    private JsonNode handoff(String owner, String recipient, String summary, String key, int status) throws Exception {
        return call(bearer(post("/api/collaboration/handoffs"), owner).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("recipientId", recipient, "summary", summary, "requestKey", key))), status);
    }

    private String license(String installationId, Instant notBefore, Instant notAfter, int seats,
                           boolean sharing, boolean modelGateway, boolean sessionView) throws Exception {
        String payload = LicenseDocument.canonical("开物示例", installationId, notBefore, notAfter, seats, sharing, modelGateway, sessionView);
        return json.writeValueAsString(Map.of("v", 1, "payload", payload, "signature", LicenseVerifier.sign(PAIR.getPrivate(), payload)));
    }

    private String insertMember(String organizationId, String email, String role) {
        String id = UUID.randomUUID().toString();
        db.update("insert into members(id,organization_id,email,display_name,role,password_hash,must_change_password,active,revision) values (?,?,?,?,?,?,false,true,1)",
                id, organizationId, email, email, role, auth.encode(PASSWORD));
        return id;
    }

    private void assertEmployeeSideBlocked(String console, String detail) throws Exception {
        call(get("/api/admin/members").cookie(new Cookie("workdsh-admin-session", console)), 200);
        JsonNode inbox = call(get("/api/collaboration/inbox").cookie(new Cookie("workdsh-admin-session", console)), 403);
        assertEquals(detail, inbox.path("detail").asText());
        JsonNode denied = call(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "console-admin@example.test", "password", PASSWORD))), 403);
        assertEquals(detail, denied.path("detail").asText());
    }

    private String browserLogin(String email) throws Exception {
        var result = mvc.perform(post("/api/auth/browser-login").header("Origin", "http://127.0.0.1:11520")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD)))).andReturn();
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        String cookie = result.getResponse().getHeader("Set-Cookie");
        int end = cookie.indexOf(';');
        return cookie.substring("workdsh-admin-session=".length(), end);
    }

    private String login(String email, String password) throws Exception {
        return call(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password))), 200).path("token").asText();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder bearer(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, int expected) throws Exception {
        var result = mvc.perform(request).andReturn();
        assertEquals(expected, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        String body = result.getResponse().getContentAsString();
        return body.isBlank() ? json.nullNode() : json.readTree(body);
    }

    private static KeyPair keys() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
