package com.volmit.inscribe;

import art.arcane.volmlib.util.diagnostics.BukkitDebugDump;
import art.arcane.volmlib.util.diagnostics.DebugDumpContributor;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.inscribe.command.CommandService;
import com.volmit.inscribe.config.ConfigService;
import com.volmit.inscribe.config.RejectedFileException;
import com.volmit.inscribe.config.RuntimeConfig;
import com.volmit.inscribe.enchant.TransferService;
import com.volmit.inscribe.localization.InscribeMessages;
import com.volmit.inscribe.localization.LanguageService;
import com.volmit.inscribe.metrics.MetricsService;
import com.volmit.inscribe.presentation.InscribePresentation;
import com.volmit.inscribe.update.UpdateNotifier;
import io.github.slimjar.app.builder.SpigotApplicationBuilder;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class Inscribe extends JavaPlugin {
    private ConfigService config;
    private LanguageService language;
    private CommandService commands;
    private TransferService transfers;
    private BukkitDebugDump debug;
    private MetricsService metrics;
    private UpdateNotifier updates;
    private final Set<String> pendingLanguages = ConcurrentHashMap.newKeySet();
    private volatile boolean stopping;

    public Inscribe() {
        getLogger().info("Loading Inscribe libraries...");
        try {
            new SpigotApplicationBuilder(this).build();
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Inscribe could not load its runtime libraries. The first start downloads them from "
                    + "https://repo.maven.apache.org, so the server needs outbound HTTPS access to that host.", exception);
            throw exception;
        }
    }

    @Override
    public void onEnable() {
        long startedNanos = System.nanoTime();
        try {
            config = new ConfigService(getDataFolder());
            config.reload();
            language = new LanguageService(this, config);
            language.start();
            metrics = new MetricsService(this);
            metrics.reconfigure(config.runtime().metricsEnabled());
            updates = new UpdateNotifier(this, () -> config.runtime().updateNotifications());
            config.setInstalled(snapshot -> FoliaScheduler.runGlobal(this, this::configurationInstalled));
            transfers = new TransferService(this, config::runtime, language);
            getServer().getPluginManager().registerEvents(transfers, this);
            debug = BukkitDebugDump.create(this, new BukkitDebugDump.Options(
                    () -> config.runtime().debugUpload(), this::captureDiagnostics,
                    new BukkitDebugDump.Presentation("/inscribe debug dump", "/inscribe debug",
                            InscribePresentation.theme(), (key, arguments) -> language.render(null, key, arguments)),
                    "inscribe.debug"));
            commands = new CommandService(this, config, language, debug::request, transfers);
            commands.register();
            config.startWatching(this::applyFileChanges, this::notifyOperators);
            language.sourceFailure().ifPresent(failure -> getLogger().log(Level.WARNING,
                    "Language source is unavailable; built-in English and installed language files remain active", failure));
            language.startUpstreamCheck();
            requestConfiguredLanguage();
            if (config.runtime().splashScreen()) {
                InscribePresentation.splash(this, startedNanos);
            }
            getLogger().info("Inscribe ready. Java " + Runtime.version().feature()
                    + "; " + (FoliaScheduler.isFolia(this) ? "entity" : "server") + " scheduling.");
        } catch (RejectedFileException exception) {
            getLogger().log(Level.SEVERE, "Inscribe could not enable: " + exception.file() + " is invalid ("
                    + exception.reason() + ").", exception);
            getServer().getPluginManager().disablePlugin(this);
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "Inscribe could not enable safely", exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public synchronized void onDisable() {
        stopping = true;
        if (config != null) {
            closeService(config, "configuration");
        }
        if (transfers != null) {
            closeService(transfers, "enchantment transfers");
        }
        if (commands != null) {
            closeService(commands, "commands and editors");
        }
        if (language != null) {
            closeService(language, "languages");
        }
        if (debug != null) {
            closeService(debug, "diagnostics");
        }
        if (metrics != null) {
            closeService(metrics, "bStats metrics");
        }
        if (updates != null) {
            closeService(updates, "release notifications");
        }
        HandlerList.unregisterAll(this);
        FoliaScheduler.cancelTasks(this);
    }

    private synchronized void applyFileChanges(Set<Path> files) throws IOException {
        if (stopping) {
            return;
        }
        RuntimeConfig before = config.runtime();
        Path active = config.languageFile(before.defaultLocale()).toAbsolutePath().normalize();
        if (files.contains(config.configFile().toPath()) || files.contains(active)) {
            config.reload();
            language.reload();
            if (config.runtime() != before) {
                transfers.invalidate();
            }
            requestConfiguredLanguage();
            return;
        }
        for (Path file : files) {
            Optional<String> locale = language.locale(file);
            if (locale.isPresent()) {
                language.refresh(locale.get());
            }
        }
    }

    private void requestConfiguredLanguage() {
        String locale = config.runtime().defaultLocale();
        if (stopping || !language.needsInstall(locale) || !pendingLanguages.add(locale)) {
            return;
        }
        language.requestInstall(locale).whenComplete((content, failure) -> {
            pendingLanguages.remove(locale);
            if (failure != null) {
                getLogger().log(Level.WARNING, "Could not install language " + locale + "; using English until it is available", failure);
                return;
            }
            if (!stopping && locale.equals(config.runtime().defaultLocale())) {
                FoliaScheduler.runGlobal(this, this::activateInstalledLanguage);
            }
        });
    }

    private synchronized void activateInstalledLanguage() {
        if (stopping) {
            return;
        }
        try {
            config.reload();
            language.reload();
        } catch (IOException | RuntimeException exception) {
            getLogger().log(Level.WARNING, "Could not activate the installed server language", exception);
        }
    }

    private void configurationInstalled() {
        if (stopping) {
            return;
        }
        metrics.reconfigure(config.runtime().metricsEnabled());
        updates.reconfigure();
    }

    private void notifyOperators(boolean applied) {
        if (stopping || !config.runtime().notifyOperators()) {
            return;
        }
        TextKey key = (TextKey) InscribeMessages.catalog().require(applied ? "command.feedback.files_applied" : "command.feedback.files_rejected");
        FoliaScheduler.runGlobal(this, () -> {
            for (Player player : getServer().getOnlinePlayers()) {
                FoliaScheduler.runEntity(this, player, () -> {
                    if (player.hasPermission("inscribe.config")) {
                        ComponentMessenger.sendActionBar(player, language.render(player, key, MessageArgs.empty()));
                    }
                });
            }
        });
    }

    private DebugDumpContributor.Report captureDiagnostics() {
        RuntimeConfig snapshot = config.runtime();
        String report = "Enabled: " + snapshot.enabled() + "\nCurrency: " + snapshot.currency()
                + "\nCreative transfers: " + snapshot.allowCreative()
                + "\nUnsafe levels: " + snapshot.allowUnsafe()
                + "\nMerge equal levels: " + snapshot.mergeEqualLevels()
                + "\nIncrease repair cost: " + snapshot.increaseRepairCost()
                + "\nDefault locale: " + snapshot.defaultLocale()
                + "\nbStats enabled: " + snapshot.metricsEnabled() + "\nbStats initialized: " + (metrics != null && metrics.initialized())
                + "\nbStats plugin ID: 34581"
                + "\nUpdate notifications: " + snapshot.updateNotifications()
                + "\nRelease source configured: " + updates.configured()
                + "\nRelease repository: " + updates.repository()
                + "\nScheduler: " + (FoliaScheduler.isFolia(this) ? "entity" : "server")
                + "\nHot reload: " + config.watching() + "; poll " + snapshot.pollIntervalMillis()
                + " ms; cooldown " + snapshot.cooldownMillis() + " ms; operator notices " + snapshot.notifyOperators()
                + "\nExtraction price: " + snapshot.extractionCost() + "; per level: " + snapshot.extractionCostPerLevel()
                + "\nApplication price: " + snapshot.applicationCost() + "; per level: " + snapshot.applicationCostPerLevel()
                + "\nEnchantment rules: " + snapshot.enchantments().size()
                + "\nActive transfer menus: " + transfers.activeSessions()
                + "\nQueued inventory actions: " + transfers.pendingActions()
                + "\n" + language.diagnostics() + "\n";
        return () -> report;
    }

    private void closeService(AutoCloseable service, String name) {
        try {
            service.close();
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "Could not close Inscribe " + name, exception);
        }
    }
}
