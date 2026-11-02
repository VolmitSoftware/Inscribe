package com.volmit.inscribe.presentation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InscribePresentationTest {
    @Test
    void splashStatesRuntimeIdentityAndSupportedBaseline() {
        assertThat(InscribePresentation.details("2.0.0", "Folia 26.1.2", 25, "2026-10-14", 123))
                .containsExactly("Inscribe | Enchantment extraction and application",
                        "Version: 2.0.0 | By VolmitSoftware",
                        "Server: Folia 26.1.2",
                        "Minecraft: 26.1-26.3 | Java: 25 (minimum 25)",
                        "Started: 2026-10-14 | Ready in 123 ms",
                        "https://volmitsoftware.com | /inscribe");
    }
}
