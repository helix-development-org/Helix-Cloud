# helix-addon-citybuild – action contract

This file is the contract between the CityBuild node addon and the CityBuild Paper plugin
(`/workspace/CityBuild`). Every action below is registered with `bridgeInvocable = true`, so the Paper
plugin reaches it with its per-service token through `ServiceNodeApi.action(ActionInvocation(name, args))`.

## Conventions

- **Players** are UUID strings (`11111111-1111-1111-1111-111111111111`).
- **`shard`** is the service id of a city instance (`CityBuild-1`, `CityBuild-2`, `CityBuild-3`, i.e. `HELIX_SERVICE_ID`).
- **Timestamps** are epoch **milliseconds**. Calendar days (daily reward, daily limits) are ISO dates in the
  zone of setting `general.zone` (default `Europe/Berlin`).
- **Items** are the Base64 text of Paper's `ItemStack.serializeAsBytes()`. The node never decodes them.
- **JSON payload** is always the last argument. It may span several arguments, which the node joins with spaces.
- **Success**: `success = true`, `lines[0]` = compact JSON (object, array or `null`).
- **Failure**: `success = false`, `lines[0]` = **error code**, `lines[1]` = English detail (for logs).
  Translate the code for players; never show `lines[1]`.
- **Tier** arguments are the player's tier 0–3, which Paper derives from `citybuild.tier.1..3`. Limit
  arguments are the tier's value from the `limits` collection, which Paper resolves. The node trusts bridge
  arguments.
- **Permission** columns are informational. Bridge calls are not permission-checked by the node, so the Paper
  plugin must check the player's permission before calling an admin action.
- **Callers**: every action is registered with `bridgeTasks = {"CityBuild"}` — only services of the
  `CityBuild` task may call them over the bridge (Lobby, MineClash and other servers get an error). Calls
  with the static admin token (CLI, panel) are not restricted.
- **Idempotency**: `ServiceNodeApi.action` always sends an `ActionInvocation.requestId`; the node runs each
  `(service, requestId)` at most once and answers repeats with the original result for ten minutes. A `null`
  result means the outcome is **unknown** — do not refund or roll back on `null`; retry with the **same**
  `requestId` (set it on the invocation) to learn the original result.
- **Compensation**: `auction.buy` and `plotmarket.buy` reverse the payment (seller loses what was received,
  buyer gets the full price back, log kind `refund`) when the state change after a successful payment fails;
  the listing returns to `active`.

### Error codes

| Code | Meaning |
|---|---|
| `usage` | missing/invalid argument (`lines[1]` contains the usage) |
| `invalid`, `invalid_name`, `invalid_id`, `invalid_price`, `invalid_duration`, `invalid_amount`, `invalid_item`, `invalid_plot`, `invalid_slot` | validation failed |
| `not_found` | unknown id/document |
| `not_available` | listing/offer not buyable (sold, reserved, expired, outside its time window) |
| `not_owner`, `own_listing`, `wrong_shard` | ownership / instance mismatch |
| `limit` | tier limit reached, or daily Kronen limit reached (for `adminshop.sell` `lines[1]` = Kronen still allowed today) |
| `item_limit` | daily per-ware sell limit reached |
| `cooldown` | kit/job-switch cooldown running (`lines[1]` = seconds left) |
| `already_claimed`, `already_active`, `already_listed` | one-time thing already done |
| `tier` | player tier too low |
| `sold_out` | offer stock or per-player limit exhausted |
| `not_for_sale`, `not_bought` | merchant does not sell / buy that ware |
| `not_pending`, `not_milestone`, `level_not_reached` | state checks |
| `world_not_allowed` | home in a world outside `homes.allowedWorlds` |
| `insufficient_funds`, `economy_unavailable` | from the Kronen economy |
| `error` | anything else |

## Economy dependency

The node books Kronen itself through `helix-addon-economy` (`KronenGateway`):

