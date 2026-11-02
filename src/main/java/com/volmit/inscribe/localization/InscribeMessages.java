package com.volmit.inscribe.localization;

import art.arcane.volmlib.util.config.BukkitConfigMessages;
import art.arcane.volmlib.util.director.DirectorMessages;
import art.arcane.volmlib.util.localization.BukkitLanguageMessages;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.diagnostics.BukkitDebugMessages;
import com.volmit.inscribe.presentation.InscribePresentation;
import com.volmit.inscribe.command.ConfigEditorLayout;

public final class InscribeMessages {
    private InscribeMessages() {
    }

    public static MessageCatalog catalog() {
        MessageCatalog.Builder builder = MessageCatalog.builder("en_US");
        ConfigEditorLayout.addMessages(builder);
        for (MessageKey key : BukkitDebugMessages.keys()) {
            builder.add(key);
        }
        for (MessageKey key : DirectorMessages.keys()) {
            builder.add(key);
        }
        for (MessageKey key : BukkitLanguageMessages.keys()) {
            builder.add(key);
        }
        for (MessageKey key : BukkitConfigMessages.keys()) {
            builder.add(key);
        }
        builder.add(TextKey.of("transfer.disabled", "Enchantment transfers are disabled."));
        builder.add(TextKey.of("transfer.creative", "Switch to survival mode to transfer enchantments."));
        builder.add(TextKey.of("transfer.denied", "You do not have permission to do that."));
        builder.add(TextKey.of("transfer.empty", "This item has no transferable enchantments."));
        builder.add(TextKey.of("transfer.space", "Make room in your inventory for the book."));
        builder.add(TextKey.of("transfer.changed", "Your items or settings changed. Start the transfer again."));
        builder.add(TextKey.of("transfer.unaffordable", "You cannot pay this transfer's cost."));
        builder.add(TextKey.of("transfer.economy", "Money payments are unavailable."));
        builder.add(TextKey.of("transfer.failed", "The transfer failed. Contact an administrator."));
        builder.add(TextKey.of("transfer.success", "Enchantments transferred."));
        builder.add(TextKey.of("transfer.applied", "Enchantments applied."));
        builder.add(TextKey.of("transfer.incompatible", "This book has no compatible upgrades for that item."));
        builder.add(TextKey.of("menu.title", "Inscribe"));
        builder.add(TextKey.of("menu.keep", "Keep: {name} {level}"));
        builder.add(TextKey.of("menu.transfer", "Transfer: {name} {level}"));
        builder.add(TextKey.of("menu.cost", "Cost: {cost}"));
        builder.add(TextKey.of("menu.price", "{experience} XP + {money} money"));
        builder.add(TextKey.of("menu.experience", "{experience} XP"));
        builder.add(TextKey.of("menu.money", "{money} money"));
        builder.add(TextKey.of("menu.free", "Free"));
        builder.add(TextKey.of("menu.confirm", "Confirm transfer"));
        builder.add(TextKey.of("menu.apply", "Apply: {name} {level}"));
        builder.add(TextKey.of("menu.skip", "Skip: {name} {level}"));
        builder.add(TextKey.of("menu.current", "Current level: {level}"));
        builder.add(TextKey.of("menu.confirm_apply", "Apply enchantments"));
        builder.add(TextKey.of("menu.cancel", "Cancel"));
        builder.add(TextKey.of("menu.previous", "Previous page"));
        builder.add(TextKey.of("menu.next", "Next page"));
        builder.add(TextKey.of("menu.total", "Total: {cost}"));
        builder.add(TextKey.of("transfer.select", "Select at least one enchantment."));
        builder.add(TextKey.of("transfer.reconciliation", "Payment failed unexpectedly. Ask an administrator to check the economy before retrying."));
        builder.add(TextKey.of("command.player", "Open this command in game."));
        builder.add(TextKey.of("command.failed", "The command failed. See the server console."));
        builder.add(TextKey.of("command.version", "<gradient:"
                + InscribePresentation.theme().primaryLeft() + ":" + InscribePresentation.theme().primaryRight()
                + ">Inscribe v{version}</gradient>"));
        builder.add(TextKey.of("command.feedback.files_applied", "&aApplied&7 configuration and language file changes."));
        builder.add(TextKey.of("command.feedback.files_rejected", "&cRejected file changes; the last known good settings remain active."));
        builder.add(TextKey.of("status.header", "Inscribe status"));
        builder.add(TextKey.of("status.configuration", "&7Configuration {enabled} &8|&r &7language &f{language}&r &8|&r &7hot reload {hot_reload}"));
        builder.add(TextKey.of("status.pricing", "&7Currency &f{currency}&r &8|&r &7extraction fee &f{extraction}&r &8|&r &7application fee &f{application}&r"));
        builder.add(TextKey.of("status.transfers", "&7Open menus &f{menus}&r &8|&r &7merge levels {merge} &8|&r &7repair cost {repair}"));
        builder.add(TextKey.of("status.services", "&7Metrics {metrics} &8|&r &7update notices {updates}"));
        builder.add(TextKey.of("status.compatibility", "&7Compatibility &fJava 25 / Bukkit {range}&r &8|&r &f{scheduler}&r"));
        builder.add(TextKey.of("status.state.enabled", "&aenabled&r"));
        builder.add(TextKey.of("status.state.disabled", "&cdisabled&r"));
        builder.add(TextKey.of("command.description.root", "Inscribe help and administration"));
        builder.add(TextKey.of("command.description.config", "Edit Inscribe settings"));
        builder.add(TextKey.of("command.description.language", "Select or edit languages"));
        builder.add(TextKey.of("command.description.status", "Show settings, open menus, and compatibility"));
        builder.add(TextKey.of("command.description.debug", "Diagnostic tools"));
        builder.add(TextKey.of("command.description.version", "Show the installed version"));
        builder.add(TextKey.of("command.description.debug_dump", "Save a diagnostic report"));
        builder.add(TextKey.of("command.parameter.upload", "Upload only when explicitly enabled."));
        return builder.build();
    }
}
