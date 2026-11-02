package com.volmit.inscribe.command;

import art.arcane.volmlib.util.config.BukkitConfigEditor;
import art.arcane.volmlib.util.director.DirectorEngineOptions;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.context.DirectorContextRegistry;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.director.runtime.DirectorInvocation;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import art.arcane.volmlib.util.director.runtime.DirectorSender;
import art.arcane.volmlib.util.localization.BukkitLanguageSwitcher;
import art.arcane.volmlib.util.localization.LanguageAudience;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import com.volmit.inscribe.localization.InscribeMessages;
import com.volmit.inscribe.config.ConfigService;
import com.volmit.inscribe.presentation.InscribePresentation;
import com.volmit.inscribe.config.RuntimeConfig;
import com.volmit.inscribe.enchant.TransferService;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.inscribe.localization.LanguageService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.logging.Level;

public final class CommandService implements CommandExecutor, TabCompleter, AutoCloseable {
    private static final DirectorMiniMenu.Theme THEME = InscribePresentation.theme();
    private final JavaPlugin plugin;
    private final ConfigService config;
    private final LanguageService language;
    private final BiConsumer<CommandSender, Boolean> debug;
    private final TransferService transfers;
    private final DirectorRuntimeEngine director;
    private BukkitConfigEditor editor;
    private BukkitLanguageSwitcher switcher;

    public CommandService(JavaPlugin plugin, ConfigService config, LanguageService language,
                          BiConsumer<CommandSender, Boolean> debug, TransferService transfers) {
        this.plugin = plugin;
        this.config = config;
        this.language = language;
        this.debug = debug;
        this.transfers = transfers;
        DirectorContextRegistry contexts = new DirectorContextRegistry();
        contexts.register(CommandSender.class, (invocation, values) ->
                invocation.getSender() instanceof Sender sender ? sender.sender() : null);
        director = DirectorEngineFactory.create(new Commands(this), DirectorEngineOptions.builder()
                .contexts(contexts).textResolver(language.directorResolver()).build());
    }

    public void register() {
        editor = BukkitConfigEditor.register(plugin, new BukkitConfigEditor.Options(config::document, config::edit,
                new BukkitConfigEditor.Presentation("inscribe.config", THEME, language.directorResolver())));
        editor.configureLayout(ConfigEditorLayout.create(player -> switcher.open(player)));
        editor.configureDynamicSections(ConfigEditorLayout.dynamicSections());
        switcher = BukkitLanguageSwitcher.register(plugin, language.selections(), new BukkitLanguageSwitcher.Options(
                "inscribe", "inscribe.config", THEME, language.directorResolver(), language.editorOptions(), null,
                language.updateAction(), language.displayNames()));
        PluginCommand root = plugin.getCommand("inscribe");
        if (root == null) {
            throw new IllegalStateException("Missing inscribe command descriptor");
        }
        root.setExecutor(this);
        root.setTabCompleter(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return LanguageAudience.call(sender instanceof Player player ? player.getUniqueId() : null,
                () -> execute(sender, label, args));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length > 1 && args[0].equalsIgnoreCase("language") && permitted(sender, "language")) {
            return switcher.complete(sender, Arrays.copyOfRange(args, 1, args.length));
        }
        if (args.length > 1 && args[0].equalsIgnoreCase("debug")) {
            List<String> candidates = args.length == 2 ? List.of("dump", "version")
                    : args.length == 3 && args[1].equalsIgnoreCase("dump") && sender.hasPermission("inscribe.debug")
                    ? List.of("upload=false", "upload=true") : List.of();
            String partial = args[args.length - 1].toLowerCase(Locale.ROOT);
            return candidates.stream().filter(value -> value.startsWith(partial))
                    .filter(value -> !value.equals("dump") || sender.hasPermission("inscribe.debug"))
                    .filter(value -> !value.equals("version") || sender.hasPermission("inscribe.command")).toList();
        }
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> choices = new ArrayList<>();
        for (String choice : List.of("help", "config", "language", "status", "debug")) {
            if (choice.startsWith(prefix) && permitted(sender, choice)) {
                choices.add(choice);
            }
        }
        return choices;
    }

    @Override
    public void close() {
        if (editor != null) {
            editor.close();
        }
        if (switcher != null) {
            switcher.close();
        }
    }

