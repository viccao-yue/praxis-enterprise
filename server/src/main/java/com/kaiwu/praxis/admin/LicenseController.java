package com.kaiwu.praxis.admin;

import java.util.HashSet;
import java.util.Set;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.kaiwu.praxis.admin.license.LicenseService;
import com.kaiwu.praxis.admin.license.LicenseTexts;

@RestController
public class LicenseController {
    private final AuthService auth;
    private final LicenseService licenses;
    private final MemberController members;

    public LicenseController(AuthService auth, LicenseService licenses, MemberController members) {
        this.auth = auth;
        this.licenses = licenses;
        this.members = members;
    }

    @GetMapping("/api/admin/license")
    public LicenseService.View view(@RequestHeader(value = "Authorization", required = false) String bearer) {
        var actor = auth.actor(bearer);
        auth.requireReady(actor);
        auth.requireAdmin(actor);
        return licenses.view(actor.organizationId());
    }

    @PutMapping("/api/admin/license")
    @Transactional
    public LicenseService.View replace(@RequestHeader(value = "Authorization", required = false) String bearer,
                                       @RequestBody JsonNode body) {
        var actor = auth.actor(bearer);
        auth.requireReady(actor);
        auth.requireAdmin(actor);
        if (!"OWNER".equals(actor.role())) throw LicenseTexts.forbidden(LicenseTexts.OWNER_ONLY);
        if (body == null || !body.isObject()) throw LicenseTexts.bad(LicenseTexts.BAD_FORMAT);
        Set<String> names = new HashSet<>();
        body.fieldNames().forEachRemaining(names::add);
        JsonNode version = body.get("v");
        if (!names.equals(Set.of("v", "payload", "signature")) || version == null || !version.isIntegralNumber()
                || !version.canConvertToLong() || version.longValue() != 1L
                || !body.get("payload").isTextual() || !body.get("signature").isTextual()) {
            throw LicenseTexts.bad(LicenseTexts.BAD_FORMAT);
        }
        licenses.replace(actor.organizationId(), body.get("payload").asText(), body.get("signature").asText());
        members.audit(actor, "license.uploaded", actor.organizationId());
        return licenses.view(actor.organizationId());
    }
}
