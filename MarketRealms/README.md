# MarketRealms 1.0.0

A standalone **Paper 1.21.11 / Java 21** survival marketplace plugin. Main package: `com.marketrealms`.

This repository contains the complete source, resources, Maven build, plugin descriptor, automated tests and a GitHub Actions build workflow. No missing classes, external scripts or hand-written database tables are needed to compile it. Treat this first release as a **staging-server candidate**, not a claim that every multiplayer/exploit scenario has been certified. See the testing checklist and safety limitations below before opening a public economy.

## Build and install

Requirements: a Java **21 JDK** and Maven **3.9+**.

```sh
java -version
mvn -version
mvn --batch-mode --no-transfer-progress clean verify
```

Output: `target/MarketRealms-1.0.0.jar`. Use this shaded JAR, **not** `original-MarketRealms-1.0.0.jar`. SQLite JDBC is bundled.

If this project is inside the existing CombatGuard repository, first run `cd MarketRealms`. The downloadable source ZIP has `pom.xml`, `src/`, `README.md` and `.github/` at its root and can be extracted directly into a separate GitHub repository.

1. Stop the Paper server and back up your server worlds.
2. Copy the shaded JAR into `plugins/`.
3. For Vault money, also install **Vault 1.7.3** and a **Paper 1.21.11-compatible EssentialsX** release, or another compatible Vault economy provider. VaultAPI 1.7 is the provided compile-time API, not an extra API JAR to install. Verify your provider's `/balance` command and service registration. EssentialsX economy is part of the core EssentialsX plugin; you do not need EssentialsX Chat for this plugin.
4. Start Paper using Java 21. MarketRealms generates its configuration and SQLite database.
5. Review the worlds/plot definitions before allowing rentals. New default worlds have a stone floor at y=64 and build volumes beginning at y=65.
6. Use `/marketadmin status`, `/marketadmin reload` and `/market help`.

**Optional dependency behavior:** without Vault, without a registered economy provider, or with `economy.money-enabled: false`, money payments are unavailable with a clear message. Emerald transactions remain supported. Missing optional dependencies do not crash server startup. WorldGuard, WorldEdit, ProtocolLib and PacketEvents are **not required**. There is no Folia support.

## Two worlds and private 3D previews

Default worlds:

- `marketrealms_market`: real rented plots, survival builds, listings, public roads and shop bells.
- `marketrealms_preview`: one shared physical world, with **per-player client views**.

The marketplace's entire non-owned area is protected. Roads, public structures, unowned plots and the floor outside plot volumes are not editable by ordinary players. PvP is disabled. The initial flat world does not automatically construct decorative roads or full street buildings; administrators can design them using the opt-in admin build bypass or console/world-edit tools.

Preview isolation uses Paper's supported, packet-backed APIs:

- `hidePlayer/showPlayer` hides other players from each preview visitor.
- Private `sendBlockChange` previews never alter a marketplace block.
- Text displays use `setVisibleByDefault(false)` and `showEntity` for their owning viewer only; temporary displays are not persistent.
- Targeted particles outline the pad under the player's crosshair.
- A server-side bounding-box ray test validates selection; right-click opens plot/design details.

Approximately three available plot pads are shown at once. `/market preview next` cycles them. Category filters are available through `/market` and `/market preview <category>`. Design preview shows the selected procedural shop shell in the same private physical space. Fake blocks are **visual**, not authoritative collision geometry: the shared stone floor is the authoritative walkable surface. No creative mode or items are granted. Oversized plot footprints are clipped to `preview.maximum-size` for rendering; labels show real dimensions. Designs larger than that cap are refused in preview with an explanation.

`/market exit`, world changes, disconnects, reload and shutdown clean up temporary displays, client blocks and plugin-owned visibility changes. Reconnecting in the preview world sends the player to the configured exit world. Existing vanish plugins retain their own hide ownership. Two-client visual testing is still required for your exact client/plugin stack.

## Renting and building

Rent **land**, then either build in normal survival or buy a premade design shell. Only one active/recovering rental is permitted per UUID. Weekly/monthly/quarterly defaults are 7/30/90 days. Time is absolute UTC epoch time, so it continues offline. Renewal adds duration to the existing expiration, not to the current time. Expired ownership fails protection checks immediately, even before the periodic archive job runs. Login messages and configurable warning thresholds show remaining time.

