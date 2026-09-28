package com.aipro.upgrade.server;

import com.aipro.upgrade.server.auth.PasswordHasher;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * BCrypt 密码哈希测试
 */
public class PasswordHasherTest {

    @Test
    public void testHashAndCheck() {
        String plain = "admin123";
        String hash = PasswordHasher.hash(plain);
        assertNotNull(hash);
        assertTrue(hash.startsWith("$2a$"));
        assertNotEquals(plain, hash);
        assertTrue(PasswordHasher.check(plain, hash));
        assertFalse(PasswordHasher.check("wrong", hash));
    }

    @Test
    public void testCheckNull() {
        assertFalse(PasswordHasher.check(null, "$2a$10$xxx"));
        assertFalse(PasswordHasher.check("admin", null));
        assertFalse(PasswordHasher.check("admin", ""));
        assertFalse(PasswordHasher.check("admin", "invalid-hash"));
    }
}
