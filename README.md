# Advanced Equivalent Exchange

An alchemy mod for **Hytale 0.7.7**, inspired by [ProjectE](https://github.com/sinkillerj/ProjectE).

- The **Transmutation Table** converts items into EMC and spends it to recreate learned items.
- The **Alchemical Chest** holds up to 104 stacks.
- The **Energy Condenser** uses the EMC from deposited items to copy a chosen item. It comes in two tiers.
- The **Philosopher's Stone** is used to craft the Transmutation Table.

Find the recipes in the **Equivalent Exchange** tab of the Alchemist's Workbench.

## Build and install

With JDK 25 on your PATH and the `HytaleServer.jar` from Hytale 0.7.7, run this command in PowerShell.

```powershell
.\build.ps1 -ServerJar 'path\to\HytaleServer.jar'
```

Copy `dist/AdvancedEquivalentExchange-0.1.0.jar` to `UserData/Mods/` and enable the mod in your world's settings. For a dedicated server, put the JAR in `mods/` and restart the server.