- `eco.api.deposit <uuid> <n> <reason> <ref> [limitKey limitMax]`
- `eco.api.withdraw <uuid> <n> <reason> <ref>`
- `eco.api.transfer <from> <to> <n> <reason> <feePermille> <ref>`: the sender pays `n`, the receiver gets
  `n − fee`, and the fee leaves the economy.

Network-wide daily limit keys used: `citybuild.jobs` (setting `jobs.dailyCap`) and `citybuild.sell` (setting
`adminshop.dailySellCap`). The node also clamps against its own counters, so payouts can be partial.

Paper calls the economy **directly** only for flows without node state:
- plot claim, merge, clear and biome (`withdraw`)
- player shops (`transfer` with `fees.shopPermille`)
- `/trade` (`transfer` with `fees.tradePermille`)

Paper logs those flows via `citybuild.log.write`.

## Records (JSON shapes)

```text
Home        {uuid, shard, name, world, x, y, z, yaw, pitch}
Vehicle     {id, uuid, shard, model, color, locked, access: all|members|none, state: garage|world,
             world?, x?, y?, z?, createdAt, updatedAt}
Warp        {shard, name, icon, description, world, x, y, z, yaw, pitch, sortOrder}
Auction     {id, shard, seller, sellerName, item, material, displayName, category, amount, price,
             createdAt, expiresAt, status: active|reserved|sold|cancelled|expired, buyer, closedAt, sellerNotified}
Pickup      {id, shard, uuid, item, source, createdAt}
PlotListing {id, shard, plotId, seller, sellerName, price, world, x, z, size,
             status: active|reserved|pending|sold|withdrawn, buyer, createdAt, closedAt}
JobProfile  {jobs: {<jobId>: {level, xp}}, active: [jobId|null …], switchedAt: [ms …], milestones: ["<job>:<level>" …]}
SerialRecord{serial, forgeId, version, issuedTo, source, shard, issuedAt}
LogEntry    {id, kind, shard, actor, target, amount, ref, data (JSON text), at}
CrateEntry  {id, type: forge|item|material|kronen|key|model, ref, data, amount, weight, rarity: NORMAL|SPECIAL|ELITE|TITAN|BOSS}
CrateSpec   {name, rarity, look: CHEST|BARREL|ENDER_CHEST|<model>, key (forge template id, default key_<crateId>),
             announceFrom (rarity|null), entries: [CrateEntry]}
OfferWare   {type: forge|key|item|material|model, ref, data, amount}   (model: ref = catalog id)
OfferSpec   {name, ware: OfferWare, price, stock|null, perPlayer|null, startsAt|null, endsAt|null, minTier, shards: []}
```

Crate prize `ref` by type:

| `type` | `ref` / other field |
|---|---|
| `forge` | forge template id |
| `material` | Bukkit material |
| `key` | crate id |
| `item` | `data` = Base64 item |
| `kronen` | `amount` = Kronen; the node pays these itself on `crate.open` |

Offer ware `ref` by type:

| `type` | `ref` / other field |
|---|---|
| `forge` | template id |
| `key` | crate id |
| `material` | material |
| `item` | `data` |

## Config collections (in the bundle)

`citybuild.config.bundle` returns `{"version":N,"settings":{key:value},"collections":{name:[{id,sortOrder,body}]}}`.
Poll `citybuild.config.version` (MineClash pattern: 15 s) and reload on change. Forge templates, crates and
offers are collections too, so their edits reach every instance through the same poll (live update).

