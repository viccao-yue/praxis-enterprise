package com.kaiwu.praxis.admin.license;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

import org.springframework.stereotype.Component;

@Component
public class LicenseVerifier {
    private final PublicKey publicKey;

    public LicenseVerifier(PublicKey licensePublicKey) {
        this.publicKey = licensePublicKey;
    }

    public LicenseDocument verify(String payload, String signature) {
        byte[] signed;
        try {
            signed = Base64.getUrlDecoder().decode(signature == null ? "" : signature);
        } catch (IllegalArgumentException e) {
            throw LicenseTexts.bad(LicenseTexts.BAD_SIGNATURE);
        }
        if (signed.length != 64) throw LicenseTexts.bad(LicenseTexts.BAD_SIGNATURE);
        LicenseDocument document = LicenseDocument.parse(payload);
        try {
            Signature ed25519 = Signature.getInstance("Ed25519");
            ed25519.initVerify(publicKey);
            ed25519.update(payload.getBytes(StandardCharsets.UTF_8));
            if (!ed25519.verify(signed)) throw LicenseTexts.bad(LicenseTexts.BAD_SIGNATURE);
        } catch (GeneralSecurityException e) {
            throw LicenseTexts.bad(LicenseTexts.BAD_SIGNATURE);
        }
        if (!payload.equals(document.canonicalPayload())) throw LicenseTexts.bad(LicenseTexts.BAD_FORMAT);
        return document;
    }

    public static String sign(PrivateKey privateKey, String payload) {
        try {
            Signature ed25519 = Signature.getInstance("Ed25519");
            ed25519.initSign(privateKey);
            ed25519.update(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(ed25519.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法签发许可证", e);
        }
    }
}