    private boolean execute(CommandSender sender, String label, String[] args) {
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (!permitted(sender, action) || (action.equals("debug") && args.length > 1
                && !permitted(sender, args[1].toLowerCase(Locale.ROOT)))) {
            language.send(sender, "transfer.denied");
            return true;
        }
        try {
            if (action.equals("language")) {
                return switcher.command(sender, Arrays.copyOfRange(args, 1, args.length));
            }
            Optional<DirectorMiniMenu.DirectorHelpPage> help = DirectorMiniMenu.resolveHelp(director, Arrays.asList(args));
            if (help.isPresent()) {
                help(sender, help.get());
                return true;
            }
            if (!director.execute(new DirectorInvocation(new Sender(sender), label, Arrays.asList(args))).isHandled()) {
                DirectorMiniMenu.resolveHelp(director, List.of()).ifPresent(page -> help(sender, page));
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Command failed: " + action, exception);
            language.send(sender, "command.failed");
        }
        return true;
    }

    private void help(CommandSender sender, DirectorMiniMenu.DirectorHelpPage page) {
        List<DirectorRuntimeNode> visible = new ArrayList<>();
        for (DirectorRuntimeNode node : page.entries()) {
            if (permitted(sender, node.getDescriptor().getName())) {
                visible.add(node);
            }
        }
        DirectorMiniMenu.deliver(sender, page.withEntries(visible), THEME, language.directorResolver());
    }

    private boolean permitted(CommandSender sender, String action) {
        return switch (action) {
            case "config", "settings" -> sender.hasPermission("inscribe.config");
            case "debug" -> sender.hasPermission("inscribe.debug") || sender.hasPermission("inscribe.command");
            case "dump" -> sender.hasPermission("inscribe.debug");
            case "language" -> sender.hasPermission("inscribe.language.self")
                    || sender.hasPermission("inscribe.config")
                    || sender.hasPermission("volmit.language.self")
                    || sender.hasPermission("volmit.language.admin");
            default -> sender.hasPermission("inscribe.command");
        };
    }

    private void status(CommandSender sender) {
        RuntimeConfig current = config.runtime();
        List<String> entries = List.of(
                entry(sender, "status.configuration", MessageArgs.builder()
                        .trusted("enabled", state(sender, current.enabled()))
                        .untrusted("language", current.defaultLocale())
                        .trusted("hot_reload", state(sender, config.watching())).build()),
                entry(sender, "status.pricing", MessageArgs.builder()
                        .untrusted("currency", current.currency().name())
                        .untrusted("extraction", Integer.toString(current.extractionCost()))
                        .untrusted("application", Integer.toString(current.applicationCost())).build()),
                entry(sender, "status.transfers", MessageArgs.builder()
                        .untrusted("menus", Integer.toString(transfers.activeSessions()))
                        .trusted("merge", state(sender, current.mergeEqualLevels()))
                        .trusted("repair", state(sender, current.increaseRepairCost())).build()),
                entry(sender, "status.services", MessageArgs.builder()
                        .trusted("metrics", state(sender, current.metricsEnabled()))
                        .trusted("updates", state(sender, current.updateNotifications())).build()),
                entry(sender, "status.compatibility", MessageArgs.builder()
                        .untrusted("range", "26.1-26.3")
                        .untrusted("scheduler", FoliaScheduler.isFolia(plugin) ? "Folia region" : "Bukkit main-thread").build()));
        String title = language.render(sender, key("status.header"), MessageArgs.empty()).plain();
        DirectorMiniMenu.deliverContent(sender, new DirectorMiniMenu.ContentMenu(title, "/inscribe status", "/inscribe",
                entries, "", 1, entries.size()), THEME, language.directorResolver());
    }

    private String entry(CommandSender sender, String id, MessageArgs arguments) {
        return ComponentText.markup("<" + THEME.muted() + ">⇀</" + THEME.muted() + "> ")
                .append(language.render(sender, key(id), arguments)).miniMessage();
    }

    private String state(CommandSender sender, boolean enabled) {
        return language.render(sender, key(enabled ? "status.state.enabled" : "status.state.disabled"), MessageArgs.empty()).miniMessage();
    }

    private static TextKey key(String id) {
        return (TextKey) InscribeMessages.catalog().require(id);
    }

    private void version(CommandSender sender) {
        ComponentMessenger.send(sender, language.render(sender,
                (TextKey) InscribeMessages.catalog().require("command.version"),
                MessageArgs.builder().untrusted("version", plugin.getDescription().getVersion()).build()));
    }

    private record Sender(CommandSender sender) implements DirectorSender {
        @Override
        public String getName() {
            return sender.getName();
        }

        @Override
        public boolean isPlayer() {
            return sender instanceof Player;
        }

        @Override
        public void sendMessage(String message) {
            sender.sendMessage(message);
        }
    }

    @Director(name = "inscribe", aliases = {"ins"}, description = "Inscribe help and administration", descriptionKey = "command.description.root")
    public static final class Commands {
        private final CommandService service;
        private DebugCommands debug;

        public Commands(CommandService service) {
            this.service = service;
            this.debug = new DebugCommands(service);
        }

        @Director(name = "config", aliases = {"settings"}, sync = true, description = "Edit Inscribe settings", descriptionKey = "command.description.config")
        public void config(@Param(name = "sender", contextual = true) CommandSender sender) {
            if (!service.permitted(sender, "config")) {
                service.language.send(sender, "transfer.denied");
            } else if (sender instanceof Player player) {
                service.editor.open(player);
            } else {
                service.language.send(sender, "command.player");
            }
        }

        @Director(name = "language", sync = true, description = "Select or edit languages", descriptionKey = "command.description.language")
        public void language(@Param(name = "sender", contextual = true) CommandSender sender) {
            service.switcher.open(sender);
        }

        @Director(name = "status", sync = true, description = "Show settings, open menus, and compatibility", descriptionKey = "command.description.status")
        public void status(@Param(name = "sender", contextual = true) CommandSender sender) {
            service.status(sender);
        }

        @Director(name = "version", hidden = true, sync = true, description = "Show the installed version", descriptionKey = "command.description.version")
        public void version(@Param(name = "sender", contextual = true) CommandSender sender) {
            service.version(sender);
        }
    }
    @Director(name = "debug", description = "Diagnostic tools", descriptionKey = "command.description.debug")
    public static final class DebugCommands {
        private final CommandService service;

        public DebugCommands(CommandService service) {
            this.service = service;
        }

        @Director(name = "dump", sync = true, description = "Save a diagnostic report", descriptionKey = "command.description.debug_dump")
        public void dump(@Param(name = "upload", defaultValue = "false", description = "Upload only when explicitly enabled.", descriptionKey = "command.parameter.upload") boolean upload,
                         @Param(name = "sender", contextual = true) CommandSender sender) {
            service.debug.accept(sender, upload);
        }

        @Director(name = "version", sync = true, description = "Show the installed version", descriptionKey = "command.description.version")
        public void version(@Param(name = "sender", contextual = true) CommandSender sender) {
            service.version(sender);
        }
    }

}
