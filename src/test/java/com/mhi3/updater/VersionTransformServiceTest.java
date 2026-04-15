package com.mhi3.updater;

import com.mhi3.updater.util.VersionTransformService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VersionTransformServiceTest {
    @Test
    void transformsP4398ToP4368StyleOutput() {
        var svc = new VersionTransformService();
        var info = svc.derive("P4368");
        assertEquals("P4368", info.normalizedVersion());
        assertEquals("4368", info.muVersion());
        assertEquals("P436*", info.wildcardVersion());
    }

    @Test
    void transformsNumericOnlyInput() {
        var svc = new VersionTransformService();
        var info = svc.derive("4368");
        assertEquals("P4368", info.normalizedVersion());
        assertEquals("4368", info.muVersion());
        assertEquals("P436*", info.wildcardVersion());
    }
}
