package com.aipro.upgrade.server.auth;

import org.mindrot.jbcrypt.BCrypt;

/**
 * 密码哈希工具：基于 BCrypt。
 * <p>
 * 用于后台用户密码存储与校验（PRD F-S-10：BCrypt 哈希）。
 */
public final class PasswordHasher {

    private PasswordHasher() {
    }

    /** 对明文密码做 BCrypt 哈希。 */
    public static String hash(String plain) {
        if (plain == null) {
            throw new IllegalArgumentException("密码不能为空");
        }
        return BCrypt.hashpw(plain, BCrypt.gensalt(10));
    }

    /** 校验明文密码与哈希是否匹配。 */
    public static boolean check(String plain, String hash) {
        if (plain == null || hash == null || hash.isEmpty()) {
            return false;
        }
        try {
            return BCrypt.checkpw(plain, hash);
        } catch (Exception e) {
            return false;
        }
    }
}
