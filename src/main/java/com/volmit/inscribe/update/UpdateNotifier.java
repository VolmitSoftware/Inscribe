package com.volmit.inscribe.update;

import art.arcane.volmlib.util.update.BukkitUpdateService;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.function.BooleanSupplier;

public final class UpdateNotifier implements AutoCloseable {
    private final String repository;
    private final boolean configured;
    private final BukkitUpdateService service;

    public UpdateNotifier(JavaPlugin plugin, BooleanSupplier enabled) throws IOException {
        Properties source = new Properties();
        try (InputStream input = plugin.getResource("inscribe-release.properties")) {
            if (input == null) {
                throw new IOException("Missing Inscribe release metadata");
            }
            source.load(input);
        }
        repository = source.getProperty("repository", "UNCONFIGURED");
        configured = Boolean.parseBoolean(source.getProperty("configured", "false"));
        String[] coordinates = configured ? repository.split("/", -1) : new String[]{"UNCONFIGURED", "UNCONFIGURED"};
        if (coordinates.length != 2 || coordinates[0].isBlank() || coordinates[1].isBlank()) {
            throw new IOException("Invalid Inscribe release repository: " + repository);
        }
        service = BukkitUpdateService.register(plugin, new BukkitUpdateService.Options(
                coordinates[0], coordinates[1], "inscribe.update", () -> configured && enabled.getAsBoolean()));
    }

    public void reconfigure() {
        service.reconfigure();
    }

    public String repository() {
        return repository;
    }

    public boolean configured() {
        return configured;
    }

    @Override
    public void close() {
        service.close();
    }
}
