package com.volmit.inscribe;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Objects;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.nio.file.Files;
import java.util.logging.Logger;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

import static org.assertj.core.api.Assertions.assertThat;

final class RuntimeJarTest {
    private static final String PLUGIN_PACKAGE = "com.volmit.inscribe.";
    private static final String LIBRARY_PACKAGE = PLUGIN_PACKAGE + "libs.";
    private static RuntimeLoader loader;

    @TempDir
    Path directory;

    @BeforeAll
    static void openRuntimeLoader() throws Exception {
        Path artifact = Path.of(Objects.requireNonNull(System.getProperty("inscribe.runtimeJar")));
        Path libraries = Path.of(Objects.requireNonNull(System.getProperty("inscribe.runtimeTestLibraries")));
        loader = new RuntimeLoader(new URL[]{artifact.toUri().toURL(), libraries.toUri().toURL()}, RuntimeJarTest.class.getClassLoader());
    }

    @AfterAll
    static void closeRuntimeLoader() throws Exception {
        if (loader != null) {
            loader.close();
        }
    }

    @Test
    void packagedLanguageCreatesEditableEnglishWithoutSourceCatalogs() throws Exception {
        Class<?> configType = loader.loadClass(PLUGIN_PACKAGE + "config.ConfigService");
        Class<?> languageType = loader.loadClass(PLUGIN_PACKAGE + "localization.LanguageService");
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("Inscribe-packaged-language"));
        when(plugin.getResource(anyString())).thenAnswer(invocation -> loader.getResourceAsStream(invocation.getArgument(0)));
        try (AutoCloseable config = (AutoCloseable) configType.getConstructor(File.class).newInstance(directory.toFile())) {
            configType.getMethod("reload").invoke(config);
            try (AutoCloseable language = (AutoCloseable) languageType.getConstructor(JavaPlugin.class, configType).newInstance(plugin, config)) {
                languageType.getMethod("start").invoke(language);
                assertThat(Files.readString(directory.resolve("languages/en_US.toml"))).contains("transfer");
                assertThat(languageType.getMethod("editorOptions").invoke(language)).isNotNull();
            }
        }
    }

    @Test
    void packagedEntrypointLoadsFromDistribution() throws Exception {
        Class<?> plugin = loader.loadClass(PLUGIN_PACKAGE + "Inscribe");
        assertThat(plugin.getClassLoader()).isSameAs(loader);
        assertThat(JavaPlugin.class.isAssignableFrom(plugin)).isTrue();
        assertThat(plugin.getConstructor()).isNotNull();
        assertThat(plugin.getMethod("onEnable")).isNotNull();
        assertThat(plugin.getMethod("onDisable")).isNotNull();
    }

    @Test
    void packagedAdventureLoadsTheRelocatedSerializers() throws Exception {
        Class<?> type = loader.loadClass(LIBRARY_PACKAGE + "volmlib.util.plugin.ComponentText");
        Object text = type.getMethod("markup", String.class).invoke(null, "<gold>Inscribe</gold>");
        assertThat(type.getMethod("plain").invoke(text)).isEqualTo("Inscribe");
        assertThat((String) type.getMethod("legacy").invoke(text)).endsWith("Inscribe");
        assertThat((String) type.getMethod("miniMessage").invoke(text)).contains("Inscribe");
    }

    @Test
    void packagedConfigurationRetainsReflectiveFieldsAndReloadsEdits() throws Exception {
        Class<?> type = loader.loadClass(PLUGIN_PACKAGE + "config.ConfigService");
        try (AutoCloseable config = (AutoCloseable) type.getConstructor(File.class).newInstance(directory.toFile())) {
            type.getMethod("reload").invoke(config);
            Object initial = type.getMethod("runtime").invoke(config);
            assertThat(initial.getClass().getMethod("extractionCost").invoke(initial)).isEqualTo(100);
            Consumer<Object> edit = candidate -> {
                try {
                    Object pricing = candidate.getClass().getField("pricing").get(candidate);
                    pricing.getClass().getField("extractionCost").setInt(pricing, 250);
                } catch (ReflectiveOperationException exception) {
                    throw new AssertionError("Packaged configuration field is unavailable", exception);
                }
            };
            type.getMethod("update", Consumer.class).invoke(config, edit);
            assertThat(type.getMethod("document").invoke(config)).isNotNull();
        }
        try (AutoCloseable config = (AutoCloseable) type.getConstructor(File.class).newInstance(directory.toFile())) {
            type.getMethod("reload").invoke(config);
            Object persisted = type.getMethod("runtime").invoke(config);
            assertThat(persisted.getClass().getMethod("extractionCost").invoke(persisted)).isEqualTo(250);
        }
    }

    @Test
    void packagedDirectorRetainsCommandsAndParameterMetadata() throws Exception {
        Class<?> service = loader.loadClass(PLUGIN_PACKAGE + "command.CommandService");
        Class<?> commands = loader.loadClass(PLUGIN_PACKAGE + "command.CommandService$Commands");
        Object instance = commands.getConstructor(service).newInstance(new Object[]{null});
        Class<?> factory = loader.loadClass(LIBRARY_PACKAGE + "volmlib.util.director.compat.DirectorEngineFactory");
        Object engine = factory.getMethod("create", Object.class).invoke(null, instance);
        Object root = engine.getClass().getMethod("getRoot").invoke(engine);
        List<?> children = (List<?>) root.getClass().getMethod("getChildren").invoke(root);
        List<String> names = new ArrayList<>();
        List<String> debugNames = new ArrayList<>();
        for (Object child : children) {
            Object descriptor = child.getClass().getMethod("getDescriptor").invoke(child);
            String name = (String) descriptor.getClass().getMethod("getName").invoke(descriptor);
            names.add(name);
            if (name.equals("debug")) {
                List<?> debugChildren = (List<?>) child.getClass().getMethod("getChildren").invoke(child);
                for (Object debugChild : debugChildren) {
                    Object debugDescriptor = debugChild.getClass().getMethod("getDescriptor").invoke(debugChild);
                    debugNames.add((String) debugDescriptor.getClass().getMethod("getName").invoke(debugDescriptor));
                }
            }
        }
        assertThat(names).containsExactlyInAnyOrder("config", "language", "status", "debug", "version");
        assertThat(debugNames).containsExactlyInAnyOrder("dump", "version");
        assertThat(commands.getMethod("status", CommandSender.class).getParameters()[0].isNamePresent()).isTrue();
    }

    private static final class RuntimeLoader extends URLClassLoader {
        private RuntimeLoader(URL[] artifacts, ClassLoader parent) {
            super(artifacts, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(PLUGIN_PACKAGE)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = findClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