Plans and designs independently allow emeralds, money or both. Use `'-'` to disable an individual payment method. Money has at most two decimal places and is stored as integer cents; emerald prices are positive whole items. There is no exchange-rate conversion.

Premade designs are configurable **procedural shells**, not an arbitrary schematic importer. Palette, dimensions, description, category restrictions and prices are configurable. They contain no prefilled stock. Purchase requires a completely empty fitting plot. Known placement failures clear only the newly placed shell and compensate payment. Ambiguous external failures lock the plot/account for review.

Catalogs can be edited or removed without rebuilding. Empty maps must remain valid YAML (`plans: {}`, `designs: {}`). Removing a plan does not remove an existing rental or its saved expiration. Occupied plot boundary/district/category-list changes are rejected; do not silently relocate another player's shop. Plot IDs with historical rental/archive references must be disabled instead of deleted. Disabling a category prevents new selection; already assigned rentals are grandfathered and are never automatically moved or erased.

## Both selling methods

Hold one example of the sale item. Its full item data defines the listing's matching template.

### Physical item displays

Stand inside your own plot:

```text
/market shop create display 8 3 12.50
/market shop restock <listing-id> 64
```

This sells eight matching items per purchase for either three emeralds or 12.50 Vault money. Prices are independent. Restocking removes real matching items from your inventory into persistent, listing-linked stock. Display objects are invulnerable visual items plus interaction entities; customers cannot take the displayed object directly. UUIDs and persistent data link entities to SQLite listings. `/marketadmin` inspection and repair tooling detects missing/orphaned display entities.

### Protected container shops

Look at a **single chest or barrel** inside your rented plot, while holding a sample sale item:

```text
/market shop create container 16 5 -
```

This sells 16 matching items for five emeralds only. Multiple different items can be listed in one eligible container. The owner opens/restocks the container normally; customers open listing/purchase menus, not its inventory. Combined/double chests are rejected. Remove every listing attached to a registered container before breaking it.

Both methods validate current rental ownership, listing state, stock, quantity, exact selected price, proximity, line of sight and inventory capacity. A changed quote requires a fresh confirmation. Offers are never accepted just because a client supplied an entity ID or GUI item.

### Offline proceeds

Both emerald and Vault-money seller proceeds are recorded in **separate persistent claimable balances**, including offline sales. Money is not automatically deposited into an offline account. `/market sales` shows sales and lets the owner claim:

- Emerald proceeds become physical emeralds, with capacity checks and a journaled claim.
- Money proceeds are deposited through Vault on claim. The persistent escrow balance is distinct from the player's existing Vault account balance.
- Removed display stock is placed into private persistent stock deliveries, rather than dropped or silently deleted.

The SQLite sale ID is the operation ID, preventing duplicate recorded sales. Vault money received through direct trades uses the same money-claim mechanism. No emerald-to-money or money-to-emerald conversion occurs.

## Protection policy

Survival placement/breaking and interactive blocks require current plot ownership and `marketrealms.build`. Ownership is rechecked for container clicks/drags, including an inventory opened before expiration. Public roads and neighboring plots are protected. Administrative build bypass is off by default and never bypasses active recovery/account locks.

The policy is intentionally conservative: marketplace/preview explosions, fire, fluid spread, pistons, dispensers, hoppers, entity block changes, hanging/item-frame placement, destructive entities and structure growth are blocked. Hoppers and adjacent chests are not permitted through ordinary building. Marketplace entity damage is cancelled. Container transport is disabled throughout these worlds, not merely on shop chests. Protected-world mobs are disabled by default. Furnace/brewing completion is blocked while ownership is expired or the plot is recovering.

Other plugins may impose stricter rules. MarketRealms does not register WorldGuard flags and does not replace another plugin's protection database. Privileged console/WorldEdit/administrative operations can bypass event-based protection; coordinate these tools carefully and never operate on a plot being captured/restored.

## Archives and crash recovery

Expiration progresses through persistent rental/plot/archive states:

