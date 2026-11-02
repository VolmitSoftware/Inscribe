package com.volmit.inscribe.command;

import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import com.volmit.inscribe.config.ConfigService;
import com.volmit.inscribe.enchant.TransferService;
import com.volmit.inscribe.localization.LanguageService;
import com.volmit.inscribe.presentation.InscribePresentation;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.bukkit.plugin.PluginDescriptionFile;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.diagnostics.BukkitDebugMessages;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import java.util.List;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import com.volmit.inscribe.localization.InscribeMessages;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class CommandServiceTest {
    @Test
    void debugHelpExposesActionsWhileRootVersionIsHidden() {
        DirectorRuntimeEngine engine = DirectorEngineFactory.create(new CommandService.Commands(null));
        DirectorMiniMenu.DirectorHelpPage root = DirectorMiniMenu.resolveHelp(engine, List.of()).orElseThrow();
        assertFalse(root.entries().stream().anyMatch(entry -> entry.getDescriptor().getName().equals("version")));
        DirectorMiniMenu.DirectorHelpPage debug = DirectorMiniMenu.resolveHelp(engine, List.of("debug")).orElseThrow();
        assertEquals("debug", debug.node().getDescriptor().getName());
        assertEquals(List.of("dump", "version"), debug.entries().stream().map(entry -> entry.getDescriptor().getName()).sorted().toList());
    }

    @Test
    void dumpRequiresPermissionAndPreservesExplicitUploadChoice() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        LanguageService language = mock(LanguageService.class);
        when(language.directorResolver()).thenReturn((key, args) -> key.id());
        CommandSender sender = mock(CommandSender.class);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Boolean> upload = new AtomicReference<>();
        try (CommandService service = new CommandService(plugin, mock(ConfigService.class), language,
                (recipient, requested) -> { calls.incrementAndGet(); upload.set(requested); }, mock(TransferService.class))) {
            service.onCommand(sender, null, "inscribe", new String[]{"debug", "dump", "upload=true"});
            assertEquals(0, calls.get());
            when(sender.hasPermission("inscribe.debug")).thenReturn(true);
            service.onCommand(sender, null, "inscribe", new String[]{"debug", "dump"});
            assertEquals(1, calls.get());
            assertFalse(upload.get());
            service.onCommand(sender, null, "inscribe", new String[]{"debug", "dump", "upload=true"});
            assertEquals(2, calls.get());
            assertTrue(upload.get());
            assertEquals(List.of("dump"), service.onTabComplete(sender, null, "inscribe", new String[]{"debug", ""}));
        }
    }
    @Test
    void abbreviatedOperatorCommandsStillRequireTheirPermission() throws IOException {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        LanguageService language = mock(LanguageService.class);
        when(language.directorResolver()).thenReturn((key, args) -> key.id());
        ConfigService config = mock(ConfigService.class);
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("inscribe.command")).thenReturn(true);
        try (CommandService service = new CommandService(plugin, config, language, (recipient, upload) -> { }, mock(TransferService.class))) {
            service.onCommand(sender, null, "inscribe", new String[]{"conf"});
            service.onCommand(sender, null, "inscribe", new String[]{"confgi"});
            service.onCommand(sender, null, "inscribe", new String[]{"settings"});
            verify(language, times(3)).send(sender, "transfer.denied");
            verify(config, never()).document();
            assertFalse(service.onTabComplete(sender, null, "inscribe", new String[]{""}).contains("config"));
            assertTrue(service.onTabComplete(sender, null, "inscribe", new String[]{""}).contains("status"));
            assertFalse(service.onTabComplete(sender, null, "inscribe", new String[]{""}).contains("reload"));
        }
    }

    @Test
    void bothVersionPathsRenderOneUnprefixedVersionLine() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(new File("build/version-test"));
        when(plugin.getDescription()).thenReturn(new PluginDescriptionFile("Inscribe", "2.0.0-test", "test.Plugin"));
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        ConfigService config = mock(ConfigService.class);
        LanguageService language = new LanguageService(plugin, config);
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("inscribe.command")).thenReturn(true);
        try (CommandService service = new CommandService(plugin, config, language, (recipient, upload) -> { }, mock(TransferService.class))) {
            service.onCommand(sender, null, "inscribe", new String[]{"version"});
            service.onCommand(sender, null, "inscribe", new String[]{"debug", "version"});
            ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
            verify(sender, times(2)).sendRichMessage(messages.capture());
            for (String message : messages.getAllValues()) {
                assertEquals("Inscribe v2.0.0-test", ComponentText.markup(message).plain());
            }
        }
    }

    @Test
    void diagnosticPresentationResolvesEverySharedWorkflowMessage() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(new File("build/debug-test"));
        LanguageService language = new LanguageService(plugin, mock(ConfigService.class));
        for (MessageKey definition : BukkitDebugMessages.keys()) {
            TextKey key = (TextKey) definition;
            MessageArgs.Builder arguments = MessageArgs.builder();
            for (String name : key.placeholders()) {
                arguments.untrusted(name, "test-value");
            }
            assertFalse(language.render(null, key, arguments.build()).plain().isBlank(), key.id());
        }
    }

    @Test
    void rootAndDebugHelpRenderLocalizedDescriptionsAndUploadHover() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(new File("build/help-test"));
        LanguageService language = new LanguageService(plugin, mock(ConfigService.class));
        DirectorRuntimeEngine engine = DirectorEngineFactory.create(new CommandService.Commands(null));
        for (List<String> path : List.of(List.<String>of(), List.of("debug"))) {
            DirectorMiniMenu.DirectorHelpPage page = DirectorMiniMenu.resolveHelp(engine, path).orElseThrow();
            String rendered = String.join("\n", DirectorMiniMenu.render(page, InscribePresentation.theme(), language.directorResolver()));
            assertFalse(rendered.isBlank());
            if (!path.isEmpty()) {
                assertTrue(rendered.contains("Upload only when explicitly enabled."));
            }
        }
    }

    @Test
    void commandMetadataUsesExactCatalogDefinitions() {
        for (Class<?> group : List.of(CommandService.Commands.class, CommandService.DebugCommands.class)) {
            Director root = group.getAnnotation(Director.class);
            assertEquals(((TextKey) InscribeMessages.catalog().require(root.descriptionKey())).english(), root.description());
            for (Method method : group.getDeclaredMethods()) {
                Director command = method.getAnnotation(Director.class);
                if (command == null) {
                    continue;
                }
                assertEquals(((TextKey) InscribeMessages.catalog().require(command.descriptionKey())).english(), command.description());
                for (Parameter parameter : method.getParameters()) {
                    Param metadata = parameter.getAnnotation(Param.class);
                    if (metadata != null && !metadata.descriptionKey().isBlank()) {
                        assertEquals(((TextKey) InscribeMessages.catalog().require(metadata.descriptionKey())).english(), metadata.description());
                    }
                }
            }
        }
    }

}
