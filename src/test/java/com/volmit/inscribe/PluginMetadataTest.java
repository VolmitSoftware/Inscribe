package com.volmit.inscribe;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

final class PluginMetadataTest {
    private static final YamlConfiguration PLUGIN = load();

    @Test
    void rootCommandUsesTheShortAliasWithoutACommandGate() {
        ConfigurationSection command = PLUGIN.getConfigurationSection("commands/inscribe");
        assertThat(command).isNotNull();
        assertThat(command.getStringList("aliases")).containsExactly("ins");
        assertThat(command.contains("permission")).isFalse();
    }

    @Test
    void playerPermissionsDefaultOnAndAdministrationDefaultsToOperators() {
        Map<String, String> defaults = Map.of(
                "inscribe.command", "true",
                "inscribe.extract", "true",
                "inscribe.apply", "true",
                "inscribe.language.self", "true",
                "inscribe.config", "op",
                "inscribe.debug", "op",
                "inscribe.update", "op",
                "inscribe.*", "op");
        ConfigurationSection permissions = PLUGIN.getConfigurationSection("permissions");
        assertThat(permissions).isNotNull();
        assertThat(permissions.getKeys(false)).containsExactlyInAnyOrderElementsOf(defaults.keySet());
        for (Map.Entry<String, String> entry : defaults.entrySet()) {
            ConfigurationSection permission = permissions.getConfigurationSection(entry.getKey());
            assertThat(permission).as(entry.getKey()).isNotNull();
            assertThat(permission.getString("default")).as(entry.getKey()).isEqualTo(entry.getValue());
            assertThat(permission.getString("description")).as(entry.getKey()).isNotBlank();
        }
        List<String> children = List.copyOf(permissions.getConfigurationSection("inscribe.*/children").getKeys(false));
        assertThat(children).containsExactlyInAnyOrderElementsOf(defaults.keySet().stream()
                .filter(name -> !name.equals("inscribe.*")).toList());
    }

    private static YamlConfiguration load() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().pathSeparator('/');
        try {
            yaml.load(new File("src/main/resources/plugin.yml"));
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException(exception);
        }
        return yaml;
    }
}
