package com.kaiwu.praxis.admin.license;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IssueLicenseTest {
    @Test void omittedAndFalseStayClosed() {
        assertEquals(false, IssueLicense.featureFlag(null));
        assertEquals(false, IssueLicense.featureFlag("false"));
    }

    @Test void packagedPublicKeyVerifiesALicenseFromTheSigningKey() throws Exception {
        String path = System.getenv("WORKDSH_LICENSE_PRIVATE_KEY");
        Assumptions.assumeTrue(path != null && !path.isBlank(), "未提供签发私钥，跳过安装包公钥配对");
        PrivateKey privateKey = PemKeys.privateKey(Files.readString(Path.of(path)));
        String payload = LicenseDocument.canonical("配对检查", "550e8400-e29b-41d4-a716-446655440000",
                Instant.now().truncatedTo(ChronoUnit.SECONDS), Instant.now().plus(30, ChronoUnit.DAYS), 1, false, false, false);
        String signature = LicenseVerifier.sign(privateKey, payload);
        String pem = new ClassPathResource("license-public.pem").getContentAsString(StandardCharsets.UTF_8);
        LicenseDocument document = new LicenseVerifier(PemKeys.publicKey(pem)).verify(payload, signature);
        assertEquals("配对检查", document.customer());
        assertEquals(1, document.seats());
    }

    @Test void seatsRejectLeadingZerosAndStayInsideTheDocumentRange() {
        assertEquals(1, LicenseDocument.parseSeats("1"));
        assertEquals(100000, LicenseDocument.parseSeats("100000"));
        assertThrows(IllegalArgumentException.class, () -> LicenseDocument.parseSeats("01"));
        assertThrows(IllegalArgumentException.class, () -> LicenseDocument.parseSeats("0"));
        assertThrows(IllegalArgumentException.class, () -> LicenseDocument.parseSeats("100001"));
    }

    @Test void onlyExactTrueOpensAFeature() {
        assertEquals(true, IssueLicense.featureFlag("true"));
        assertThrows(IllegalArgumentException.class, () -> IssueLicense.featureFlag("TRUE"));
        assertThrows(IllegalArgumentException.class, () -> IssueLicense.featureFlag("1"));
    }
}