| Collection | Document id | Body |
|---|---|---|
| `servers` | `CityBuild` (task) | `{mode:"CITYBUILD", enabled, instances:[…]}`; dormant when missing |
| `adminshop_categories` | category id | `{name, icon}` |
| `adminshop` | lower-case material | `{material, category, buy, sell, unit, dailyLimit}`; `buy`/`sell` are Kronen per `unit` items, `null` = not offered, `dailyLimit` `null` = `adminshop.defaultItemLimit` |
| `jobs` | `holzfaeller`, `miner`, `graeber`, `farmer`, `jaeger`, `fischer` | `{name, icon, villager, worlds:[…], plots:bool, actions:{"break:STONE":0.05, "harvest:WHEAT":0.2, "breed:COW":1, "kill:ZOMBIE":1, "fish:FISH":2 …}}` (see below) |
| `kits` | `starter`, `taeglich`, `woechentlich`, `baumeister` | `{name, icon, cooldownSeconds, once, minTier, items:[…]}`; item shapes below |
| `rewards` | `day1`…`day7`, `job_10`, `job_25`, `job_50`, `tutorial` | `{kronen, items:[kit item shapes], keys:[{crate, amount}]}` |
| `limits` | `tier0`…`tier3` | `{plots, homes, shops, auctions, inactivityDays, back, weeklyKit, builderKit, furniture, livestock}` (`furniture`/`livestock` per plot) |
| `villagers` | e.g. `fritz_feilbiet` | `{name, role, location, robe, prop, quote}` (13 CityBuild figures incl. the dealers `stellmacher`, `moebel`, `tiere`) |
| `enchants` | e.g. `erntesegen` | `{name, appliesTo:[…], effect, perLevel, suggestedMax}` |
| `abilities` | e.g. `flaechenabbau` | `{name, appliesTo:[…], params:{…}, multiBlock, disabledWorlds:[…]}` |
| `crates` | crate id | `CrateSpec` (seed: `standard`) |
| `forge` | template id | `{name, version, updatedBy, updatedAt, spec}`, where `spec` is Paper's item model (opaque to the node) |
| `offers` | offer id | `OfferSpec` |
| `models` | catalog id `[a-z0-9_]{1,40}` | `{kind, name, itemModel?, rig?, variants:[…], price\|null, dealer\|null, data:{…}}`; kinds and `data` fields see `config/ModelSeeds.kt` (game concept A21). Item model default `citybuild:model/<kind>/<id>`, variant `<itemModel>_<variant>`. |
| `recipes` | recipe id | `{inputs:[{model\|material, amount}], output:{model\|material, amount}, seconds}` (stove) |
| `arenas` | shard | free; reserved for the arena region (`{world, pos1, pos2}`), written via `config.doc.set` |

Keys of the `jobs` collection's `actions` map:

| Key | Meaning |
|---|---|
| `break:<MATERIAL>` | block broken |
| `harvest:<CROP>` | crop harvested (block material: `WHEAT`, `CARROTS`, …) |
| `breed:<ENTITY>` | animal bred |
| `kill:<ENTITY>` | mob killed |
| `fish:FISH`, `fish:TREASURE`, `fish:JUNK` | catch type |

Item shapes in `kits` and `rewards`:

| Shape | Meaning |
|---|---|
| `{type:"material", material, amount}` | a plain material |
| `{type:"item", data, amount}` | a serialized item stack |
| `{type:"forge", ref, amount}` | a forge item by reference, issued via `forge.issue` with source `kit` |
| `{type:"model", ref, amount}` | a model catalog item (collection `models`) by reference |
| `{type:"choice", options:[materials], picks, amount}` | the player chooses `picks` materials |

All settings with types and defaults: `citybuild.config.export`. Groups:
`general.*`, `plots.*`, `plotmarket.*`, `fees.*`, `adminshop.*`, `jobs.*`, `auction.*`, `teleport.*`, `tpa.*`,
`homes.*`, `trade.*`, `arena.*`, `farm.*`, `reward.*`, `forge.*`, `crates.*`.

## Actions

### Config

