package com.beavent.backend;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Fails the build when one module reaches into another module's internals.
 * Modules are the direct sub-packages of com.beavent.backend (one per database schema, docs/03 §2.2).
 */
class ModularityTests {

    @Test
    void moduleBoundariesAreRespected() {
        ApplicationModules.of(BackendApplication.class).verify();
    }
}