```text
ACTIVE -> ARCHIVING / CAPTURING -> RESETTING -> EXPIRED / AVAILABLE
archive AVAILABLE -> RESTORING -> USED
```

- World/block reads happen on the server thread in bounded batches.
- ZIP writing, hashing, verification and archive reading run on a dedicated I/O worker.
- Each `.mrzip` is a bounded ZIP containing only `manifest.yml`, with format version 1 and SHA-256 metadata. No extracted paths or Java object streams are accepted.
- Reset starts only after the archive has been written, flushed and verified.
- Containers are cleared without dropping items, then blocks are reset without physics drops.
- World save occurs before the plot becomes available.
- Capture is recaptured safely if interrupted before reset. Verified reset operations resume idempotently. In-progress restoration resumes under a destination lock; no inventory/build is accessible before completion.
- A successful restore consumes the archive. Repeated retries of an AVAILABLE/USED archive are refused.
- Too-small or invalid destinations fail validation without consuming the archive. Partial runtime failures keep the destination locked, preserve the archive and can be retried.
- Listings are remapped relative to the archived origin with deterministic IDs and restored paused. Bells and supported stock/container contents are restored with the build. Seller proceeds already earned remain in separate wallets and are not copied into an archive.

Supported block entities: unlocked chests, barrels, shulker boxes, furnaces/smokers/blast furnaces, brewing stands, dispensers/droppers, signs/hanging signs and bells. Block data, supported inventory item components, persistent-data bytes, container names, furnace/brewing timers and both sign sides are preserved. **Furnace stored recipe XP is not preserved.** Entities, animals, armor stands and item frames are not part of building archives. Their ordinary placement is blocked in marketplace plots.

Unsupported tile states (for example spawners, beacons, decorated pots, command/structure blocks, crafter special state, campfires, skull profiles and plugin-specific blocks), loot-table containers and custom locked containers stop capture and leave the plot locked. External plugin databases, custom entity state and mod-specific inventories cannot be preserved by this format. Do not claim those builds are restorable; handle them manually before resetting. No reset occurs when capture cannot preserve an encountered block entity.

Unused archives are never automatically deleted. Default `keep-used-archives: true` retains consumed archives too. When that is false and `used-archive-retention-days` is positive, hourly retention retires only old consumed archives after making/verifying a copy in `exports/`; metadata is retained. A value of 0 disables retirement. Administrator deletion is restricted to USED archives and first exports a verified copy.

## Financial/inventory crash boundary — important

SQLite, Minecraft player/world files and arbitrary Vault providers **do not share an atomic transaction**. Vault does not offer a universal idempotent transfer key or cross-system rollback protocol. This plugin does not pretend otherwise.

Each operation records a durable UUID, participants, details and inventory before/after images. PREPARED intent has no external effects and can be rolled back on restart. APPLYING intent may have external effects and becomes REVIEW after interruption. Known synchronous failures compensate changes/payment; an uncertain provider exception is **not blindly retried**. Participants with unresolved REVIEW operations cannot log in; referenced plots are locked. COMMITTED operations are never replayed automatically.

Manual reconciliation is necessary for an ambiguous crash/provider failure. Inspect the operation export, Minecraft saved player data, container data, rental/listing rows and the economy provider's own ledger. Make the intended exchange whole, or refund/restore the original exchange, **exactly once**. Only then acknowledge the result and unlock any reviewed plot. The acknowledge command does not itself invent a refund, recreate a rental, credit a wallet or replay inventory images. Back up first, stop the server before externally modifying player files/SQLite, and do not guess which side of a provider call completed.

This is recoverable fail-closed journaling, not guaranteed automatic distributed exactly-once settlement. Hard power loss, asynchronous changes by other plugins, privileged inventory edits, destructive third-party tools or a lying economy provider remain outside that guarantee. Do not deploy to a valuable production economy before completing the live checklist and auditing recovery procedures.

## Secure direct trades

```text
/trade <player>
/trade accept
/trade decline
/trade cancel
```

