package com.volmit.inscribe.command;

import art.arcane.volmlib.util.config.BukkitConfigEditor;
import com.volmit.inscribe.config.InscribeConfig;
import com.volmit.inscribe.localization.InscribeMessages;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConfigEditorLayoutTest {
    @Test
    void everySectionAndSettingHasCatalogBackedLabelsAndDistinctOrdering() throws ReflectiveOperationException {
        BukkitConfigEditor.EditorLayout layout = ConfigEditorLayout.create(player -> { });
        Set<Integer> orders = new HashSet<>();
        InscribeConfig defaults = new InscribeConfig();
        for (Field field : InscribeConfig.class.getFields()) {
            assertPresented(layout, List.of(field.getName()));
            assertTrue(orders.add(layout.entries().apply(List.of(field.getName())).order()), field.getName());
            if (field.getType().getName().startsWith(InscribeConfig.class.getName() + "$")) {
                Set<Integer> nested = new HashSet<>();
                for (Field setting : field.get(defaults).getClass().getFields()) {
                    List<String> path = List.of(field.getName(), setting.getName());
                    assertPresented(layout, path);
                    assertTrue(nested.add(layout.entries().apply(path).order()), String.join(".", path));
                }
            }
        }
        assertEquals(8, orders.size());
    }

    private static void assertPresented(BukkitConfigEditor.EditorLayout layout, List<String> path) {
        BukkitConfigEditor.EntryPresentation presentation = layout.entries().apply(path);
        assertNotNull(presentation, String.join(".", path));
        assertEquals(presentation.name(), InscribeMessages.catalog().require(presentation.name().id()));
        assertEquals(presentation.description(), InscribeMessages.catalog().require(presentation.description().id()));
    }

    @Test
    void languageNavigationAndPolicyFieldsUseSharedLayoutActions() {
        AtomicInteger opened = new AtomicInteger();
        BukkitConfigEditor.EditorLayout layout = ConfigEditorLayout.create(player -> opened.incrementAndGet());
        layout.shortcuts().getFirst().action().accept(null);
        layout.entries().apply(List.of("general", "language")).action().accept(null);
        assertEquals(2, opened.get());
        for (String field : List.of("enabled", "cost", "perLevel", "currency")) {
            BukkitConfigEditor.EntryPresentation entry = layout.entries().apply(List.of("enchantments", "minecraft:sharpness", field));
            assertNotNull(entry);
            assertEquals("setting.policy." + field + ".name", entry.name().id());
        }
    }

    @Test
    void dynamicSectionsOptInOnlyEnchantmentPolicies() {
        assertEquals(Set.of(List.of("enchantments")), ConfigEditorLayout.dynamicSections());
    }

}
