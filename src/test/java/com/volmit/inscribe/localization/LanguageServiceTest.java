package com.volmit.inscribe.localization;

import art.arcane.volmlib.util.localization.LanguageMeta;
import art.arcane.volmlib.util.localization.LanguageUpstreamSync;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.TextValue;
import art.arcane.volmlib.util.localization.TomlLanguageEditor;
import art.arcane.volmlib.util.localization.TomlLanguageParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.volmit.inscribe.config.ConfigService;
import com.volmit.inscribe.config.RejectedFileException;
import com.volmit.inscribe.command.ConfigEditorLayout;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class LanguageServiceTest {
    private static final String REVISION_ONE = "1111111111111111111111111111111111111111";
    private static final String REVISION_TWO = "2222222222222222222222222222222222222222";
    @TempDir
    Path directory;
    private JavaPlugin plugin;
    private ConfigService config;
    private LanguageService language;
    private HttpServer server;
    private final AtomicReference<String> revision = new AtomicReference<>(REVISION_ONE);
    private final Map<String, String> published = new ConcurrentHashMap<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/", this::api);
        server.createContext("/raw/", this::raw);
        server.start();
        plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("Inscribe-language-test"));
        when(plugin.getResource(anyString())).thenAnswer(invocation -> getClass().getResourceAsStream("/" + invocation.getArgument(0)));
        config = new ConfigService(directory.toFile());
        config.reload();
        Files.createDirectories(directory.resolve("languages"));
        for (String locale : List.of("de_DE", "fr_FR")) {
            Files.copy(Path.of("src/main/resources/languages/" + locale + ".toml"), directory.resolve("languages/" + locale + ".toml"));
        }
        language = service();
        language.start();
    }

    @AfterEach
    void stop() {
        language.close();
        config.close();
        server.stop(0);
    }

    @Test
    void invalidLanguageFileIsRejectedByNameAndKeepsTheServerLanguage() throws Exception {
        Files.writeString(directory.resolve("languages/de_DE.toml"), "[menu]\ntitle = 5\n");
        RejectedFileException rejected = assertThrows(RejectedFileException.class,
                () -> config.update(candidate -> candidate.general.language = "de_DE"));
        assertEquals("languages/de_DE.toml", rejected.file());
        assertTrue(rejected.reason().contains("menu.title"), rejected.reason());
        assertEquals("en_US", config.runtime().defaultLocale());
    }

    @Test
    void startupWritesOnlyEnglishWithAHeaderAndListsRepositoryLocales() throws Exception {
        String english = Files.readString(directory.resolve("languages/en_US.toml"));
        assertTrue(english.startsWith("# Inscribe — en_US\n"), english.lines().findFirst().orElse(""));
        assertTrue(english.contains("{remaining}"));
        assertTrue(Files.isRegularFile(directory.resolve("languages/.upstream/en_US.toml")));
        assertTrue(Files.notExists(directory.resolve("languages/es_ES.toml")));
        List<String> available = language.selections().availableLocales();
        assertEquals(18, available.size());
        assertTrue(available.containsAll(List.of("en_US", "de_DE", "es_ES", "ja-JP", "zh_TW")), available.toString());
        try (PluginLanguageEditor editor = new PluginLanguageEditor(language.selections(), language.editorOptions())) {
            assertEquals(new TextValue("Enchantments transferred."), editor.load("en_US").get(5, TimeUnit.SECONDS)
                    .snapshot().value(InscribeMessages.catalog().require("transfer.success")));
        }
    }

    @Test
    void forcedUpdateMergesTheBranchHeadAndKeepsManualEditsAndCustomFiles() throws Exception {
        String spanish = Files.readString(Path.of("src/main/resources/languages/es_ES.toml"));
        published.put(REVISION_ONE + "/es_ES", spanish);
        UUID player = UUID.randomUUID();
        language.selections().selectPlayer(player, "es_ES").get(10, TimeUnit.SECONDS);
        Path installed = directory.resolve("languages/es_ES.toml");
        assertTrue(Files.isRegularFile(installed));
        assertTrue(Files.isRegularFile(directory.resolve("languages/.upstream/es_ES.toml")));
        assertTrue(Files.readString(directory.resolve("languages/.upstream/upstream.properties")).contains(REVISION_ONE));
        Files.writeString(installed, TomlLanguageEditor.upsertText(Files.readString(installed),
                "transfer.success", "Edición local").content());
        Path pirate = directory.resolve("languages/pirate.toml");
        String custom = "[meta]\nname = \"Pirate Speak\"\n\n[transfer]\nsuccess = \"Arr, moved!\"\n";
        Files.writeString(pirate, custom);
        language.reload();
        revision.set(REVISION_TWO);
        String next = TomlLanguageEditor.upsertText(TomlLanguageEditor.upsertText(spanish,
                "transfer.success", "Encantamientos transferidos (v2).").content(),
                "transfer.failed", "La transferencia falló (v2).").content();
        published.put(REVISION_TWO + "/es_ES", next);

        LanguageUpstreamSync.SyncReport report = language.updateAction().update().get(10, TimeUnit.SECONDS);

        assertTrue(report.failure().isEmpty(), report.failure().toString());
        assertEquals(List.of("es_ES"), report.changedLocales());
        assertEquals(List.of("transfer.success"), List.copyOf(report.merged().get("es_ES").kept()));
        assertTrue(report.merged().get("es_ES").updated().contains("transfer.failed"));
        Map<String, String> merged = TomlLanguageParser.parseText(Files.readString(installed));
        assertEquals("Edición local", merged.get("transfer.success"));
        assertEquals("La transferencia falló (v2).", merged.get("transfer.failed"));
        assertEquals(custom, Files.readString(pirate));
        assertEquals(new TextValue("La transferencia falló (v2)."), language.selections().snapshot(player)
                .value(InscribeMessages.catalog().require("transfer.failed")));
        assertTrue(language.diagnostics().contains("revision 2222222"), language.diagnostics());

        LanguageUpstreamSync.SyncReport unchanged = language.updateAction().update().get(10, TimeUnit.SECONDS);

        assertTrue(unchanged.unchanged());
        assertTrue(unchanged.changedLocales().isEmpty());
    }

    @Test
    void metaBaseChainResolvesBeforeEnglishAndNamesTheLocale() throws Exception {
        Path german = directory.resolve("languages/de_DE.toml");
        Files.writeString(german, TomlLanguageEditor.remove(Files.readString(german), "menu.cancel").content());
        Files.writeString(directory.resolve("languages/de_AT.toml"), "[meta]\nname = \"Österreichisch\"\nbase = \"de_DE\"\n\n"
                + "[transfer]\nsuccess = \"Verzauberungen übertragen, Oida.\"\n");
        language.reload();
        assertTrue(language.selections().availableLocales().contains("de_AT"));
        assertEquals("Österreichisch", language.displayNames().displayName("de_AT"));
        assertNull(language.displayNames().displayName("de_DE"));
        UUID player = UUID.randomUUID();
        language.selections().selectPlayer(player, "de_AT").get(5, TimeUnit.SECONDS);
        assertEquals(new TextValue("Verzauberungen übertragen, Oida."), language.selections().snapshot(player)
                .value(InscribeMessages.catalog().require("transfer.success")));
        assertEquals(language.selections().snapshot(player).value(InscribeMessages.catalog().require("transfer.failed")),
                new TextValue(TomlLanguageParser.parseText(Files.readString(german)).get("transfer.failed")));
        assertEquals(new TextValue("Cancel"), language.selections().snapshot(player)
                .value(InscribeMessages.catalog().require("menu.cancel")));
    }

    @Test
    void customServerLanguageIsCreatedWithMetaFromTheEnglishReference() throws Exception {
        config.update(candidate -> candidate.general.language = "pirate");
        Path pirate = directory.resolve("languages/pirate.toml");
        LanguageMeta meta = LanguageMeta.parse(Files.readString(pirate));
        assertEquals("pirate", meta.name().orElseThrow());
        assertEquals("en_US", meta.base().orElseThrow());
        assertEquals("pirate", config.runtime().defaultLocale());
        assertTrue(language.selections().availableLocales().contains("pirate"));
    }

    @Test
    void englishReferenceGainsNewCatalogKeysWhileKeepingEdits() throws Exception {
        language.close();
        Path english = directory.resolve("languages/en_US.toml");
        Path sidecar = directory.resolve("languages/.upstream/en_US.toml");
        String older = TomlLanguageEditor.remove(Files.readString(sidecar), "transfer.incompatible").content();
        Files.writeString(sidecar, older);
        Files.writeString(english, TomlLanguageEditor.upsertText(older, "menu.cost", "Price: {cost}").content());
        language = service();
        language.start();
        Map<String, String> values = TomlLanguageParser.parseText(Files.readString(english));
        assertEquals("This book has no compatible upgrades for that item.", values.get("transfer.incompatible"));
        assertEquals("Price: {cost}", values.get("menu.cost"));
        assertEquals("Price: 5 XP", language.text(null, "menu.cost", "cost", "5 XP"));
        assertTrue(Files.readString(sidecar).contains("compatible upgrades"));
    }

    @Test
    void editorSavesImmediatelyWithoutChangingSelectionsAndPreservesEditsOnRestart() throws Exception {
        UUID player = UUID.randomUUID();
        language.selections().selectPlayer(player, "de_DE").get(5, TimeUnit.SECONDS);
        try (PluginLanguageEditor editor = new PluginLanguageEditor(language.selections(), language.editorOptions())) {
            PluginLanguageEditor.Document document = editor.load("de_DE").get(5, TimeUnit.SECONDS);
            editor.save(new PluginLanguageEditor.Edit("de_DE", "transfer.success",
                    document.snapshot().value(InscribeMessages.catalog().require("transfer.success")),
                    new TextValue("Übertragung gespeichert."))).get(5, TimeUnit.SECONDS);
        }
        assertEquals("de_DE", language.selections().effectiveLocale(player));
        assertEquals("en_US", config.runtime().defaultLocale());
        assertEquals("Übertragung gespeichert.", ((TextValue) language.selections().snapshot(player)
                .value(InscribeMessages.catalog().require("transfer.success"))).template());
        language.close();
        language = service();
        language.start();
        assertEquals("de_DE", language.selections().effectiveLocale(player));
        try (PluginLanguageEditor editor = new PluginLanguageEditor(language.selections(), language.editorOptions())) {
            assertEquals(new TextValue("Übertragung gespeichert."), editor.load("de_DE").get(5, TimeUnit.SECONDS)
                    .snapshot().value(InscribeMessages.catalog().require("transfer.success")));
        }
    }

    @Test
    void invalidPlaceholdersAndStaleEditorValuesNeverOverwriteDisk() throws Exception {
        Path file = directory.resolve("languages/en_US.toml");
        String original = Files.readString(file);
        try (PluginLanguageEditor editor = new PluginLanguageEditor(language.selections(), language.editorOptions())) {
            PluginLanguageEditor.Document document = editor.load("en_US").get(5, TimeUnit.SECONDS);
            assertThrows(ExecutionException.class, () -> editor.save(new PluginLanguageEditor.Edit("en_US", "menu.cost",
                    document.snapshot().value(InscribeMessages.catalog().require("menu.cost")),
                    new TextValue("Cost: {wrong}"))).get(5, TimeUnit.SECONDS));
            assertEquals(original, Files.readString(file));
            Files.writeString(file, TomlLanguageEditor.upsertText(original, "menu.cost", "Price: {cost}").content());
            assertThrows(ExecutionException.class, () -> editor.save(new PluginLanguageEditor.Edit("en_US", "menu.cost",
                    document.snapshot().value(InscribeMessages.catalog().require("menu.cost")),
                    new TextValue("Charge: {cost}"))).get(5, TimeUnit.SECONDS));
            assertTrue(Files.readString(file).contains("Price: {cost}"));
        }
    }

    @Test
    void serverSelectionPersistsAndPersonalSelectionRemainsIndependent() throws Exception {
        UUID player = UUID.randomUUID();
        language.selections().selectPlayer(player, "fr_FR").get(5, TimeUnit.SECONDS);
        language.selections().selectDefault("de_DE").get(5, TimeUnit.SECONDS);
        assertEquals("de_DE", config.runtime().defaultLocale());
        assertEquals("fr_FR", language.selections().effectiveLocale(player));
        assertTrue(language.text(mock(CommandSender.class), "transfer.success").contains("übertragen"));
        ConfigService reloaded = new ConfigService(directory.toFile());
        reloaded.reload();
        assertEquals("de_DE", reloaded.runtime().defaultLocale());
        reloaded.close();
    }

    @Test
    void curatedEditorTitleRendersWithoutArgumentsInSelectedLanguage() throws Exception {
        TextKey title = ConfigEditorLayout.create(player -> { }).title();
        assertEquals("Inscribe / Settings", language.render(null, title, MessageArgs.empty()).plain());
        language.selections().selectDefault("de_DE").get(5, TimeUnit.SECONDS);
        assertEquals("Inscribe / Einstellungen", language.render(null, title, MessageArgs.empty()).plain());
    }

    @Test
    void untrustedArgumentsStayLiteralWithoutRecursiveSubstitution() {
        TextKey key = (TextKey) InscribeMessages.catalog().require("menu.keep");
        String result = language.render(null, key, MessageArgs.builder()
                .untrusted("name", "<red>{level}</red>&a")
                .untrusted("level", "5").build()).plain();
        assertEquals("Keep: <red>{level}</red>&a 5", result);
    }

    private LanguageService service() {
        URI root = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        return new LanguageService(plugin, config, root.resolve("api/"), root.resolve("raw/"), Duration.ZERO);
    }

    private void api(HttpExchange exchange) throws IOException {
        String etag = "\"" + revision.get() + "\"";
        if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().set("ETag", etag);
        respond(exchange, "[{\"sha\":\"" + revision.get() + "\"}]");
    }

    private void raw(HttpExchange exchange) throws IOException {
        String[] parts = exchange.getRequestURI().getPath().split("/");
        String content = published.get(parts[4] + "/" + parts[parts.length - 1].replace(".toml", ""));
        if (content == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        respond(exchange, content);
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
