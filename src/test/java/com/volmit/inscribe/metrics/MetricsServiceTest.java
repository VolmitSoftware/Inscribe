package com.volmit.inscribe.metrics;

import com.volmit.inscribe.Inscribe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class MetricsServiceTest {
    @TempDir
    Path directory;

    @Test
    void preservesGlobalOptOutUsesRegisteredIdAndStopsOnDisable() throws Exception {
        Path plugins = directory.resolve("plugins");
        Files.createDirectories(plugins.resolve("bStats"));
        Files.writeString(plugins.resolve("bStats/config.yml"), "enabled: false\nserverUuid: 00000000-0000-0000-0000-000000000001\n");
        Inscribe plugin = mock(Inscribe.class);
        when(plugin.getDataFolder()).thenReturn(plugins.resolve("Inscribe").toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("InscribeMetricsTest"));
        try (MetricsService service = new MetricsService(plugin)) {
            service.reconfigure(false);
            assertThat(service.initialized()).isFalse();
            service.reconfigure(true);
            assertThat(service.initialized()).isTrue();
            Object metrics = field(service, "metrics");
            Object base = field(metrics, "metricsBase");
            assertThat(field(base, "serviceId")).isEqualTo(34581);
            assertThat(field(base, "enabled")).isEqualTo(false);
            ScheduledExecutorService scheduler = (ScheduledExecutorService) field(base, "scheduler");
            service.reconfigure(true);
            assertThat(field(service, "metrics")).isSameAs(metrics);
            service.reconfigure(false);
            assertThat(service.initialized()).isFalse();
            assertThat(scheduler.isShutdown()).isTrue();
            service.reconfigure(true);
            Object restarted = field(service, "metrics");
            assertThat(restarted).isNotSameAs(metrics);
            service.close();
            assertThat(((ScheduledExecutorService) field(field(restarted, "metricsBase"), "scheduler")).isShutdown()).isTrue();
            service.reconfigure(true);
            assertThat(service.initialized()).isFalse();
        }
        assertThat(Files.readString(plugins.resolve("bStats/config.yml"))).contains("enabled: false");
    }

    private static Object field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }
}