Stand near the other player in the same world. Each receives their own trade view. Click an item in your bottom inventory to offer the **entire existing stack**; click its offer icon to remove it. Items stay in their original inventory until final commit—closing/cancelling a window does not require recovering cursor escrow. Buttons adjust offered physical emeralds and optional money. Both participants see both offers. Any UI offer change resets both confirmations. Inventory slots, exact item data, balances, proximity, world/death/online conditions and destination capacity are revalidated before exchange.

Disconnect, death, world change, distance violations, closing the trade window or plugin reload cancel an open trade. Participants cannot have two sessions. Persisted open sessions cancel safely on restart; uncertain exchanges are journaled for review instead of being replayed. Requests have timeout and anti-spam controls. Currency gains through Vault are separately claimable; physical emerald and item gains enter the recipient's inventory.

## Bells

Place a bell inside your plot, look at it, then `/market bell`. `/market bell remove` unregisters it; register another bell to move the contact point. Customers must be inside the corresponding shop, near the bell and able to see it. Online owners receive customer name/plot/location; offline notifications can be queued persistently. Cooldowns are per customer and plot. Bell contact does not grant container/build permissions. Vanilla non-player/redstone bell ringing is not a remote contact mechanism.

## Player commands

All purchases/restores/removals show a confirmation menu. Aliases: `/mr` for `/market`.

| Command | Purpose / permission suffix |
|---|---|
| `/market` | Main menu (`use`) |
| `/market preview [category-id]` | Private 3D preview (`preview`) |
| `/market preview next` | Next nearby plots (`preview`) |
| `/market exit` | Leave preview (`use`) |
| `/market rent [plot-id plan-id emeralds\|money]` | Browse or confirm rental (`rent`) |
| `/market renew [plan-id emeralds\|money]` | Renew correct active rental (`renew`) |
| `/market home` | Teleport to active plot (`use`) |
| `/market info` | Expiration/remaining time (`use`) |
| `/market category [category-id]` | Choose permitted category (`category`) |
| `/market designs` | Catalog/purchase menus (`designs`) |
| `/market designs preview <design-id>` | 3D design preview (`designs`; GUI also checks `preview`) |
| `/market restore [archive-id]` | Private archive list/restore (`restore`) |
| `/market bell [remove]` | Register/move/remove bell (`bell`) |
| `/market shop [list\|help]` | Owner listings and usage (`shop`) |
| `/market shop create display\|container <quantity> <emerald-price\|-> <money-price\|->` | New listing (`shop`) |
| `/market shop edit <id> <quantity> <emerald-price\|-> <money-price\|->` | Edit independent prices (`shop`) |
| `/market shop restock <id> <count>` | Deposit display stock (`shop`) |
| `/market shop pause\|resume <id>` | Pause/resume sales (`shop`) |
| `/market shop remove <id>` | Confirm removal (`shop`) |
| `/market sales` | Recent sales, proceeds and stock deliveries (`sales`) |
| `/market sales claim emeralds [count]` | Claim physical emeralds, max 2304 (`sales`) |
| `/market sales claim money` | Claim Vault proceeds (`sales`) |
| `/market sales delivery <id>` | Claim recovered stock (`sales`) |
| `/market help` | Help (`use`) |
| `/trade <player>\|accept\|decline\|cancel` | Separate direct trading (`trade`) |

Currency aliases: `emerald`, `emeralds`, `money`, `vault`. IDs and prices are always validated server-side. No rental-cancel/refund command exists.

## Administrator commands

Alias: `/mra`. Permission suffixes below have `marketrealms.` prepended. Full `admin` grants all administrative subnodes; ordinary defaults are false (OP defaults true for admin nodes).

