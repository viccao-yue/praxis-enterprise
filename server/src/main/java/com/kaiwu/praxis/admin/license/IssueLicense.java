package com.kaiwu.praxis.admin.license;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Signs one license file. Not a server bean. */
public final class IssueLicense {
    private IssueLicense() {}

    public static void main(String[] args) {
        try {
            Map<String, String> options = options(args);
            String customer = value(options, "customer", "WORKDSH_LICENSE_CUSTOMER");
            String installationId = options.get("installation-id");
            String notAfter = options.get("not-after");
            String seats = options.get("seats");
            if (customer == null || installationId == null || notAfter == null || seats == null) {
                throw new IllegalArgumentException("缺少 customer、installation-id、not-after 或 seats");
            }
            String keyPath = System.getenv("WORKDSH_LICENSE_PRIVATE_KEY");
            if (keyPath == null || keyPath.isBlank()) throw new IllegalArgumentException("未设置 WORKDSH_LICENSE_PRIVATE_KEY");
            Instant start = options.containsKey("not-before")
                    ? Instant.parse(options.get("not-before")).truncatedTo(ChronoUnit.SECONDS)
                    : Instant.now().truncatedTo(ChronoUnit.SECONDS);
            String payload = LicenseDocument.canonical(customer, installationId, start, Instant.parse(notAfter),
                    LicenseDocument.parseSeats(seats), featureFlag(options.get("sharing")), featureFlag(options.get("model-gateway")), featureFlag(options.get("session-view")));
            LicenseDocument.parse(payload);
            String signature = LicenseVerifier.sign(PemKeys.privateKey(Files.readString(Path.of(keyPath))), payload);
            ObjectNode file = new ObjectMapper().createObjectNode();
            file.put("v", 1);
            file.put("payload", payload);
            file.put("signature", signature);
            System.out.println(file.toString());
        } catch (Exception e) {
            System.err.println(e.getMessage() == null ? "无法签发许可证" : e.getMessage());
            System.exit(1);
        }
    }

    static boolean featureFlag(String value) {
        if (value == null || value.equals("false")) return false;
        if (value.equals("true")) return true;
        throw new IllegalArgumentException("开关只能是 true 或 false");
    }

    private static String value(Map<String, String> options, String name, String env) {
        String fromEnv = System.getenv(env);
        return options.containsKey(name) ? options.get(name) : fromEnv;
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) throw new IllegalArgumentException("参数不完整");
            options.put(args[i].substring(2), args[++i]);
        }
        return options;
    }
}