| Action | Arguments | Answer | Permission |
|---|---|---|---|
| `citybuild.config.bundle` | – | bundle (see above) | – |
| `citybuild.config.version` | – | number | – |
| `citybuild.config.export` | – | `[{key, value, type, default, description}]` | – |
| `citybuild.config.set` | `<key> <value…>` | `{key: normalizedValue}` | citybuild.admin.config |
| `citybuild.config.reset` | `<key>` | `{key: default}` | citybuild.admin.config |
| `citybuild.config.collections` | – | `["adminshop", …]` | – |
| `citybuild.config.docs` | `<collection>` | `[{id, sortOrder, body}]` | – |
| `citybuild.config.doc.set` | `<collection> <id> <sortOrder> <json body>` | `{version}` | citybuild.admin.config (Paper: sub-permission of the edited area) |
| `citybuild.config.doc.remove` | `<collection> <id>` | `{version}` | citybuild.admin.config |
| `citybuild.config.seed` | `[force]` | `{written}` | citybuild.admin.config |

### Homes and warps

| Action | Arguments | Answer | Permission |
|---|---|---|---|
| `citybuild.home.list` | `<uuid> <shard>` | `[Home]` (by name) | – |
| `citybuild.home.set` | `<uuid> <shard> <limit> <json {name, world, x, y, z, yaw, pitch}>` | `Home`; errors `invalid_name`, `world_not_allowed`, `limit`. The same name (ignoring case) moves the home. | – |
| `citybuild.home.delete` | `<uuid> <shard> <name>` | `{deleted:true}` / `not_found` | – |
| `citybuild.warp.list` | `<shard>` | `[Warp]` | – |
| `citybuild.warp.set` | `<shard> <json {name, icon, description, world, x, y, z, yaw, pitch, sortOrder}>` | `Warp` | citybuild.admin.warps |
| `citybuild.warp.delete` | `<shard> <name>` | `{deleted:true}` | citybuild.admin.warps |

### Kits

| Action | Arguments | Answer | Permission |
|---|---|---|---|
| `citybuild.kit.claim` | `<uuid> <shard> <kit> <tier> [bypass]` | kit body; Paper hands out `items`. Errors: `tier`, `cooldown` (`lines[1]` = seconds), `already_claimed`. Pass `bypass` only with `citybuild.bypass.cooldown`. | – |
| `citybuild.kit.state` | `<uuid> <shard> <tier>` | `{kitId: {available, remainingSeconds, claimedOnce, tierOk}}` | – |
| `citybuild.kit.list` | – | `[{id, body}]` | – |

### Auction house and pickup box (per instance)

