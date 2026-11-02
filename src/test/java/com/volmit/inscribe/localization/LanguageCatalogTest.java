package com.volmit.inscribe.localization;

import art.arcane.volmlib.util.localization.LanguageSourceManifest;
import art.arcane.volmlib.util.localization.LocaleOverlay;
import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TomlLanguageParser;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LanguageCatalogTest {
    @Test
    void everyRepositoryLocaleHasCompleteKeysAndValidPlaceholders() throws IOException {
        MessageCatalog catalog = InscribeMessages.catalog();
        LanguageSourceManifest manifest = LanguageSourceManifest.load(getClass().getClassLoader(), LanguageService.MANIFEST_RESOURCE);
        assertEquals(17, manifest.locales().size());
        assertFalse(manifest.contains("en_US"));
        for (String locale : manifest.locales()) {
            try (InputStream input = getClass().getResourceAsStream("/languages/" + locale + ".toml")) {
                assertNotNull(input, locale);
                Map<String, String> values = TomlLanguageParser.parseText(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                assertEquals(catalog.ids(), values.keySet(), locale);
                LocaleOverlay.Builder overlay = LocaleOverlay.builder(locale);
                values.forEach(overlay::text);
                LocalizationSnapshot.create(new LocalizationCandidate(catalog, List.of(overlay.build()),
                        (language, quantity) -> quantity.doubleValue() == 1D ? "one" : "other"));
                assertNotEquals("Enchantments transferred.", values.get("transfer.success"), locale);
                assertNotEquals("Type a new value for {key} in chat.", values.get("language.editor.prompt.value"), locale);
                assertNotEquals("Click to toggle true or false.", values.get("config.editor.toggle"), locale);
                assertNotEquals("Enable extraction and application.", values.get("setting.general.enabled.description"), locale);
                assertNotEquals("{plugin}: checking the language repository for changes...", values.get("language.update.checking"), locale);
            }
        }
    }

    @Test
    void englishIsGeneratedFromTheCatalogWithEveryPlaceholderInTheHeader() {
        assertNull(getClass().getResourceAsStream("/languages/en_US.toml"));
        Set<String> used = new TreeSet<>();
        for (MessageKey key : InscribeMessages.catalog().keys()) {
            used.addAll(key.placeholders());
        }
        Set<String> missing = new TreeSet<>(used);
        missing.removeAll(LanguageService.variables().keySet());
        assertTrue(missing.isEmpty(), "Header variables are missing " + missing);
        String reference = LanguageService.englishReference();
        assertTrue(reference.startsWith("# Inscribe — en_US\n"), reference.lines().findFirst().orElse(""));
        for (String placeholder : List.of("added", "updated", "kept", "remaining")) {
            assertTrue(reference.contains("#   {" + placeholder + "}  "), placeholder);
        }
        assertTrue(reference.contains("[language.update]"));
    }
}
