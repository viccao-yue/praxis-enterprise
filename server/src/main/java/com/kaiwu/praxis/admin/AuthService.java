package com.kaiwu.praxis.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
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
    public record Login(String token, Actor member) {}

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
        db.update("insert into auth_sessions(token_hash,member_id,expires_at,channel) values (?,?,?,?)",
                sha256(token), u.get("id"), java.sql.Timestamp.from(Instant.now().plus(12, ChronoUnit.HOURS)), console ? "console" : "desktop");
        return new Login(token, new Actor((String) u.get("id"), (String) u.get("org"),
                (String) u.get("email"), (String) u.get("name"), (String) u.get("role"),
                (Boolean) u.get("change"), console));
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
