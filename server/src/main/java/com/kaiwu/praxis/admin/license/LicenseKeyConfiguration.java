package com.kaiwu.praxis.admin.license;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

@Configuration
public class LicenseKeyConfiguration {
    @Bean
    PublicKey licensePublicKey() throws IOException {
        String pem = new ClassPathResource("license-public.pem").getContentAsString(StandardCharsets.UTF_8);
        return PemKeys.publicKey(pem);
    }
}
