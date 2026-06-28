package com.chtholly.auth.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentifierValidatorTest {

    @Test
    void acceptsValidHandle() {
        assertTrue(IdentifierValidator.isValidHandle("alice"));
        assertTrue(IdentifierValidator.isValidHandle("_user123"));
        assertTrue(IdentifierValidator.isValidHandle("abc_def_123"));
    }

    @Test
    void rejectsInvalidHandle() {
        assertFalse(IdentifierValidator.isValidHandle("1abc"));
        assertFalse(IdentifierValidator.isValidHandle("ab"));
        assertFalse(IdentifierValidator.isValidHandle("a".repeat(33)));
        assertFalse(IdentifierValidator.isValidHandle("user-name"));
        assertFalse(IdentifierValidator.isValidHandle(null));
    }
}
