package com.volmit.inscribe.metrics;

import com.volmit.inscribe.config.ConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class MetricsConfigurationTest {
    @TempDir
    Path directory;

    @Test
    void installedListenerReceivesAcceptedEditsAndReloadsOnly() throws Exception {
        List<Boolean> installed = new ArrayList<>();
        try (ConfigService config = new ConfigService(directory.toFile())) {
            config.reload();
            assertThat(config.runtime().metricsEnabled()).isTrue();
            config.setInstalled(snapshot -> installed.add(snapshot.metricsEnabled()));
            config.update(candidate -> candidate.metrics.enabled = false);
            config.reload();
            config.update(candidate -> candidate.metrics.enabled = true);
            assertThatThrownBy(() -> config.update(candidate -> {
                candidate.metrics.enabled = false;
                candidate.pricing.extractionCost = -1;
            })).isInstanceOf(IOException.class);
            assertThat(installed).containsExactly(false, false, true);
            assertThat(config.runtime().metricsEnabled()).isTrue();
        }
    }
}