package com.volmit.inscribe.localization;

import art.arcane.volmlib.util.director.DirectorTextResolver;
import art.arcane.volmlib.util.io.AtomicFileIO;
import art.arcane.volmlib.util.localization.BukkitLanguageSwitcher;
import art.arcane.volmlib.util.localization.GitHubLanguageSource;
import art.arcane.volmlib.util.localization.LanguageAudience;
import art.arcane.volmlib.util.localization.LanguageCatalogMerge;
import art.arcane.volmlib.util.localization.LanguageDirectory;
import art.arcane.volmlib.util.localization.LanguageFileEditor;
import art.arcane.volmlib.util.localization.LanguageFileHeader;
import art.arcane.volmlib.util.localization.LanguageMeta;
import art.arcane.volmlib.util.localization.LanguageReferenceRenderer;
import art.arcane.volmlib.util.localization.LanguageSourceManifest;
import art.arcane.volmlib.util.localization.LanguageUpstreamStore;
import art.arcane.volmlib.util.localization.LanguageUpstreamSync;
import art.arcane.volmlib.util.localization.LocaleOverlay;
import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.MessageArgumentKind;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.PluginLanguageService;
import art.arcane.volmlib.util.localization.PluralSelector;
import art.arcane.volmlib.util.localization.ResolvedText;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.TomlLanguageEditor;
import art.arcane.volmlib.util.localization.TomlLanguageParser;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.plugin.ComponentText;
import com.volmit.inscribe.config.ConfigService;
import com.volmit.inscribe.config.RejectedFileException;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LanguageService implements AutoCloseable {
    public static final String MANIFEST_RESOURCE = "inscribe-language-source.properties";
    private static final MessageCatalog CATALOG = InscribeMessages.catalog();
    private static final PluralSelector PLURALS = (locale, quantity) -> quantity.doubleValue() == 1D ? "one" : "other";
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.-]+)}");
    private static final int MAXIMUM_BASE_DEPTH = 4;
    private static final Map<String, String> VARIABLES = Map.ofEntries(
            Map.entry("added", "Messages added by a language update"),
            Map.entry("after", "Value after the change"),
            Map.entry("application", "Application fee"),
            Map.entry("argument", "Unexpected command argument"),
            Map.entry("before", "Value before the change"),
            Map.entry("command", "Command path"),
            Map.entry("cost", "Formatted transfer cost"),
            Map.entry("count", "Number of messages"),
            Map.entry("currency", "Payment currency"),
            Map.entry("enabled", "Transfer master switch state"),
            Map.entry("experience", "Experience cost"),
            Map.entry("extraction", "Extraction fee"),
            Map.entry("group", "Message category name"),
            Map.entry("hot_reload", "Hot-reload state"),
            Map.entry("kept", "Local edits kept by a language update"),
            Map.entry("key", "Setting, message, or parameter key"),
            Map.entry("language", "Server default language"),
            Map.entry("level", "Enchantment level"),
            Map.entry("line", "Message line number"),
            Map.entry("locale", "Locale identifier"),
            Map.entry("maximum", "Maximum accepted value"),
            Map.entry("menus", "Open transfer menus"),
            Map.entry("merge", "Equal-level merge state"),
            Map.entry("metrics", "bStats state"),
            Map.entry("money", "Money cost"),
            Map.entry("name", "Enchantment, setting, or language name"),
            Map.entry("number", "Page number"),
            Map.entry("page", "Current page"),
            Map.entry("pages", "Total pages"),
            Map.entry("parameter", "Parameter name"),
            Map.entry("path", "Setting path or local report path"),
            Map.entry("permission", "Required permission"),
            Map.entry("personal", "Personal locale when it differs from the server default"),
            Map.entry("plugin", "Plugin name"),
            Map.entry("range", "Supported Minecraft versions"),
            Map.entry("reason", "Failure reason"),
            Map.entry("remaining", "Time until the next language check is allowed"),
            Map.entry("repair", "Repair-cost increase state"),
            Map.entry("scheduler", "Scheduler implementation"),
            Map.entry("section", "Language editor section"),
            Map.entry("target", "Language selection target"),
            Map.entry("type", "Parameter or value type"),
            Map.entry("updated", "Messages changed by a language update"),
            Map.entry("updates", "Release notice state"),
            Map.entry("url", "Public report URL"),
            Map.entry("value", "Current, default, or raw value"),
            Map.entry("variables", "Placeholders valid for the selected message"),
            Map.entry("version", "Plugin version")
    );
    private final JavaPlugin plugin;
    private final ConfigService config;
    private final LanguageDirectory languages;
    private final LanguageSourceManifest manifest;
    private final LanguageUpstreamSync sync;
    private final RuntimeException sourceFailure;
    private volatile LocalizationSnapshot snapshot = LocalizationSnapshot.create(LocalizationCandidate.english(CATALOG, PLURALS));
    private volatile PluginLanguageService selections;
    private volatile int installedCatalogs;
    private volatile List<String> availableLocaleSnapshot = List.of("en_US");
    private volatile Map<String, LanguageMeta> metas = Map.of();

    public LanguageService(JavaPlugin plugin, ConfigService config) {
        this(plugin, config, GitHubLanguageSource.API_ROOT, GitHubLanguageSource.RAW_ROOT, LanguageUpstreamSync.MINIMUM_CHECK_INTERVAL);
    }

    LanguageService(JavaPlugin plugin, ConfigService config, URI apiRoot, URI rawRoot, Duration minimumCheckInterval) {
        this.plugin = plugin;
        this.config = config;
        languages = new LanguageDirectory(plugin.getDataFolder().toPath().resolve("languages"));
        LanguageSourceManifest loadedManifest = null;
        LanguageUpstreamSync loadedSync = null;
        RuntimeException failure = null;
        try {
            loadedManifest = LanguageSourceManifest.load(LanguageService.class.getClassLoader(), MANIFEST_RESOURCE);
            loadedSync = new LanguageUpstreamSync(new LanguageUpstreamSync.Options("Inscribe", CATALOG, languages, loadedManifest,
                    new GitHubLanguageSource(loadedManifest, "Inscribe", apiRoot, rawRoot),
                    new LanguageUpstreamStore(languages, loadedManifest), LanguageService::englishReference,
                    this::validateContent, this::commitUpstream, this::noteUpstreamWrite, this::applyUpstream,
                    plugin.getLogger()), minimumCheckInterval);
        } catch (RuntimeException exception) {
            failure = exception;
        }
        manifest = loadedManifest;
        sync = loadedSync;
        sourceFailure = failure;
    }

    public void start() throws IOException {
        languages.prepare();
        ensureEnglish();
        refreshInstalled();
        try {
            snapshot = prepareDefault(config.runtime().defaultLocale());
        } catch (RejectedFileException exception) {
            plugin.getLogger().log(Level.SEVERE, "Rejected " + exception.file() + " (" + exception.reason() + "); using built-in English.", exception);
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Configured language failed; using built-in English", exception);
        }
        selections = new PluginLanguageService(new PluginLanguageService.Options(
                languages.root().resolve("language-preferences.properties"), this::availableLocales,
                () -> config.runtime().defaultLocale(), this::defaultSnapshot, this::loadSelection,
                (locale, selected) -> {
                    config.update(candidate -> candidate.general.language = locale);
                }, plugin.getLogger()));
        config.setPreparation(candidate -> {
            try {
                Map<String, LocalizationSnapshot> prepared = new LinkedHashMap<>();
                for (String locale : languages.scan()) {
                    prepared.put(locale, load(locale));
                }
                LocalizationSnapshot selected = prepared.containsKey(candidate.defaultLocale())
                        ? prepared.get(candidate.defaultLocale()) : prepareDefault(candidate.defaultLocale());
                return () -> snapshot = selected;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        });
    }

    public void reload() throws IOException {
        refreshInstalled();
        selections.invalidate();
    }

    public void refresh(String locale) throws IOException {
        LocalizationSnapshot updated = load(locale);
        refreshInstalled();
        selections.cache(canonical(locale), updated);
    }

    public PluginLanguageService selections() {
        return selections;
    }

    public CompletableFuture<LanguageUpstreamSync.SyncReport> startUpstreamCheck() {
        if (sync == null) {
            return CompletableFuture.completedFuture(LanguageUpstreamSync.SyncReport.failed(
                    sourceDescription(), System.currentTimeMillis()));
        }
        return sync.checkAsync().thenApply(this::logUpstreamReport);
    }

    public BukkitLanguageSwitcher.LanguageUpdateAction updateAction() {
        return this::startUpstreamCheck;
    }

    public BukkitLanguageSwitcher.LocaleDisplayNames displayNames() {
        return locale -> metas.getOrDefault(locale, LanguageMeta.empty()).name().orElse(null);
    }

    public boolean needsInstall(String locale) {
        String required = canonical(locale);
        return isRepositoryLocale(required) && !languages.exists(required);
    }

    public CompletableFuture<String> requestInstall(String locale) {
        String required = canonical(locale);
        if (sync == null || !isRepositoryLocale(required)) {
            return CompletableFuture.failedFuture(new IOException("Language " + required
                    + " is not available from " + sourceDescription()));
        }
        plugin.getLogger().info("Installing Inscribe language " + required + " from " + sourceDescription() + "...");
        return sync.installAsync(required).thenApply(content -> {
            plugin.getLogger().info("Installed Inscribe language " + required + " to " + languages.languageFile(required) + ".");
            return content;
        });
    }

    public Optional<RuntimeException> sourceFailure() {
        return Optional.ofNullable(sourceFailure);
    }

    public String sourceDescription() {
        return manifest == null ? "unavailable" : manifest.repository() + "@" + manifest.branch();
    }

    public Optional<String> locale(Path file) {
        if (!languages.isLanguageFile(file)) {
            return Optional.empty();
        }
        return LanguageDirectory.localeFromName(file.getFileName().toString());
    }

    public String diagnostics() {
        StringBuilder report = new StringBuilder("Languages: ").append(installedCatalogs).append(" installed; source ")
                .append(sourceDescription());
        if (sync == null) {
            return report.append("; unavailable: ").append(sourceFailure == null ? "unknown" : sourceFailure.getMessage()).toString();
        }
        LanguageUpstreamSync.Status status = sync.status();
        return report.append("; revision ").append(status.revision().map(revision -> revision.substring(0, Math.min(7, revision.length()))).orElse("none"))
                .append("; last check ").append(status.checkedMillis() > 0L ? Instant.ofEpochMilli(status.checkedMillis()).toString() : "never")
                .append("; last error ").append(status.lastError().orElse("none"))
                .append(status.checking() ? "; checking" : "").toString();
    }

    public PluginLanguageEditor.Options editorOptions() {
        return new PluginLanguageEditor.Options(this::loadSelection, this::save);
    }

    public DirectorTextResolver directorResolver() {
        return (key, arguments) -> CATALOG.key(key.id()) == null ? ComponentText.literal(key.english()).miniMessage()
                : render(null, key, arguments).miniMessage();
    }

    public void send(CommandSender sender, String key, MessageArgs arguments) {
        ComponentMessenger.send(sender, ComponentText.markup("<#E8B65B>Inscribe</#E8B65B> <#4AA89F>›</#4AA89F> ")
                .append(render(sender, (TextKey) CATALOG.require(key), arguments)));
    }

    public void send(CommandSender sender, String key, String... replacements) {
        send(sender, key, arguments(replacements));
    }

    public String text(CommandSender sender, String key, String... replacements) {
        return render(sender, (TextKey) CATALOG.require(key), arguments(replacements)).legacy();
    }

    public ComponentText render(CommandSender sender, TextKey key, MessageArgs arguments) {
        LocalizationSnapshot selected = selections == null ? snapshot : selections.snapshot(
                sender instanceof Player player ? player.getUniqueId() : LanguageAudience.current());
        ResolvedText resolved = selected.resolve(key, arguments);
        String template = ComponentText.normalizeMarkup(resolved.template());
        TagResolver.Builder tags = TagResolver.builder();
        for (MessageArgument argument : arguments.arguments().values()) {
            Component value = argument.kind() == MessageArgumentKind.TRUSTED
                    ? MINI.deserialize(ComponentText.normalizeMarkup(String.valueOf(argument.value())))
                    : Component.text(String.valueOf(argument.value()));
            tags.resolver(Placeholder.component("arg_" + argument.name(), value));
        }
        Matcher placeholders = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (placeholders.find()) {
            String name = placeholders.group(1);
            String replacement = arguments.names().contains(name) ? "<arg_" + name + ">" : placeholders.group();
            placeholders.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        placeholders.appendTail(rendered);
        return ComponentText.component(MINI.deserialize(rendered.toString(), tags.build()));
    }

    @Override
    public void close() {
        if (selections != null) {
            selections.close();
        }
        if (sync != null) {
            sync.close();
        }
    }

    static Map<String, String> variables() {
        return VARIABLES;
    }

    static String englishReference() {
        return LanguageReferenceRenderer.render(CATALOG, header(CATALOG.englishLocale()));
    }

    private static List<String> header(String locale) {
        return LanguageFileHeader.render(new LanguageFileHeader.Options("Inscribe", locale,
                List.of("Inscribe puts its Inscribe › tag before its own chat replies; the tag is not part of these messages.",
                        "Shared language, editor, and command messages name the plugin through {plugin}."),
                List.of("Colors and styles: &0-&f, &k-&r.", "RGB colors: &#RRGGBB, &xRRGGBB, &x&R&R&G&G&B&B, [RRGGBB].",
                        "MiniMessage supports custom formatting."),
                VARIABLES));
    }

    private LocalizationSnapshot defaultSnapshot() {
        return snapshot;
    }

    private List<String> availableLocales() {
        return availableLocaleSnapshot;
    }

    private boolean isRepositoryLocale(String locale) {
        return manifest != null && manifest.contains(locale);
    }

    private String canonical(String locale) {
        String required = LanguageDirectory.requireLocale(locale);
        if (CATALOG.englishLocale().equalsIgnoreCase(required)) {
            return CATALOG.englishLocale();
        }
        return isRepositoryLocale(required) ? manifest.canonical(required) : required;
    }

    private LocalizationSnapshot loadSelection(String locale) throws Exception {
        String required = canonical(locale);
        if (!languages.exists(required) && isRepositoryLocale(required)) {
            if (sync == null) {
                throw new IOException("Language " + required + " is not installed and the language source is unavailable");
            }
            plugin.getLogger().info("Installing Inscribe language " + required + " from " + sourceDescription() + "...");
            sync.install(required);
            plugin.getLogger().info("Installed Inscribe language " + required + " to " + languages.languageFile(required) + ".");
            refreshInstalled();
        }
        return load(required);
    }

    private LocalizationSnapshot prepareDefault(String locale) throws IOException {
        String required = canonical(locale);
        if (!required.equals(CATALOG.englishLocale()) && !isRepositoryLocale(required) && !languages.exists(required)) {
            Path target = languages.languageFile(required);
            AtomicFileIO.writeString(target, LanguageReferenceRenderer.render(CATALOG, header(required),
                    LanguageMeta.of(required, CATALOG.englishLocale())));
            config.markWritten(target);
            refreshInstalled();
        }
        return load(required);
    }

    private void ensureEnglish() throws IOException {
        if (sync == null) {
            Path english = languages.languageFile(CATALOG.englishLocale());
            if (!Files.exists(english, LinkOption.NOFOLLOW_LINKS)) {
                AtomicFileIO.writeString(english, englishReference());
                config.markWritten(english);
            }
            return;
        }
        try {
            sync.mergeEnglish().ifPresent(result -> plugin.getLogger().info("Updated Inscribe language "
                    + CATALOG.englishLocale() + " from the built-in catalog: " + result.summary() + "."));
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Could not refresh the English language file", exception);
        }
    }

    private void validateContent(String locale, String content) throws IOException {
        parse(locale, content);
    }

    private <T> T commitUpstream(LanguageUpstreamSync.CommitAction<T> action) throws IOException {
        PluginLanguageService active = selections;
        return active == null ? action.apply() : active.commitUpdate(action::apply);
    }

    private void noteUpstreamWrite(Path file, String content) {
        config.markWritten(file);
    }

    private void applyUpstream(String locale, String content, LanguageCatalogMerge.MergeResult result) throws IOException {
        LocalizationSnapshot updated = parse(locale, content);
        refreshInstalled();
        PluginLanguageService active = selections;
        if (active != null) {
            active.cache(locale, updated);
        }
        if (LanguageDirectory.sameLocale(locale, config.runtime().defaultLocale())) {
            snapshot = updated;
        }
    }

    private LanguageUpstreamSync.SyncReport logUpstreamReport(LanguageUpstreamSync.SyncReport report) {
        String revision = report.revision().map(sha -> sha.substring(0, Math.min(7, sha.length()))).orElse("unknown");
        for (String locale : report.changedLocales()) {
            String source = locale.equals(CATALOG.englishLocale()) ? "the built-in catalog" : sourceDescription() + " (" + revision + ")";
            plugin.getLogger().info("Updated Inscribe language " + locale + " from " + source + ": " + report.merged().get(locale).summary() + ".");
        }
        if (report.coolingDown()) {
            plugin.getLogger().info("Inscribe language source " + sourceDescription() + " was checked recently; the next check is allowed in "
                    + report.cooldownRemaining() + ".");
        } else if (report.failure().isEmpty() && report.changedLocales().isEmpty() && report.failures().isEmpty()
                && report.revision().isPresent()) {
            plugin.getLogger().info("Inscribe languages match " + sourceDescription() + " (" + revision + ").");
        }
        return report;
    }

    private void refreshInstalled() throws IOException {
        List<String> installed = languages.scan();
        Set<String> choices = new LinkedHashSet<>();
        choices.add(CATALOG.englishLocale());
        if (manifest != null) {
            choices.addAll(manifest.locales());
        }
        choices.addAll(installed);
        Map<String, LanguageMeta> discovered = new LinkedHashMap<>();
        for (String locale : installed) {
            try {
                LanguageMeta meta = languages.meta(locale);
                if (!meta.isEmpty()) {
                    discovered.put(locale, meta);
                }
            } catch (IOException exception) {
                plugin.getLogger().log(Level.FINE, "Could not read language metadata for " + locale, exception);
            }
        }
        List<String> sorted = new ArrayList<>(choices);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        metas = Map.copyOf(discovered);
        installedCatalogs = installed.size();
        availableLocaleSnapshot = List.copyOf(sorted);
    }

    private LocalizationSnapshot load(String locale) throws IOException {
        String required = canonical(locale);
        Path path = languages.languageFile(required);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return LocalizationSnapshot.create(LocalizationCandidate.english(CATALOG, PLURALS));
        }
        String file = "languages/" + required + ".toml";
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new RejectedFileException(file, "not a regular file", null);
        }
        if (Files.size(path) > LanguageDirectory.MAXIMUM_BYTES) {
            throw new RejectedFileException(file, "file exceeds 2 MiB", null);
        }
        String source;
        try {
            source = Files.readString(path);
        } catch (CharacterCodingException exception) {
            throw new RejectedFileException(file, "file is not valid UTF-8", exception);
        }
        try {
            return parse(required, source);
        } catch (IOException | RuntimeException exception) {
            throw new RejectedFileException(file, exception);
        }
    }

    private LocalizationSnapshot parse(String locale, String source) throws IOException {
        List<LocaleOverlay> overlays = new ArrayList<>();
        overlays.add(overlay(locale, TomlLanguageParser.parseText(source, CATALOG.ids())));
        overlays.addAll(baseOverlays(locale, source));
        return LocalizationSnapshot.create(new LocalizationCandidate(CATALOG, overlays, PLURALS));
    }

    private List<LocaleOverlay> baseOverlays(String locale, String source) throws IOException {
        List<LocaleOverlay> overlays = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(locale.replace('-', '_').toLowerCase(Locale.ROOT));
        LanguageMeta meta = meta(locale, source);
        for (int depth = 0; depth < MAXIMUM_BASE_DEPTH && meta.base().isPresent(); depth++) {
            String base = canonical(meta.base().get());
            if (!visited.add(base.replace('-', '_').toLowerCase(Locale.ROOT)) || base.equals(CATALOG.englishLocale())
                    || !languages.exists(base)) {
                break;
            }
            String content = languages.read(base);
            overlays.add(overlay(base, TomlLanguageParser.parseValidText(content, CATALOG)));
            meta = meta(base, content);
        }
        return overlays;
    }

    private static LanguageMeta meta(String locale, String source) {
        try {
            return LanguageMeta.parse(source).withoutSelfBase(locale);
        } catch (IOException exception) {
            return LanguageMeta.empty();
        }
    }

    private static LocaleOverlay overlay(String locale, Map<String, String> values) {
        LocaleOverlay.Builder overlay = LocaleOverlay.builder(locale);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            overlay.text(entry.getKey(), entry.getValue());
        }
        return overlay.build();
    }

    private LocalizationSnapshot save(PluginLanguageEditor.Edit edit) throws IOException {
        String locale = canonical(edit.locale());
        Path target = languages.languageFile(locale);
        LocalizationSnapshot updated = LanguageFileEditor.update(target, source -> {
            LocalizationSnapshot current = parse(locale, source);
            if (!current.value(CATALOG.require(edit.key())).equals(edit.expected())) {
                throw new IOException("Message changed while the editor was open; reopen it");
            }
            String content = TomlLanguageEditor.upsert(source, edit.key(), edit.value()).content();
            return new LanguageFileEditor.Prepared<>(content, parse(locale, content));
        });
        config.markWritten(target);
        if (LanguageDirectory.sameLocale(locale, config.runtime().defaultLocale())) {
            snapshot = updated;
        }
        return updated;
    }

    private MessageArgs arguments(String[] replacements) {
        if (replacements.length % 2 != 0) {
            throw new IllegalArgumentException("Message replacements require name/value pairs");
        }
        MessageArgs.Builder result = MessageArgs.builder();
        for (int index = 0; index < replacements.length; index += 2) {
            result.untrusted(replacements[index], replacements[index + 1]);
        }
        return result.build();
    }
}
