# Advanced Equivalency

An independent alchemy mod for **Hytale 0.7.7**, inspired by [Equivalent Exchange 2](https://www.minecraftforum.net/forums/mapping-and-modding-java-edition/minecraft-mods/1282288-1-2-5-equivalent-exchange-2-v1-4-6-7).

- The **Transmutation Table** converts items into EMC and spends it to recreate learned items.
- The **Alchemical Chest** holds up to 104 stacks.
- The **Energy Condenser** uses the EMC from deposited items to copy a chosen item. It comes in two tiers.
- The **Philosopher's Stone** is used to craft the Transmutation Table.

Find the recipes in the **Advanced Equivalency** tab of the Alchemist's Workbench.

Use `/advancedequivalency` or `/aee` to edit item EMC values. The world owner and players with the `advancedequivalency.config` permission can save changes. Grant `advancedequivalency.readonly` for read-only access.
The world owner and players with both editor access and `advancedequivalency.permissions` can grant Modify or Read-only access from **Player Permissions**.

## Build and install

With JDK 25 on your PATH and the `HytaleServer.jar` from Hytale 0.7.7, run this command in PowerShell.

```powershell
.\build.ps1 -ServerJar 'path\to\HytaleServer.jar'
```

Copy `dist/AdvancedEquivalency-0.1.0.jar` to `UserData/Mods/` and enable the mod in your world's settings. For a dedicated server, put the JAR in `mods/` and restart the server.

When upgrading from a previous build, remove the old JAR before launching. The mod imports existing EMC and permission command settings and migrates permission keys in Hytale's built-in provider. If you use a third-party permission provider, update its keys to the names above.
