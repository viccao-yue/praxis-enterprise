package com.kaiwu.praxis.admin.license;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

public final class LicenseDocument {
    private static final Pattern INSTALLATION = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern SEATS = Pattern.compile("(?:[1-9][0-9]{0,4}|100000)");
    private static final String[] KEYS = {
            "customer", "installationId", "notBefore", "notAfter", "seats", "sharing", "modelGateway", "sessionView"
    };

    private final String customer;
    private final String installationId;
    private final Instant notBefore;
    private final Instant notAfter;
    private final int seats;
    private final boolean sharing;
    private final boolean modelGateway;
    private final boolean sessionView;

    private LicenseDocument(String customer, String installationId, Instant notBefore, Instant notAfter,
                            int seats, boolean sharing, boolean modelGateway, boolean sessionView) {
        this.customer = customer;
        this.installationId = installationId;
        this.notBefore = notBefore;
        this.notAfter = notAfter;
        this.seats = seats;
        this.sharing = sharing;
        this.modelGateway = modelGateway;
        this.sessionView = sessionView;
    }

    public String customer() { return customer; }
    public String installationId() { return installationId; }
    public Instant notBefore() { return notBefore; }
    public Instant notAfter() { return notAfter; }
    public int seats() { return seats; }

    /** Exact decimal text. Leading zeros are rejected instead of being rewritten. */
    public static int parseSeats(String raw) {
        if (raw == null || !SEATS.matcher(raw).matches()) {
            throw new IllegalArgumentException("席位数必须是 1 到 100000 的整数，不能有前导零");
        }
        return Integer.parseInt(raw);
    }
    public boolean sharing() { return sharing; }
    public boolean modelGateway() { return modelGateway; }
    public boolean sessionView() { return sessionView; }

    public static String canonical(String customer, String installationId, Instant notBefore, Instant notAfter,
                                   int seats, boolean sharing, boolean modelGateway, boolean sessionView) {
        Instant start = notBefore.truncatedTo(ChronoUnit.SECONDS);
        Instant end = notAfter.truncatedTo(ChronoUnit.SECONDS);
        return "customer=" + customer + "\n"
                + "installationId=" + installationId + "\n"
                + "notBefore=" + start + "\n"
                + "notAfter=" + end + "\n"
                + "seats=" + seats + "\n"
                + "sharing=" + sharing + "\n"
                + "modelGateway=" + modelGateway + "\n"
                + "sessionView=" + sessionView;
    }

    public String canonicalPayload() {
        return canonical(customer, installationId, notBefore, notAfter, seats, sharing, modelGateway, sessionView);
    }

    public static LicenseDocument parse(String payload) {
        if (payload == null || payload.endsWith("\n")) throw LicenseTexts.bad(LicenseTexts.BAD_FORMAT);
        String[] lines = payload.split("\n", -1);
        if (lines.length != KEYS.length) throw LicenseTexts.bad(LicenseTexts.BAD_FORMAT);
        String[] values = new String[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) {
            int split = lines[i].indexOf('=');
            if (split <= 0 || !lines[i].substring(0, split).equals(KEYS[i])) throw LicenseTexts.bad(LicenseTexts.BAD_FORMAT);
            values[i] = lines[i].substring(split + 1);
        }
        String customer = values[0];
        if (customer.isEmpty() || customer.length() > 80 || !customer.equals(customer.trim()) || customer.indexOf('\r') >= 0)
            throw LicenseTexts.bad(LicenseTexts.BAD_FORMAT);
        if (!INSTALLATION.matcher(values[1]).matches()) throw LicenseTexts.bad(LicenseTexts.BAD_FORMAT);
        Instant notBefore;
        Instant notAfter;
        try {
            notBefore = Instant.parse(values[2]);
            notAfter = Instant.parse(values[3]);
        } catch (RuntimeException e) {
            throw LicenseTexts.bad(LicenseTexts.BAD_FORMAT);
        }
        if (notBefore.isAfter(notAfter)) throw LicenseTexts.bad(LicenseTexts.BAD_FORMAT);
        int seats;
        try {
            seats = parseSeats(values[4]);
        } catch (IllegalArgumentException e) {
            throw LicenseTexts.bad(LicenseTexts.BAD_FORMAT);
        }
        return new LicenseDocument(customer, values[1], notBefore, notAfter, seats,
                flag(values[5]), flag(values[6]), flag(values[7]));
    }

    private static boolean flag(String value) {
        if (value.equals("true")) return true;
        if (value.equals("false")) return false;
        throw LicenseTexts.bad(LicenseTexts.BAD_FORMAT);
    }
}