| Command | Permission / behavior |
|---|---|
| `/marketadmin status` | `admin`; economy/storage/rental status |
| `/marketadmin reload` | `admin`; validates before applying, refuses world changes or active archive work |
| `/marketadmin set <file-without-yml> <path> <YAML-value>` | Relevant subnode; **draft only**, then reload |
| `/marketadmin world market\|preview <name>` | `admin`; saves restart-only configuration |
| `/marketadmin spawn market\|preview` | `admin`; stand in the relevant world |
| `/marketadmin plot create <id> <width> <height> <depth> <district> <category,...>` | `admin.plots`; stand at minimum build corner in market |
| `/marketadmin plot remove <id>` | `admin.plots`; only empty/unreferenced plot identities |
| `/marketadmin inspect rentals\|listings` | `admin`; inspect first 100 persisted records |
| `/marketadmin reset <plot-id> confirm` | `admin.reset`; expires occupied plot and archives BEFORE resetting |
| `/marketadmin archive list\|info <id>` | `admin.archive`; metadata inspection (list takes no ID) |
| `/marketadmin archive export <id>` | `admin.archive`; verified copy to `exports/` |
| `/marketadmin archive retry <id>` | `admin.archive` + `admin.recovery`; incomplete operations only |
| `/marketadmin archive restore <id> <online-original-owner>` | `admin.archive`; same ownership/empty-fit checks |
| `/marketadmin archive delete <id> confirm-used` | `admin.archive`; USED only, export first |
| `/marketadmin recovery list\|info <operation-id>` | `admin.recovery`; review intents (list takes no ID) |
| `/marketadmin recovery export <operation-id>` | `admin.recovery`; before/after inventory/payment intent file |
| `/marketadmin recovery resolve <id> acknowledge-commit\|acknowledge-rollback` | `admin.recovery`; only AFTER manual reconciliation |
| `/marketadmin recovery unlockplot <plot-id> confirm-reconciled` | `admin.recovery`; reviewed plot only, after operation reconciliation |

Examples:

```text
/marketadmin set rental-plans plans.weekly.money '750.00'
/marketadmin set rental-plans plans.weekly.duration-days 10
/marketadmin set rental-plans plans.weekly.enabled false
/marketadmin set categories categories.ores.description 'Mining and ore supplies'
/marketadmin set plots plots.north_2.categories [ores, general]
/marketadmin reload
```

Create/edit/remove custom plans, categories, districts or designs by editing the corresponding YAML section or saving its fields as a draft with `set`, then reloading once the complete section is valid. Use `null` as the YAML value to remove a draft key. Draft errors are reported and do not replace the live configuration. Boundary/category-list changes to occupied plots require deliberate archive/reset. Renaming marketplace worlds while historical rentals exist is rejected by stored identity metadata; perform an explicit backed-up offline migration rather than changing names opportunistically.

## Permissions

Player nodes (default true): `marketrealms.use`, `.preview`, `.rent`, `.renew`, `.build`, `.category`, `.designs`, `.restore`, `.bell`, `.shop`, `.sales`, `.trade`.

Administrative nodes (default OP): `marketrealms.admin`, `.admin.plots`, `.admin.plans`, `.admin.categories`, `.admin.designs`, `.admin.archive`, `.admin.reset`, `.admin.recovery`. Configure these in your permissions plugin; never grant administrative recovery to ordinary players.

## Configuration and storage files

| File | Purpose |
|---|---|
| `config.yml` | Worlds/spawns, preview/protection limits, rental warning timing, money toggle |
| `rental-plans.yml` | Enabled plans, duration/name, independent admin-set payment prices |
| `categories.yml` | Categories and permitted district category lists |
| `designs.yml` | Procedural shell catalog, dimensions/palettes/prices/category restrictions |
| `messages.yml` | Message templates, prefix and placeholders; menu/help explanatory labels are source-defined |
| `trade.yml` | Requests, distance, offer limits and currency increments |
| `bell.yml` | Contact cooldown, sound and offline notifications |
| `recovery.yml` | Archive format, batch limits and consumed-archive retention |
| `plots.yml` | Inclusive build boundaries, names, districts, categories and enabled state |
| `shops.yml` | Eligible single-container types and item-display scale |
| `marketrealms.db` | SQLite structured data, schema migration version 2, WAL/FULL synchronous |
| `archives/*.mrzip` | Owner-private verified build archives |
| `exports/` | Administrator exports and critical-operation/retention backups |

Small financial/rental SQL transactions run synchronously on the main thread for deterministic serialization. Large world capture/reset/restore runs in bounded main-thread batches; archive I/O is off-thread. This is not an asynchronous high-throughput database architecture; benchmark large player counts and plots before scaling. Archive file exports requested by administrators are synchronous and should not be run repeatedly during busy periods. Avoid Bukkit `/reload` and plugin hot-unload tools; use the supported configuration reload or restart.

