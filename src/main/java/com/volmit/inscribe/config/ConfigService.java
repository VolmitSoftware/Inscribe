package com.volmit.inscribe.config;

import art.arcane.volmlib.util.config.ConfigEditorDocument;
import art.arcane.volmlib.util.config.TomlCodec;
import art.arcane.volmlib.util.config.TomlDocumentEditor;
import art.arcane.volmlib.util.io.AtomicFileIO;
import art.arcane.volmlib.util.localization.LanguageDirectory;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ConfigService implements AutoCloseable {
    private static final String FILE_NAME = "config.toml";
    private static final long MAX_BYTES = 2L * 1024L * 1024L;
    private static final long TICK_MILLIS = 250L;
    private static final String MISSING = "\u0000missing";
    private static final Logger LOGGER = Logger.getLogger("Inscribe");
    private final Path file;
    private final Path languages;
    private final Object watchLock = new Object();
    private final Map<Path, String> observed = new LinkedHashMap<>();
    private volatile RuntimeConfig current = RuntimeConfig.from(new InscribeConfig());
    private String source = TomlCodec.toToml(new InscribeConfig(), "inscribe");
    private Function<RuntimeConfig, Runnable> preparation = config -> () -> {};
    private Consumer<RuntimeConfig> installed = config -> {};
    private ScheduledExecutorService watcher;
    private LongSupplier clock = System::currentTimeMillis;
    private Map<Path, String> pending;
    private long pendingSince;
    private long lastPoll = Long.MIN_VALUE;
    private boolean wasEnabled = true;

    public ConfigService(File folder) {
        file = folder.toPath().resolve(FILE_NAME).toAbsolutePath().normalize();
        languages = file.getParent().resolve("languages");
    }

    public synchronized void setPreparation(Function<RuntimeConfig, Runnable> preparation) {
        this.preparation = preparation;
    }

    public synchronized void setInstalled(Consumer<RuntimeConfig> installed) {
        this.installed = installed;
    }

    public synchronized void reload() throws IOException {
        if (!Files.exists(file)) {
            write(source);
        }
        install(read(), false);
    }

    public RuntimeConfig runtime() {
        return current;
    }

    public synchronized InscribeConfig editableCopy() throws IOException {
        return TomlCodec.fromToml(source, InscribeConfig.class);
    }

    public synchronized RuntimeConfig update(Consumer<InscribeConfig> editor) throws IOException {
        InscribeConfig candidate = editableCopy();
        editor.accept(candidate);
        install(TomlCodec.toToml(candidate, "inscribe"), true);
        return current;
    }

    public synchronized ConfigEditorDocument document() throws IOException {
        return ConfigEditorDocument.fromTomlWithDefaults(read(), TomlCodec.toToml(new InscribeConfig(), "inscribe"));
    }

    public synchronized ConfigEditorDocument edit(ConfigEditorDocument.Edit edit) throws IOException {
        ConfigEditorDocument latest = document();
        if (!latest.value(edit.path()).equals(edit.expected())) {
            throw new IOException("Setting changed while the editor was open; reopen it");
        }
        install(TomlDocumentEditor.set(latest.source(), edit.path(), edit.value()), true);
        return document();
    }

    public synchronized void startWatching(Changes changes, Consumer<Boolean> outcome) {
        if (watcher != null) {
            return;
        }
        baseline();
        watcher = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "Inscribe-HotReload");
            thread.setDaemon(true);
            return thread;
        });
        watcher.scheduleWithFixedDelay(() -> poll(changes, outcome), TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
    }

    public void markWritten(Path written) {
        Path normalized = written.toAbsolutePath().normalize();
        synchronized (watchLock) {
            observed.put(normalized, read(normalized, true));
            pending = null;
        }
    }

    public File configFile() {
        return file.toFile();
    }

    public Path languageFile(String locale) {
        return languages.resolve(locale + ".toml");
    }

    public synchronized boolean watching() {
        return current.hotReloadEnabled() && watcher != null && !watcher.isShutdown();
    }

    @Override
    public void close() {
        ScheduledExecutorService active;
        synchronized (this) {
            active = watcher;
            watcher = null;
        }
        if (active == null) {
            return;
        }
        active.shutdownNow();
        try {
            if (!active.awaitTermination(5L, TimeUnit.SECONDS)) {
                LOGGER.warning("The Inscribe file watcher did not stop within five seconds.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    void clock(LongSupplier clock) {
        this.clock = clock;
    }

    void baseline() {
        synchronized (watchLock) {
            observed.clear();
            observed.putAll(snapshot());
            pending = null;
        }
    }

    void poll(Changes changes, Consumer<Boolean> outcome) {
        Set<Path> changed;
        try {
            changed = detect();
        } catch (RuntimeException exception) {
            LOGGER.log(Level.SEVERE, "The Inscribe file watcher failed while checking files", exception);
            return;
        }
        if (changed.isEmpty()) {
            return;
        }
        try {
            changes.apply(changed);
            LOGGER.info("Applied Inscribe configuration and language file changes.");
            outcome.accept(true);
        } catch (RejectedFileException exception) {
            LOGGER.log(Level.SEVERE, "Rejected " + exception.file() + " (" + exception.reason()
                    + "); the last known good settings remain active.", exception);
            outcome.accept(false);
        } catch (IOException | RuntimeException exception) {
            LOGGER.log(Level.SEVERE, "Could not apply Inscribe file changes; the last known good settings remain active.", exception);
            outcome.accept(false);
        }
    }

    private Set<Path> detect() {
        RuntimeConfig settings = current;
        long now = clock.getAsLong();
        synchronized (watchLock) {
            if (lastPoll != Long.MIN_VALUE && now - lastPoll < settings.pollIntervalMillis()) {
                return Set.of();
            }
            lastPoll = now;
            Map<Path, String> state = snapshot();
            if (!settings.hotReloadEnabled() || !wasEnabled) {
                wasEnabled = settings.hotReloadEnabled();
                observed.clear();
                observed.putAll(state);
                pending = null;
                return Set.of();
            }
            if (state.equals(observed)) {
                pending = null;
                return Set.of();
            }
            if (!state.equals(pending)) {
                pending = state;
                pendingSince = now;
                return Set.of();
            }
            if (now - pendingSince < settings.cooldownMillis()) {
                return Set.of();
            }
            Set<Path> changed = new LinkedHashSet<>();
            for (Map.Entry<Path, String> entry : state.entrySet()) {
                if (!Objects.equals(entry.getValue(), observed.get(entry.getKey()))) {
                    changed.add(entry.getKey());
                }
            }
            observed.clear();
            observed.putAll(state);
            pending = null;
            return changed;
        }
    }

    private Map<Path, String> snapshot() {
        Set<Path> files = new LinkedHashSet<>();
        files.add(file);
        files.addAll(observed.keySet());
        files.addAll(languageFiles());
        Map<Path, String> state = new LinkedHashMap<>();
        for (Path watched : files) {
            state.put(watched, read(watched, false));
        }
        return state;
    }

    private List<Path> languageFiles() {
        if (!Files.isDirectory(languages, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<Path> found = new ArrayList<>();
        try (Stream<Path> entries = Files.list(languages)) {
            for (Path entry : entries.toList()) {
                if (LanguageDirectory.localeFromName(entry.getFileName().toString()).isPresent()) {
                    found.add(entry.toAbsolutePath().normalize());
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not list " + languages, exception);
        }
        return found;
    }

    private static String read(Path watched, boolean written) {
        try {
            if (!Files.isRegularFile(watched, LinkOption.NOFOLLOW_LINKS)) {
                return MISSING;
            }
            if (Files.size(watched) > MAX_BYTES) {
                return MISSING + ":oversized:" + Files.size(watched) + ":" + Files.getLastModifiedTime(watched);
            }
            return Files.readString(watched, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return written ? MISSING : MISSING + ":" + exception.getClass().getSimpleName();
        }
    }

    private void install(String content, boolean save) throws IOException {
        RuntimeConfig prepared;
        try {
            prepared = RuntimeConfig.from(TomlCodec.fromToml(content, InscribeConfig.class));
        } catch (IOException | RuntimeException exception) {
            throw new RejectedFileException(FILE_NAME, exception);
        }
        if (prepared.equals(current)) {
            prepared = current;
        }
        Runnable installation;
        try {
            installation = preparation.apply(prepared);
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        } catch (RuntimeException exception) {
            throw new IOException("Could not prepare Inscribe settings; previous settings remain active", exception);
        }
        if (save) {
            write(content);
        }
        source = content;
        current = prepared;
        try {
            installation.run();
            installed.accept(prepared);
        } catch (RuntimeException exception) {
            throw new IOException("Could not apply Inscribe settings", exception);
        }
    }

    private String read() throws IOException {
        if (Files.size(file) > MAX_BYTES) {
            throw new RejectedFileException(FILE_NAME, "file exceeds 2 MiB", null);
        }
        return Files.readString(file);
    }

    private void write(String content) throws IOException {
        AtomicFileIO.writeString(file, content);
        markWritten(file);
    }

    @FunctionalInterface
    public interface Changes {
        void apply(Set<Path> files) throws IOException;
    }
}
