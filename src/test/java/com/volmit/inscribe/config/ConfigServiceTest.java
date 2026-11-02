package com.volmit.inscribe.config;

import art.arcane.volmlib.util.config.ConfigEditorDocument;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConfigServiceTest {
    @TempDir
    Path directory;

    @Test
    void rejectsMalformedReplacementAndKeepsInstalledSettings() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.reload();
        service.update(candidate -> candidate.pricing.extractionCost = 42);
        Files.writeString(service.configFile().toPath(), "[general]\nenabled = [broken");
        assertThrows(IOException.class, service::reload);
        assertEquals(42, service.runtime().extractionCost());
    }

    @Test
    void rejectsInvalidCandidateBeforeWritingOrInstalling() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.reload();
        String original = Files.readString(service.configFile().toPath());
        assertThrows(IOException.class, () -> service.update(candidate -> candidate.pricing.extractionCost = -1));
        assertEquals(original, Files.readString(service.configFile().toPath()));
        assertEquals(100, service.runtime().extractionCost());
    }

    @Test
    void writesReservedSectionsWithStandardDefaults() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.reload();
        String written = Files.readString(service.configFile().toPath());
        for (String section : List.of("[general]", "[metrics]", "[presentation]", "[hotReload]", "[debug]", "[transfers]", "[pricing]")) {
            assertTrue(written.contains(section), section);
        }
        RuntimeConfig runtime = service.runtime();
        assertEquals(1_000L, runtime.pollIntervalMillis());
        assertEquals(1_500L, runtime.cooldownMillis());
        assertTrue(runtime.notifyOperators());
        assertTrue(runtime.debugUpload());
        assertTrue(runtime.mergeEqualLevels());
        assertTrue(runtime.increaseRepairCost());
    }

    @Test
    void clampsHotReloadTiming() {
        InscribeConfig source = new InscribeConfig();
        source.hotReload.pollIntervalMillis = 10;
        source.hotReload.cooldownMillis = 600_000;
        RuntimeConfig runtime = RuntimeConfig.from(source);
        assertEquals(250L, runtime.pollIntervalMillis());
        assertEquals(60_000L, runtime.cooldownMillis());
    }

    @Test
    void unchangedReloadKeepsTheInstalledSnapshot() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.reload();
        RuntimeConfig before = service.runtime();
        service.reload();
        assertSame(before, service.runtime());
    }

    @Test
    void editorRejectsConcurrentChangesAndPreservesTheNewerValue() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.reload();
        ConfigEditorDocument document = service.document();
        service.update(candidate -> candidate.general.enabled = false);
        assertThrows(IOException.class, () -> service.edit(document.edit(List.of("general", "enabled"), new JsonPrimitive(false))));
        assertFalse(service.runtime().enabled());
    }

    @Test
    void preparationFailurePreservesConfigAndDisk() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.reload();
        String original = Files.readString(service.configFile().toPath());
        service.setPreparation(candidate -> {
            throw new IllegalArgumentException("Invalid language");
        });
        assertThrows(IOException.class, () -> service.update(candidate -> candidate.general.language = "de_DE"));
        assertEquals("en_US", service.runtime().defaultLocale());
        assertEquals(original, Files.readString(service.configFile().toPath()));
    }

    @Test
    void rejectsFractionalExperienceAndUnsafeLocaleIds() {
        InscribeConfig fractional = new InscribeConfig();
        fractional.enchantments.put("minecraft:sharpness", new EnchantmentPolicy(true, 0.5, false, Currency.EXPERIENCE));
        assertEquals("enchantments.minecraft:sharpness.cost must be a whole number of experience points",
                assertThrows(IllegalArgumentException.class, () -> RuntimeConfig.from(fractional)).getMessage());
        InscribeConfig custom = new InscribeConfig();
        custom.general.language = "pirate";
        assertEquals("pirate", RuntimeConfig.from(custom).defaultLocale());
        for (String unsafe : new String[]{"../de_DE", "x", "de DE", "a".repeat(33)}) {
            InscribeConfig source = new InscribeConfig();
            source.general.language = unsafe;
            assertThrows(IllegalArgumentException.class, () -> RuntimeConfig.from(source), unsafe);
        }
    }

    @Test
    void watcherSeesCustomLanguageFilesAndDeletionsButNotTheUpstreamSidecar() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.reload();
        AtomicLong now = new AtomicLong(0);
        service.clock(now::get);
        Path languages = directory.resolve("languages");
        Files.createDirectories(languages.resolve(".upstream"));
        service.baseline();
        List<Set<Path>> changes = new ArrayList<>();
        ConfigService.Changes reload = changes::add;
        service.poll(reload, outcome -> { });
        Path pirate = languages.resolve("pirate.toml").toAbsolutePath().normalize();
        Files.writeString(pirate, "[meta]\nname = \"Pirate Speak\"\n");
        Files.writeString(languages.resolve(".upstream/de_DE.toml"), "[menu]\ntitle = \"Inscribe\"\n");
        Files.writeString(languages.resolve("notes.txt"), "ignored");
        for (int poll = 0; poll < 3; poll++) {
            now.addAndGet(1_000);
            service.poll(reload, outcome -> { });
        }
        assertEquals(List.of(Set.of(pirate)), changes);
        Files.delete(pirate);
        for (int poll = 0; poll < 3; poll++) {
            now.addAndGet(1_000);
            service.poll(reload, outcome -> { });
        }
        assertEquals(List.of(Set.of(pirate), Set.of(pirate)), changes);
        service.close();
    }

    @Test
    void rejectionsNameTheFileAndSetting() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.reload();
        RejectedFileException cost = assertThrows(RejectedFileException.class,
                () -> service.update(candidate -> candidate.pricing.extractionCost = -1));
        assertEquals("config.toml", cost.file());
        assertEquals("pricing.extractionCost must be between 0 and 1000000", cost.reason());
        RejectedFileException material = assertThrows(RejectedFileException.class,
                () -> service.update(candidate -> candidate.transfers.applicationMaterials = List.of("NOT_A_MATERIAL")));
        assertEquals("transfers.applicationMaterials contains unknown item material NOT_A_MATERIAL", material.reason());
        RejectedFileException lore = assertThrows(RejectedFileException.class,
                () -> service.update(candidate -> candidate.transfers.bookLore = List.of("ok", "x".repeat(513))));
        assertEquals("transfers.bookLore line 2 must be text of at most 512 characters", lore.reason());
        Files.writeString(service.configFile().toPath(), "[general]\nenabled = [broken");
        RejectedFileException syntax = assertThrows(RejectedFileException.class, service::reload);
        assertFalse(syntax.reason().isBlank());
    }

    @Test
    void watcherWaitsForQuietFilesAndLogsEachOutcomeOnceWithItsTrace() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.reload();
        AtomicLong now = new AtomicLong(0);
        service.clock(now::get);
        service.baseline();
        Path file = service.configFile().toPath();
        String original = Files.readString(file);
        List<LogRecord> records = new ArrayList<>();
        List<Boolean> outcomes = new ArrayList<>();
        List<Set<Path>> changes = new ArrayList<>();
        Handler handler = handler(records);
        Logger logger = Logger.getLogger("Inscribe");
        logger.addHandler(handler);
        ConfigService.Changes reload = files -> {
            changes.add(files);
            service.reload();
        };
        try {
            service.poll(reload, outcomes::add);
            Files.writeString(file, original.replaceFirst("(?m)^extractionCost = 100$", "extractionCost = -10"));
            now.addAndGet(1_000);
            service.poll(reload, outcomes::add);
            assertTrue(changes.isEmpty());
            now.addAndGet(1_000);
            service.poll(reload, outcomes::add);
            assertTrue(changes.isEmpty());
            now.addAndGet(1_000);
            service.poll(reload, outcomes::add);
            assertEquals(List.of(Set.of(file)), changes);
            now.addAndGet(1_000);
            service.poll(reload, outcomes::add);
            assertEquals(1, records.size());
            assertEquals(Level.SEVERE, records.getFirst().getLevel());
            assertNotNull(records.getFirst().getThrown());
            assertEquals("Rejected config.toml (pricing.extractionCost must be between 0 and 1000000); the last known good settings remain active.",
                    records.getFirst().getMessage());
            assertEquals(List.of(false), outcomes);
            assertEquals(100, service.runtime().extractionCost());
            Files.writeString(file, original.replaceFirst("(?m)^enabled = true$", "enabled = false"));
            now.addAndGet(1_000);
            service.poll(files -> { throw new IOException("Disk failure"); }, outcomes::add);
            now.addAndGet(2_000);
            service.poll(files -> { throw new IOException("Disk failure"); }, outcomes::add);
            assertEquals(2, records.size());
            assertNotNull(records.get(1).getThrown());
            assertEquals(List.of(false, false), outcomes);
        } finally {
            logger.removeHandler(handler);
            service.close();
        }
    }

    @Test
    void ownWritesAndDisabledHotReloadNeverTriggerReloads() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.reload();
        AtomicLong now = new AtomicLong(0);
        service.clock(now::get);
        service.baseline();
        List<Set<Path>> changes = new ArrayList<>();
        ConfigService.Changes reload = changes::add;
        service.poll(reload, outcome -> { });
        service.update(candidate -> candidate.pricing.extractionCost = 7);
        for (int poll = 0; poll < 4; poll++) {
            now.addAndGet(1_000);
            service.poll(reload, outcome -> { });
        }
        assertTrue(changes.isEmpty());
        service.update(candidate -> candidate.hotReload.enabled = false);
        Path file = service.configFile().toPath();
        Files.writeString(file, Files.readString(file).replaceFirst("(?m)^extractionCost = 7$", "extractionCost = 8"));
        for (int poll = 0; poll < 4; poll++) {
            now.addAndGet(1_000);
            service.poll(reload, outcome -> { });
        }
        assertTrue(changes.isEmpty());
        assertEquals(7, service.runtime().extractionCost());
    }

    private static Handler handler(List<LogRecord> records) {
        return new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }
}
