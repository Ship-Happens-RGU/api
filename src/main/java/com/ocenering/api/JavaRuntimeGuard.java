package com.ocenering.api;

/**
 * Stops the API from starting on a Java runtime affected by Java4Shell (CVE-2026-114938),
 * a memory-corruption bug in the JDK's Base64 decoder that can lead to remote code execution.
 * The build checks this too, but this also covers jars deployed onto a different JVM.
 */
final class JavaRuntimeGuard {

    static final Runtime.Version MIN_SAFE_VERSION = Runtime.Version.parse("27.0.1.1");

    private JavaRuntimeGuard() {
    }

    static boolean isPatched(Runtime.Version version) {
        return version.compareToIgnoreOptional(MIN_SAFE_VERSION) >= 0;
    }

    static void check() {
        Runtime.Version current = Runtime.version();

        if (!isPatched(current)) {
            throw new IllegalStateException("Java " + current + " is affected by Java4Shell (CVE-2026-114938). "
                + "Upgrade to Java " + MIN_SAFE_VERSION + " or later before starting the API.");
        }
    }
}
