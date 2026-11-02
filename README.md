# Inscribe

Transfer selected enchantments from equipment into a book, then apply enchanted books through inventory clicks.

## Requirements

- Minecraft 26.1 through 26.3 on Spigot, Paper, or Folia.
- Java 25 or newer.
- Network access for dependency downloads on the first start.
- Vault and an economy provider, only for money prices.

## Install

1. Stop the server and back up its worlds and plugin data.
2. Place `Inscribe.jar` in `plugins/`, replacing the previous jar.
3. Start the server, check `/ins status`, and open `/ins config` to review the settings.

See the [wiki](https://docs.volmitsoftware.com/inscribe), [configuration reference](https://docs.volmitsoftware.com/inscribe/03-configuration), and [commands and permissions](https://docs.volmitsoftware.com/inscribe/04-commands-permissions).

## Build

Use Java 25. From the Inscribe repository:

```powershell
.\gradlew.bat build buildSwiftSwamp
```

The build resolves local VolmLib automatically and stages the verified distribution as `../BUILDS/Inscribe.jar`. Override the staging directory with `-PbuildsDirectory=<path>`.
