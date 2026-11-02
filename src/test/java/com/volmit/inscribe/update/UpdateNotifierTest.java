package com.volmit.inscribe.update;

import art.arcane.volmlib.util.update.BukkitUpdateService;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpdateNotifierTest {
    @Test
    void registersPublishedIdentityWithLivePreferenceAndCloses() throws IOException {
        JavaPlugin plugin = plugin("repository=VolmitSoftware/Inscribe\nconfigured=true\n");
        AtomicBoolean enabled = new AtomicBoolean(true);
        AtomicReference<BukkitUpdateService.Options> options = new AtomicReference<>();
        BukkitUpdateService service = mock(BukkitUpdateService.class);
        try (MockedStatic<BukkitUpdateService> shared = mockStatic(BukkitUpdateService.class)) {
            shared.when(() -> BukkitUpdateService.register(eq(plugin), any())).thenAnswer(call -> {
                options.set(call.getArgument(1));
                return service;
            });
            try (UpdateNotifier notifier = new UpdateNotifier(plugin, enabled::get)) {
                assertThat(notifier.repository()).isEqualTo("VolmitSoftware/Inscribe");
                assertThat(notifier.configured()).isTrue();
                assertThat(options.get().owner()).isEqualTo("VolmitSoftware");
                assertThat(options.get().repository()).isEqualTo("Inscribe");
                assertThat(options.get().permission()).isEqualTo("inscribe.update");
                assertThat(options.get().enabled().getAsBoolean()).isTrue();
                enabled.set(false);
                assertThat(options.get().enabled().getAsBoolean()).isFalse();
                notifier.reconfigure();
                verify(service).reconfigure();
            }
            verify(service).close();
        }
    }

    @Test
    void unconfiguredIdentityNeverEnablesRemoteChecks() throws IOException {
        JavaPlugin plugin = plugin("repository=UNCONFIGURED\nconfigured=false\n");
        BukkitUpdateService service = mock(BukkitUpdateService.class);
        try (MockedStatic<BukkitUpdateService> shared = mockStatic(BukkitUpdateService.class)) {
            shared.when(() -> BukkitUpdateService.register(eq(plugin), any())).thenAnswer(call -> {
                BukkitUpdateService.Options options = call.getArgument(1);
                assertThat(options.enabled().getAsBoolean()).isFalse();
                return service;
            });
            try (UpdateNotifier notifier = new UpdateNotifier(plugin, () -> true)) {
                assertThat(notifier.configured()).isFalse();
            }
            verify(service).close();
        }
    }

    @Test
    void missingOrMalformedMetadataCannotRegisterAnEndpoint() {
        try (MockedStatic<BukkitUpdateService> shared = mockStatic(BukkitUpdateService.class)) {
            assertThatThrownBy(() -> new UpdateNotifier(mock(JavaPlugin.class), () -> true))
                    .isInstanceOf(IOException.class).hasMessageContaining("Missing");
            assertThatThrownBy(() -> new UpdateNotifier(plugin("repository=invalid\nconfigured=true\n"), () -> true))
                    .isInstanceOf(IOException.class).hasMessageContaining("Invalid");
            shared.verifyNoInteractions();
        }
    }

    private static JavaPlugin plugin(String metadata) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getResource("inscribe-release.properties"))
                .thenReturn(new ByteArrayInputStream(metadata.getBytes(StandardCharsets.UTF_8)));
        return plugin;
    }
}
