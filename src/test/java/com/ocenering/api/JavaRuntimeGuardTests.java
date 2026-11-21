package com.ocenering.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JavaRuntimeGuardTests {

    @Test
    void rejectsVersionsAffectedByJava4Shell() {
        assertFalse(JavaRuntimeGuard.isPatched(Runtime.Version.parse("27")));
        assertFalse(JavaRuntimeGuard.isPatched(Runtime.Version.parse("27.0.1")));
        assertFalse(JavaRuntimeGuard.isPatched(Runtime.Version.parse("27.0.1.0.3")));
    }

    @Test
    void acceptsPatchedVersions() {
        assertTrue(JavaRuntimeGuard.isPatched(Runtime.Version.parse("27.0.1.1")));
        assertTrue(JavaRuntimeGuard.isPatched(Runtime.Version.parse("27.0.1.1+4")));
        assertTrue(JavaRuntimeGuard.isPatched(Runtime.Version.parse("27.0.1.1.4")));
        assertTrue(JavaRuntimeGuard.isPatched(Runtime.Version.parse("27.0.2")));
    }

    @Test
    void currentTestRuntimeIsPatched() {
        assertTrue(JavaRuntimeGuard.isPatched(Runtime.version()));
    }
}
