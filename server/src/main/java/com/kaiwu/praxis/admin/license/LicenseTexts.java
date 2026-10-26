package com.kaiwu.praxis.admin.license;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class LicenseTexts {
    public static final String INVALID = "企业授权无效，请所有者更新许可证";
    public static final String FEATURE = "企业授权未包含此功能";
    public static final String SEATS = "席位已满";
    public static final String SEATS_OVER = "席位已超出授权，请所有者停用多余成员";
    public static final String SEATS_LOW = "席位少于当前有效成员数";
    public static final String BAD_SIGNATURE = "许可证签名无效";
    public static final String BAD_INSTALLATION = "许可证与此安装标识不符";
    public static final String BAD_FORMAT = "许可证格式无效";
    public static final String OWNER_ONLY = "只有所有者可以更新企业授权";

    private LicenseTexts() {}

    public static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public static ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }

    static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
