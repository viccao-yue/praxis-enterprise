package com.kaiwu.praxis.admin.license;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

final class PemKeys {
    private PemKeys() {}

    static PublicKey publicKey(String pem) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(body(pem, "PUBLIC KEY")));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("无法读取授权公钥", e);
        }
    }

    static PrivateKey privateKey(String pem) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(body(pem, "PRIVATE KEY")));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("无法读取授权私钥", e);
        }
    }

    private static byte[] body(String pem, String kind) {
        String begin = "-----BEGIN " + kind + "-----";
        String end = "-----END " + kind + "-----";
        int start = pem.indexOf(begin);
        int finish = pem.indexOf(end);
        if (start < 0 || finish < start) throw new IllegalArgumentException("PEM 头不正确");
        String encoded = pem.substring(start + begin.length(), finish).replaceAll("\\s", "");
        return Base64.getDecoder().decode(encoded);
    }
}
