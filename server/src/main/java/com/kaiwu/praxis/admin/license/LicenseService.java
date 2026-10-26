package com.kaiwu.praxis.admin.license;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.kaiwu.praxis.admin.DatabaseDialect;

@Service
public class LicenseService {
    public enum Feature { SHARING, MODEL_GATEWAY, SESSION_VIEW }

    public record Features(boolean sharing, boolean modelGateway, boolean sessionView) {}

    public record View(String installationId, String status, String customer, Instant notBefore, Instant notAfter,
                       Integer seats, int seatsUsed, Features features) {}

    private record Raw(String installationId, String payload, String signature) {}

    private record Snapshot(String installationId, String status, LicenseDocument document, int seatsUsed) {
        boolean usable() { return "active".equals(status) || "expiring".equals(status); }
    }

    private final JdbcTemplate db;
    private final DatabaseDialect dialect;
    private final LicenseVerifier verifier;
    private final boolean enforced;

    public LicenseService(JdbcTemplate db, DatabaseDialect dialect, LicenseVerifier verifier,
                          @Value("${workdsh.license.enforced:true}") boolean enforced) {
        this.db = db;
        this.dialect = dialect;
        this.verifier = verifier;
        this.enforced = enforced;
    }

    public void ensureInstallation(String organizationId) {
        Integer existing = db.queryForObject("select count(*) from deployment_licenses where organization_id=?", Integer.class, organizationId);
        if (existing != null && existing > 0) return;
        try {
            db.update("insert into deployment_licenses(organization_id,installation_id) values (?,?)", organizationId, UUID.randomUUID().toString());
        } catch (DuplicateKeyException ignored) {
            // Another request inserted this organization's installation id.
        }
    }

    public View view(String organizationId) {
        return present(read(organizationId));
    }

    public void replace(String organizationId, String payload, String signature) {
        ensureInstallation(organizationId);
        LicenseDocument document = verifier.verify(payload, signature);
        lockInstallation(organizationId);
        Raw raw = raw(organizationId);
        if (!document.installationId().equals(raw.installationId())) throw LicenseTexts.bad(LicenseTexts.BAD_INSTALLATION);
        int changed = db.update("""
                update deployment_licenses set payload=?,signature=?,customer_name=?,not_before=?,not_after=?,seats=?,sharing=?,model_gateway=?,session_view=?,uploaded_at=?
                where organization_id=? and ? >= (select count(*) from members where organization_id=? and active=true)
                """, payload, signature, document.customer(), Timestamp.from(document.notBefore()), Timestamp.from(document.notAfter()),
                document.seats(), document.sharing(), document.modelGateway(), document.sessionView(), Timestamp.from(Instant.now()),
                organizationId, document.seats(), organizationId);
        if (changed != 1) throw LicenseTexts.conflict(LicenseTexts.SEATS_LOW);
    }

    public boolean usable(String organizationId) {
        return !enforced || read(organizationId).usable();
    }

    public void requireUsable(String organizationId) {
        if (enforced && !read(organizationId).usable()) throw LicenseTexts.forbidden(LicenseTexts.INVALID);
    }

    public void requireSeat(String organizationId) {
        if (!enforced) return;
        Snapshot snapshot = lockedSnapshot(organizationId);
        if (snapshot.seatsUsed() >= snapshot.document.seats()) throw LicenseTexts.forbidden(LicenseTexts.SEATS);
    }

    /** Recount after the member row has changed. Rolls the caller transaction back when the license no longer covers everyone. */
    public void confirmSeats(String organizationId) {
        if (!enforced) return;
        Snapshot snapshot = lockedSnapshot(organizationId);
        if (snapshot.seatsUsed() > snapshot.document.seats()) throw LicenseTexts.forbidden(LicenseTexts.SEATS);
    }

    public void requireFeature(String organizationId, Feature feature) {
        if (!enforced) return;
        Snapshot snapshot = read(organizationId);
        if (!snapshot.usable()) throw LicenseTexts.forbidden(LicenseTexts.INVALID);
        if (overSeats(snapshot)) throw LicenseTexts.forbidden(LicenseTexts.SEATS_OVER);
        boolean enabled = switch (feature) {
            case SHARING -> snapshot.document.sharing();
            case MODEL_GATEWAY -> snapshot.document.modelGateway();
            case SESSION_VIEW -> snapshot.document.sessionView();
        };
        if (!enabled) throw LicenseTexts.forbidden(LicenseTexts.FEATURE);
    }

    public boolean limitsConcurrentSessions() { return enforced; }

    public void allowLogin(String organizationId, String role) {
        if (!enforced || "OWNER".equals(role)) return;
        Snapshot snapshot = read(organizationId);
        if (!snapshot.usable()) throw LicenseTexts.forbidden(LicenseTexts.INVALID);
        if (overSeats(snapshot)) throw LicenseTexts.forbidden(LicenseTexts.SEATS_OVER);
    }

    private static boolean overSeats(Snapshot snapshot) {
        return snapshot.document != null && snapshot.seatsUsed() > snapshot.document.seats();
    }

    private Snapshot read(String organizationId) {
        ensureInstallation(organizationId);
        Raw raw = raw(organizationId);
        int seatsUsed = used(organizationId);
        if (raw.payload() == null || raw.payload().isBlank()) return new Snapshot(raw.installationId(), "none", null, seatsUsed);
        try {
            LicenseDocument document = verifier.verify(raw.payload(), raw.signature());
            if (!document.installationId().equals(raw.installationId())) return new Snapshot(raw.installationId(), "invalid", null, seatsUsed);
            return new Snapshot(raw.installationId(), status(document, Instant.now()), document, seatsUsed);
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode().value() == 400) return new Snapshot(raw.installationId(), "invalid", null, seatsUsed);
            throw ex;
        }
    }

    private View present(Snapshot snapshot) {
        boolean open = snapshot.usable();
        LicenseDocument document = snapshot.document();
        Features features = open
                ? new Features(document.sharing(), document.modelGateway(), document.sessionView())
                : new Features(false, false, false);
        if (document == null) return new View(snapshot.installationId(), snapshot.status(), null, null, null, null, snapshot.seatsUsed(), features);
        return new View(snapshot.installationId(), snapshot.status(), document.customer(), document.notBefore(), document.notAfter(),
                document.seats(), snapshot.seatsUsed(), features);
    }

    private static String status(LicenseDocument document, Instant now) {
        if (now.isBefore(document.notBefore())) return "pending";
        if (now.isAfter(document.notAfter())) return "expired";
        if (now.plus(14, ChronoUnit.DAYS).isAfter(document.notAfter())) return "expiring";
        return "active";
    }

    private Snapshot lockedSnapshot(String organizationId) {
        ensureInstallation(organizationId);
        lockInstallation(organizationId);
        Snapshot snapshot = read(organizationId);
        if (!snapshot.usable()) throw LicenseTexts.forbidden(LicenseTexts.INVALID);
        return snapshot;
    }

    private void lockInstallation(String organizationId) {
        db.query(dialect.lock("select installation_id from deployment_licenses where organization_id=?"),
                (rs, row) -> rs.getString(1), organizationId);
    }

    private Raw raw(String organizationId) {
        return db.query("select installation_id,payload,signature from deployment_licenses where organization_id=?",
                (rs, row) -> new Raw(rs.getString(1), rs.getString(2), rs.getString(3)), organizationId).get(0);
    }

    private int used(String organizationId) {
        Integer count = db.queryForObject("select count(*) from members where organization_id=? and active=true", Integer.class, organizationId);
        return count == null ? 0 : count;
    }
}
