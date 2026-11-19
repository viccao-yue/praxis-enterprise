package com.kaiwu.praxis.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import com.kaiwu.praxis.admin.license.LicenseService;

@Service
public class AuthService {
    private final JdbcTemplate db;
    private final LicenseService licenses;
    private final DatabaseDialect dialect;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final SecureRandom random = new SecureRandom();
    private static final long SESSION_HOURS = 12;
    private static final long DESKTOP_LIMIT_HOURS = 72;

    public AuthService(JdbcTemplate db, LicenseService licenses, DatabaseDialect dialect) {
        this.db = db;
        this.licenses = licenses;
        this.dialect = dialect;
    }

    public record Actor(String id, String organizationId, String email, String displayName,
                        String role, boolean mustChangePassword, boolean console) {
        public boolean admin() { return role.equals("ADMIN") || role.equals("OWNER"); }
        /** Owner or admin signed in through the enterprise console, not the desktop. */
        public boolean consoleOperator() { return console && admin(); }
    }
    public record Login(String token, Actor member, Long expiresAt, Long renewalLimit) {}
    public record Renewal(long expiresAt) {}

    @Bean
    ApplicationRunner bootstrap(
            @Value("${workdsh.bootstrap.admin-email:}") String email,
            @Value("${workdsh.bootstrap.admin-password:}") String password,
            @Value("${workdsh.bootstrap.organization-name:开物Praxis}") String orgName,
            @Value("${workdsh.bootstrap.require-password-change:false}") boolean requirePasswordChange) {
        return args -> {
            try {
                db.execute("alter table auth_sessions add column channel varchar(16) not null default 'desktop'");
            } catch (DataAccessException ignored) {
                // Existing databases already have the console/desktop channel column.
            }
            ensureDesktopRenewalStorage();
            Long count = db.queryForObject("select count(*) from organizations", Long.class);
            if (count != null && count > 0) return;
            // Allow current delivery default Sun55kong! (10 chars); keep a modest floor.
            if (email.isBlank() || password.length() < 8) {
                throw new IllegalStateException("Set WORKDSH_BOOTSTRAP_ADMIN_EMAIL and WORKDSH_BOOTSTRAP_ADMIN_PASSWORD (at least 8 characters) before first start");
            }
            String orgId = UUID.randomUUID().toString();
            String adminId = UUID.randomUUID().toString();
            db.update("insert into organizations(id,name) values (?,?)", orgId, orgName);
            db.update("insert into members(id,organization_id,email,display_name,role,password_hash,must_change_password,active,revision) values (?,?,?,?,?,?,?,true,1)",
                    adminId, orgId, email.trim().toLowerCase(), "管理员", "OWNER", passwords.encode(password), requirePasswordChange);
            licenses.ensureInstallation(orgId);
        };
    }