| Action | Arguments | Answer | Permission |
|---|---|---|---|
| `citybuild.auction.list` | `<shard> <page> <pageSize> [json {category?, search?, sort?: newest\|price_asc\|price_desc, seller?}]` | `{page, pages, total, entries:[Auction]}` (only active, not expired) | – |
| `citybuild.auction.create` | `<uuid> <shard> <limit> <json {sellerName, item, material, displayName, category, amount, price, hours}>` | `Auction`. The listing fee (`fees.auctionListingPermille`, min `fees.auctionListingMin`) is debited and never refunded. Remove the item from the player **after** success. Errors: `invalid_price`, `invalid_duration`, `invalid_item`, `limit`, `insufficient_funds`. | – |
| `citybuild.auction.fee` | `<price>` | `{fee}` | – |
| `citybuild.auction.buy` | `<uuid> <shard> <id>` | `Auction` (status `sold`, with `item`). Paper gives the item, or puts it into the pickup box (`pickup.give`) when the inventory is full. The seller gets `price − fees.auctionSalePermille`. Errors: `not_available`, `own_listing`, `wrong_shard`, `insufficient_funds`. | – |
| `citybuild.auction.cancel` | `<uuid> <shard> <id> [admin]` | `Pickup` (the item is moved into the seller's box) | `admin` flag: citybuild.admin |
| `citybuild.auction.expire` | `<shard>` | `[{auctionId, seller, pickupId}]`; call periodically (e.g. every minute) | – |
| `citybuild.auction.mine` | `<uuid> <shard>` | `[Auction]` active and reserved | – |
| `citybuild.auction.sold` | `<uuid> <shard>` | `[Auction]` sales not yet announced to the seller (marks them); call on join | – |
| `citybuild.pickup.list` | `<uuid> <shard>` | `[Pickup]` | – |
| `citybuild.pickup.take` | `<uuid> <shard> <id>` | `Pickup` (removed atomically, give it now) / `not_found` | – |
| `citybuild.pickup.give` | `<uuid> <shard> <source> <item>` | `Pickup` (offline gifts, full inventories; source e.g. `give`, `auction.buy`, `crate`) | – |

### Plot market (per instance)

| Action | Arguments | Answer | Permission |
|---|---|---|---|
| `citybuild.plotmarket.list` | `<shard>` | `[PlotListing]` active | – |
| `citybuild.plotmarket.of` | `<shard> <plotId>` | `PlotListing` or `null` | – |
| `citybuild.plotmarket.offer` | `<uuid> <shard> <json {sellerName, plotId, price, world, x, z, size}>` | `PlotListing`. Errors: `invalid_price`, `already_listed`, `limit` (`plotmarket.maxOffers`). | – |
| `citybuild.plotmarket.withdraw` | `<uuid> <shard> <id>` | `PlotListing` (withdrawn) | – |
| `citybuild.plotmarket.remove` | `<actorUuid> <shard> <id>` | `PlotListing` (withdrawn by the team) | citybuild.admin |
| `citybuild.plotmarket.buy` | `<uuid> <shard> <id>` | `PlotListing` with status `pending` and `buyer`. Check the buyer's plot limit **before** calling. After success, transfer the plot with PlotSquared (owner = buyer; clear trusted/members/denied; deactivate shops), then call `complete`. The seller gets `price − fees.plotMarketPermille`. | – |
| `citybuild.plotmarket.complete` | `<shard> <id>` | `PlotListing` (sold) / `not_pending` | – |
| `citybuild.plotmarket.pending` | `<shard>` | `[PlotListing]` paid but not handed over; reconcile on start | – |

### Jobs (network-wide)

| Action | Arguments | Answer | Permission |
|---|---|---|---|
| `citybuild.jobs.load` | `<uuid>` | `JobProfile`, normalized to `jobs.maxActive` slots | – |
| `citybuild.jobs.save` | `<uuid> <json {jobs:{id:{level,xp}}}>` | `JobProfile` (progress merged; slots and milestones kept) | – |
| `citybuild.jobs.select` | `<uuid> <slot> <job\|none> [bypass]` | `JobProfile`. Errors: `invalid_slot`, `not_found`, `already_active`, `cooldown` (`lines[1]` = seconds). | – |
| `citybuild.jobs.payout` | `<uuid> <kronen> <job>` | `{paid, remaining, capped[, code]}`. Send whole Kronen; keep the milli-Kronen remainder on Paper. | – |
| `citybuild.jobs.milestone` | `<uuid> <job> <level>` | rewards body `job_<level>` + `{paid:true, kronen}` (Kronen already paid; Paper hands out `items`/`keys`). Errors: `not_milestone`, `level_not_reached` (call `jobs.save` first), `already_claimed`. | – |
| `citybuild.jobs.curve` | – | `[xp for level 1, level 2, …]` = `jobs.levelBase × n^jobs.levelExponent` | – |

### Daily reward and tutorial (network-wide)

| Action | Arguments | Answer | Permission |
|---|---|---|---|
| `citybuild.reward.state` | `<uuid>` | `{claimedToday, streak, nextDay, lastDay}` | – |
| `citybuild.reward.claim` | `<uuid>` | rewards body `day<N>` + `{day, paid:true, kronen}`. Paper hands out `items` and crate `keys` on the current instance. Missing a day restarts at day 1; after day `reward.streakDays` it restarts at 1. Error: `already_claimed`. | – |
| `citybuild.tutorial.state` | `<uuid>` | `{done, bonusPaid}` | – |
| `citybuild.tutorial.complete` | `<uuid>` | rewards body `tutorial` + `{paid:true, kronen}`, or `{paid:false, kronen:0}` when it was already paid | – |

### Merchant (admin shop)

| Action | Arguments | Answer | Permission |
|---|---|---|---|
| `citybuild.adminshop.buy` | `<uuid> <shard> <ware> <amount>` | `{material, amount, paid, balance}`. `amount` must be a multiple of the ware's `unit`. Give the items after success; refund via `eco.api.deposit` if they do not fit. | – |
| `citybuild.adminshop.sell` | `<uuid> <shard> <ware> <amount>` | `{material, amount, paid, itemRemaining, kronenRemaining}`. Check that the player holds the items, call, then remove the items. Errors: `limit` (`lines[1]` = Kronen left today), `item_limit`, `not_bought`. | – |
| `citybuild.adminshop.state` | `<uuid>` | `{kronenSold, kronenCap, kronenRemaining, items:{ware: soldToday}}` | – |

### Forge

| Action | Arguments | Answer | Permission |
|---|---|---|---|
| `citybuild.forge.list` | – | `[{id, name, version, updatedBy, updatedAt, spec}]` (also in the bundle, collection `forge`) | – |
| `citybuild.forge.get` | `<id>` | `{id, name, version, updatedBy, updatedAt, spec}` | – |
| `citybuild.forge.save` | `<uuid> <id> <json {name, spec}>` | `{id, name, version, …}`, version +1 per save. Id `[a-z0-9_]{1,48}`, not `_oneoff`. | citybuild.admin.forge |
| `citybuild.forge.delete` | `<uuid> <id>` | `{deleted:true}` (issued items keep their frozen data) | citybuild.admin.forge |
| `citybuild.forge.issue` | `<uuid> <shard> <forgeId\|_oneoff> <source> [count 1–64]` | `{forgeId, version, serials:[…], spec}`. Write `forge_id`, `forge_version` and the serial into each item's PDC. Sources: `give`, `kit`, `crate`, `offer`, `reward`, `forge`. | – |
| `citybuild.forge.track` | `<serial>` | `SerialRecord` | citybuild.admin.logs |

### Crates

| Action | Arguments | Answer | Permission |
|---|---|---|---|
| `citybuild.crate.list` | – | `[{id, spec: CrateSpec, chances:{entryId: percent}}]` | – |
| `citybuild.crate.save` | `<uuid> <id> <json CrateSpec>` | `CrateSpec`. `key` defaults to `key_<id>`; Paper creates that forge template when missing. Id `[a-z0-9_]{1,32}`. | citybuild.admin.crates |
| `citybuild.crate.delete` | `<uuid> <id>` | `{deleted:true}` | citybuild.admin.crates |
| `citybuild.crate.open` | `<uuid> <shard> <crateId>` | `{crate, entry: CrateEntry, announce, paid}`. Consume the key **before** calling and return it on failure. Kronen prizes are already paid (`paid`); for every other type Paper hands the prize out (forge items via `forge.issue` with source `crate`). When `announce` is true, broadcast network-wide. | – |

### Special offers

| Action | Arguments | Answer | Permission |
|---|---|---|---|
| `citybuild.offer.list` | `<shard> <uuid> <tier>` | `[{id, spec: OfferSpec, stockLeft\|null, bought}]` for offers visible now | – |
| `citybuild.offer.all` | – | `[{id, spec}]` (admin hub) | – |
| `citybuild.offer.save` | `<uuid> <id> <json OfferSpec>` | `OfferSpec` | citybuild.admin.offers |
| `citybuild.offer.delete` | `<uuid> <id>` | `{deleted:true}` | citybuild.admin.offers |
| `citybuild.offer.buy` | `<uuid> <shard> <offerId> <count 1–64> <tier>` | `{offer, spec, count, paid}`. Hand out `count × ware.amount`. Errors: `not_available`, `tier`, `sold_out`, `insufficient_funds`. | – |

### Vehicles (per instance, concept A21.3)

| Action | Arguments | Answer | Permission |
|---|---|---|---|
| `citybuild.vehicle.register` | `<uuid> <shard> <model> [color]` | `Vehicle` in the garage; errors `unknown_model`, `invalid_model` (catalog kind ≠ `vehicle`), `limit` (`vehicles.maxPerPlayer`). The colour is clamped to the model's `data.colors`. | – |
| `citybuild.vehicle.list` | `<uuid> <shard>` | `[Vehicle]` oldest first | – |
| `citybuild.vehicle.update` | `<uuid> <id> <json {color?, locked?, access?}>` | `Vehicle`; errors `not_found`, `not_owner`, `invalid_access`, `invalid_color` | – |
| `citybuild.vehicle.park` | `<uuid> <id> <garage\|world> [world x y z]` | `Vehicle`; `world` needs the position (`invalid_position`) | – |
| `citybuild.vehicle.remove` | `<uuid> <id>` | `{removed:true}`; only the owner | – |
| `citybuild.vehicle.recall` | `<shard>` | `{recalled:n}`: every vehicle of the instance marked `world` goes back to the garage (called on instance start) | – |

### Audit log, profile, arena

| Action | Arguments | Answer | Permission |
|---|---|---|---|
| `citybuild.log.write` | `<kind> <shard> <actorUuid> [json {target, amount, ref, data}]` | `{logged:true}`. Kinds written by Paper: `shop`, `trade`, `plot.claim`, `plot.merge`, `plot.clear`, `give`. | – |
| `citybuild.log.query` | `[json {actor?, ref?, kind?, limit? (≤ 500)}]` | `[LogEntry]` newest first. `actor` also matches `target`; `ref` e.g. `auction:17`. | citybuild.admin.logs |
| `citybuild.profile.report` | `<uuid> <shard> <json {plots, …}>` | `{stored:true}`; feeds the network profile (`plots` summed over instances) | – |
| `citybuild.arena.record` | `<uuid> <kills> <deaths>` | `{accepted}`; forwards to `stats.add citybuild.arena.kills/deaths` | – |
| `citybuild.arena.top` | `[limit 1–20, default 10]` | `[{rank, name, kills}]` from `stats.top citybuild.arena.kills`; empty array without `helix.stats` | – |

Kinds the node writes itself:

| Group | Kinds |
|---|---|
| Auctions | `auction.create`, `auction.sale`, `auction.cancel` |
| Plot market | `plotmarket.offer`, `plotmarket.sale`, `plotmarket.withdraw` |
| Merchant | `adminshop.buy`, `adminshop.sell` |
| Forge | `forge.save`, `forge.delete`, `forge.issue` |
| Crates | `crate`, `crate.save` |
| Offers | `offer`, `offer.save` |
| Rewards and kits | `reward`, `kit` |
| Vehicles | `vehicle`, `vehicle.remove` |

## Storage

| Mode | Where the data lives |
|---|---|
| PostgreSQL (node storage mode `postgres`) | schema `citybuild` |
| Fallback without PostgreSQL | addon document store (`data.*` keys, every write synchronized on the node) |

Tables in schema `citybuild`:

| Group | Tables |
|---|---|
| Teleports and kits | `homes`, `warps`, `kit_claims` |
| Auctions and pickup | `auctions`, `pickup` |
| Plot market | `plot_market` |
| Player progress | `jobs` (JSONB profile), `rewards`, `tutorial`, `daily_counters`, `profile_reports` |
| Offers and forge | `offer_buys`, `forge_serials` |
| Vehicles | `vehicles` (own pool `citybuild-vehicles`) |
| Audit | `logs` |

Config, including forge templates, crates and offers, lives in the addon config store (`config.*` keys).

## Translations

The texts live as fragments in `src/main/lang/<locale>/<module>.json`. Task `mergeLang` merges them into
`lang/<locale>.json`; a duplicate key fails the build. The Paper plugin adds its own fragments to the same
directory (for example `src/main/lang/de-DE/paper-auction.json`) and merges them the same way for its bundled
fallback. The node's own fragment is `node.json` (profile labels).