Back up the entire MarketRealms directory **and both worlds**, plus player data and your economy provider database, while the server is stopped. Do not back up only the main SQLite file while WAL writes are active. Never delete a review intent or AVAILABLE archive to hide an error.

## Testing status and checklist

Automated tests are run by `mvn clean verify` and GitHub Actions. The current suite covers exact money arithmetic, independent currencies, inclusive bounds/design shape, offline expiry/renewal math, unique rental/operation constraints, transaction rollback, persistence across restart, inventory capacity/stock conservation, ZIP verification/expansion/traversal rejection, missing Vault, known payment compensation and uncertain-operation locks. These are unit/SQLite/mocked-API tests, not a replacement for live client tests.

Actual Paper 1.21.11 / Java 21 checks performed during development:

- Startup without Vault/Essentials, generation of both worlds, SQLite migration 1 -> 2, status/reload/clean shutdown.
- Invalid reload rejected while the previous live configuration remained active.
- Seeded expired-rental fixture captured a wood block, chest containing exactly five diamonds, and sign; verified its archive and reset all three positions.
- Seeded RESTORING state resumed after restart at a different plot; recovered the three blocks, sign and exactly five diamonds, and marked the archive USED.
- This found and fixed a Paper empty-container-lock codec issue. The failure left the destination locked and archive intact, then resumed successfully after the fix.

**Not yet verified with real multiplayer clients / a real Vault provider:** complete GUI rental purchase/renewal flows, preview isolation, actual shop GUI purchases, real provider deposits/withdrawals, complete live trade sessions and aggressive kill-at-every-step crash tests. Those require a live staging server. Use this checklist; do not mark them passed merely because the JAR compiles:

- [ ] Two non-OP clients plus an administrator: all permissions, tab completions, invalid inputs and confirmation double-clicks.
- [ ] Rent, renew, two clients racing one plot, one UUID attempting a second rental; no duplicate charge.
- [ ] Offline time, login/warning thresholds, expiration while a container/menu is open.
- [ ] Place/break outside bounds, neighbors/roads, unauthorized doors/containers, explosions, fire, fluid, pistons, hoppers, dispensers, double chests and third-party protection cancellations.
- [ ] Two preview clients with different categories/designs: neither sees the other or the other's labels/selection; exit/disconnect/world-change/reload/reconnect cleanup; marketplace unchanged.
- [ ] Purchase designs with both methods, too-small and nonempty plots; inject placement failure and verify compensation.
- [ ] Display listing/restock/edit/pause/remove, container multi-item listings, insufficient stock and orphan repair.
- [ ] Emerald-only, money-only and dual prices; independent quantities, full inventory, insufficient balances, through-wall/range/stale-entity attempts.
- [ ] Offline seller sales; independent emerald/money proceeds; repeated claims and restarts; real Vault provider rejection/exception behavior.
- [ ] Bell proximity/line of sight/cooldown and queued offline notification delivery.
- [ ] Trade both confirmations, every offer change, full inventory, item replacement, balance changes, decline/timeout, disconnect/death/world-change/distance/reload cancellation.
- [ ] Kill the server at PREPARED/APPLYING/COMMITTED boundaries for rent, sale, claim and trade. Audit snapshots/provider ledger; demonstrate administrator reconciliation exactly once.
- [ ] Archive chest variants, named items/components, sign sides/glow/color, shulker contents, furnace/brewing state, listings and bells; intentionally unsupported block entities must prevent reset.
- [ ] Interrupt capture, verified reset and both restore passes. Check automatic resume, consumed-archive rejection, too-small restoration, checksum corruption, export and retention.
- [ ] Benchmark maximum plot volumes, concurrent preview viewers and large listing counts with your actual plugins.

## GitHub Actions

The exported standalone project includes `.github/workflows/build.yml`: Java 21 setup, Maven `clean verify`, then upload the shaded JAR as artifact **MarketRealms**. Push the ZIP contents at your repository root, open the Actions tab, select the build and download its artifact. If you keep this folder nested in a larger repository, move/adapt the workflow to the outer repository's `.github/workflows/` and add `working-directory: MarketRealms` to the Maven step. GitHub does not run a nested workflow automatically.