    private void ensureDesktopRenewalStorage() {
        var probe = "select desktop_renewal_limit from auth_sessions where 1=0";
        try {
            db.queryForList(probe);
        } catch (DataAccessException missingColumn) {
            if (dialect.postgres()) {
                throw new IllegalStateException("Apply PostgreSQL migration V010 before starting Admin", missingColumn);
            }
            try {
                db.execute("alter table auth_sessions add column desktop_renewal_limit timestamp null");
            } catch (DataAccessException migrationFailure) {
                try { db.queryForList(probe); }
                catch (DataAccessException unavailable) { throw migrationFailure; }
            }
        }
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Login login(String email, String password) {
        return login(email, password, "desktop");
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Login login(String email, String password, String channel) {
        if (!email.contains("@")) {
            var matches = db.queryForList("select m.email from member_profiles p join members m on m.id=p.member_id where p.username=? and m.active=true", String.class, email.trim().toLowerCase());
            if (matches.size()==1) email=matches.get(0);
        }
        var users = db.query("select id,organization_id,email,display_name,role,password_hash,must_change_password,active from members where email=?",
                (rs, n) -> Map.<String,Object>of(
                        "id", rs.getString("id"), "org", rs.getString("organization_id"),
                        "email", rs.getString("email"), "name", rs.getString("display_name"),
                        "role", rs.getString("role"), "hash", rs.getString("password_hash"),
                        "change", rs.getBoolean("must_change_password"), "active", rs.getBoolean("active")),
                email.trim().toLowerCase());
        if (users.size() != 1 || !Boolean.TRUE.equals(users.get(0).get("active"))
                || !passwords.matches(password, (String) users.get(0).get("hash"))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        var u = users.get(0);
        boolean console = "console".equals(channel);
        if (!consoleOperator(console, (String) u.get("role"))) licenses.allowLogin((String) u.get("org"), (String) u.get("role"));
        if (licenses.limitsConcurrentSessions()) {
            db.query(dialect.lock("select id from members where id=?"), (rs, row) -> rs.getString(1), u.get("id"));
            db.update("delete from auth_sessions where member_id=? and channel=?", u.get("id"), console ? "console" : "desktop");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        // MySQL delivery schemas store timestamps with second precision.
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var expires = now.plus(SESSION_HOURS, ChronoUnit.HOURS);
        var limit = console ? null : now.plus(DESKTOP_LIMIT_HOURS, ChronoUnit.HOURS);
        db.update("insert into auth_sessions(token_hash,member_id,expires_at,channel,desktop_renewal_limit) values (?,?,?,?,?)",
                sha256(token), u.get("id"), Timestamp.from(expires), console ? "console" : "desktop", limit == null ? null : Timestamp.from(limit));
        return new Login(token, new Actor((String) u.get("id"), (String) u.get("org"),
                (String) u.get("email"), (String) u.get("name"), (String) u.get("role"),
                (Boolean) u.get("change"), console), console ? null : expires.toEpochMilli(), limit == null ? null : limit.toEpochMilli());
    }

    @Transactional
    public Renewal renewDesktop(String bearer) {
        var actor = actor(bearer);
        requireReady(actor);
        if (actor.console()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Desktop session required");
        var now = Instant.now();
        var key = sha256(bearer.substring(7));
        var current = Timestamp.from(now);
        var next = Timestamp.from(now.truncatedTo(ChronoUnit.SECONDS).plus(SESSION_HOURS, ChronoUnit.HOURS));
        // Never recreate a removed session or extend an expired one. The bounded
        // update also handles simultaneous renewals without moving expiry backwards.
        db.update("""
                update auth_sessions set expires_at=case when desktop_renewal_limit<? then desktop_renewal_limit else ? end
                where token_hash=? and member_id=? and channel='desktop'
                  and expires_at>? and expires_at<=? and desktop_renewal_limit>expires_at
                  and exists (select 1 from members m where m.id=? and m.active=true
                    and m.must_change_password=false and m.organization_id=? and m.role=?)
                """, next, next, key, actor.id(), current, Timestamp.from(now.plus(4, ChronoUnit.HOURS)),
                actor.id(), actor.organizationId(), actor.role());
        var result = db.query("""
                select s.expires_at from auth_sessions s join members m on m.id=s.member_id
                where s.token_hash=? and s.member_id=? and s.channel='desktop' and s.expires_at>?
                  and s.desktop_renewal_limit is not null and m.active=true and m.must_change_password=false
                  and m.organization_id=? and m.role=?
                """, (rs, i) -> rs.getTimestamp(1).toInstant().toEpochMilli(),
                key, actor.id(), current, actor.organizationId(), actor.role());
        if (result.size() != 1) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Desktop session not renewable");
        return new Renewal(result.get(0));
    }

    public Actor actor(String bearer) {
        if (bearer == null || !bearer.startsWith("Bearer ") || bearer.length() > 512) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login required");
        }
        String token = bearer.substring(7);
        var found = db.query("""
                select m.id,m.organization_id,m.email,m.display_name,m.role,m.must_change_password,s.channel
                from auth_sessions s join members m on m.id=s.member_id
                where s.token_hash=? and s.expires_at>? and m.active=true
                """, (rs, n) -> new Actor(rs.getString("id"), rs.getString("organization_id"),
                rs.getString("email"), rs.getString("display_name"), rs.getString("role"),
                rs.getBoolean("must_change_password"), "console".equals(rs.getString("channel"))), sha256(token), java.sql.Timestamp.from(Instant.now()));
        if (found.size() != 1) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login expired");
        Actor actor = found.get(0);
        if (!consolePage(actor)) licenses.allowLogin(actor.organizationId(), actor.role());
        return actor;
    }

    /** License exemption follows the console pages, not the console cookie. */
    private static boolean consolePage(Actor actor) {
        if (!actor.consoleOperator()) return false;
        var attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) return false;
        String path = servlet.getRequest().getRequestURI();
        return path.startsWith("/api/admin/") || path.equals("/api/auth/me")
                || path.equals("/api/auth/logout") || path.equals("/api/auth/change-password");
    }

    private static boolean consoleOperator(boolean console, String role) {
        return console && ("OWNER".equals(role) || "ADMIN".equals(role));
    }

    public void requireAdmin(Actor actor) {
        if (!actor.admin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
    }

    public void requireReady(Actor actor) {
        if (actor.mustChangePassword()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Password change required");
        }
    }

    public void changePassword(Actor actor, String current, String replacement) {
        if (replacement == null || replacement.length() < 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password must have at least 12 characters");
        }
        String hash = db.queryForObject("select password_hash from members where id=?", String.class, actor.id());
        if (!passwords.matches(current, hash)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Current password is incorrect");
        }
        db.update("update members set password_hash=?,must_change_password=false,revision=revision+1 where id=?",
                passwords.encode(replacement), actor.id());
        db.update("delete from auth_sessions where member_id=?", actor.id());
    }

    public String newTemporaryPassword() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String encode(String password) { return passwords.encode(password); }

    public void logout(String bearer) {
        if (bearer != null && bearer.startsWith("Bearer ")) {
            db.update("delete from auth_sessions where token_hash=?", sha256(bearer.substring(7)));
        }
    }

    String authenticatedSessionKey(String bearer) { actor(bearer); return sha256(bearer.substring(7)); }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
