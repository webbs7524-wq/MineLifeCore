# MineLife Core

MineLife Core is a Paper Minecraft server plugin made for MineLife. It adds core server commands, a shulker-only kit shop, Vault economy helpers, ping/status tools, and safe server control commands.

## Requirements

Required plugins/software: Paper server tested on Minecraft/Paper 1.21.11, Java 21, Vault, and a Vault-compatible economy provider. EconomyShopGUI is recommended if you want MineLife Core to use the same money system as the shop GUI. PlugManX is optional and only needed if you want to hot-reload plugins without restarting.

## Features

MineLife Core includes a player kit shop GUI with `/kitshop`, an op-only kit shop editor with `/kitshop edit`, an op-only add command with `/kitshop add <price> <stock>`, shulker-only kit adding, preserved shulker box item data/NBT/names/lore/contents, kit stock limits, automatic removal when stock reaches 0, Vault economy purchases, `/money` balance checks for all players, op money management commands, `/ping`, `/mlcore status`, a safe TCP_NODELAY ping optimizer, and an op-only `/server stop` command.

## Commands

Player commands: `/kitshop` opens the kit shop buying menu. `/kitshop buy` also opens the buying menu. `/money` shows your money balance. `/ping` shows your ping and server TPS. `/mlcore status` shows TPS, online players, average ping, highest ping, and ping optimizer status.

Op/admin commands: `/kitshop edit` opens the kit shop editor. `/kitshop add <price> <stock>` adds the shulker box in your hand to the kit shop. `/kitshop reload` reloads kit shop data/config. `/money add <amount>` adds money to your own account. `/money remove <amount>` removes money from your own account. `/money <player> add <amount>` adds money to another player. `/money <player> remove <amount>` removes money from another player. `/money <player>` checks another player's balance. `/ping <player>` checks another player's ping. `/mlcore reload` reloads MineLife Core config/data. `/server stop` stops the Minecraft server.

## Permissions

`kitshop.use` defaults to everyone and allows players to use `/kitshop`. `kitshop.admin` defaults to op and allows managing kit shop items. `kitshop.money` defaults to op and allows managing player money. `minelifecore.admin` defaults to op and allows MineLife Core admin commands. `minelifecore.ping.other` defaults to op and allows checking another player's ping. `minelifecore.server.stop` defaults to op and allows `/server stop`. `minelifecore.*` defaults to op and gives all MineLife Core permissions.

## Kit Shop

To add a kit, put a shulker box in your hand, make sure the shulker contains the items you want to sell, then run `/kitshop add <price> <stock>`. Example: `/kitshop add 5000 10`. That adds the shulker kit for $5,000 with 10 purchases available. When all 10 are bought, the kit goes out of stock and disappears from the buying GUI. Only shulker boxes can be added, and the plugin preserves the shulker contents and item data.

## Economy

MineLife Core uses Vault for all money features. The kit shop and `/money` commands use the same Vault economy balance as the server shop GUI/economy plugin. If Vault or an economy provider is missing, buying kits and money management will not work.

## Ping Optimizer

MineLife Core includes a safe ping optimizer. It tries to apply TCP_NODELAY to player connections on join and every 60 seconds. This may help reduce delayed packet waits, but it cannot fix bad Wi-Fi, distance from the server, or internet routing. In `plugins/MineLifeCore/config.yml`, you can configure it with `settings.ping-optimizer.enabled` and `settings.ping-optimizer.reapply-seconds`.

## Files

Config file: `plugins/MineLifeCore/config.yml`. Kit shop data file: `plugins/MineLifeCore/kits.yml`.

## Warning

`/server stop` will shut down the Minecraft server. Only give `minelifecore.server.stop` to trusted staff.
