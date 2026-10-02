# The vendored Minecraft tree: what is in it, and what is not

**What this document is.** A reference for the vendored tree at
`client/src/main/java/net/minecraft/**`, written so that somebody who was not in the room can
answer **"can I cite this file?"** without running anything.

**Every `file:line` below was opened and read when this file was written.** Every count was
re-derived from the files on disk, not copied from the audit that prompted the document
(`.ai-notes/docs/audits/2026-10-02-wave13-vendored.md`, gitignored — read it for *how* the
findings were reached, read this for *what is true*).

**Do not add anchors to this file without opening the target.** The rules that say why are in §4.1.

---

## 1. Extent

**1612 `.java` files** under `client/src/main/java/net/minecraft`, in **25** top-level packages
and **97** distinct package paths.

```
find client/src/main/java/net/minecraft -name '*.java' | wc -l                        # 1612
find client/src/main/java/net/minecraft -name '*.java' -printf '%h\n' | sort -u | wc -l   # 97
```

Two different "package" counts appear in circulation and they do not agree, so the number to use
is worth being precise about. 76 appears in the audit and is wrong for any reading of "package
path" on disk:

| what you count | how | total |
|---|---|---|
| top-level packages | `find -name '*.java' -printf '%P\n' \| cut -d/ -f1 \| sort -u` | **25** |
| package paths (directories holding `.java`) | `find -printf '%h\n' \| sort -u` | **97** |
| distinct `package` declarations in the sources | `grep -rh '^package net\.minecraft' \| sort -u` | **97** |

The last two agree, so 97 is the number; 76 is not reachable from the tree.

### 1.1 Files per top-level package

| package | files | package | files | package | files |
|---|---|---|---|---|---|
| `client` | 492 | `inventory` | 30 | `pathfinding` | 8 |
| `world` | 191 | `tileentity` | 24 | `dispenser` | 8 |
| `block` | 176 | `server` | 24 | `potion` | 7 |
| `entity` | 172 | `realms` | 23 | `village` | 6 |
| `network` | 130 | `enchantment` | 21 | `profiler` | 3 |
| `item` | 91 | `nbt` | 18 | `init` | 3 |
| `util` | 82 | `stats` | 11 | `event` | 2 |
| `command` | 76 | `scoreboard` | 11 | `crash` | 2 |
| | | | | `creativetab` | 1 |

Top-level counts include subpackages. The largest single directory is `block` at 155 files (176
minus `block/material` 6, `block/properties` 6, `block/state` 5, `block/state/pattern` 4), then
`util` at 82, `client/gui` at 81, `item` at 72, `network/play/server` at 71,
`entity/ai` at 58, `client/renderer/entity` at 57, `client/model` at 52.

`client` alone is 492 files and `client/resources` is 49 of them (26 directly, 15 under `data`,
8 under `model`).

---

## 2. The two registries — the load-bearing facts

**These bounds are the reason this document exists.** A block or item id outside them cannot be
checked against this tree, and until this section was written, **no document, test, or comment in
the repository said so.**

### 2.1 The block registry is a contiguous prefix; ids 198–255 are registered by nothing

`Block.registerBlocks()` (`Block.java:1249`) issues **198** calls to
`registerBlock(int, ...)`, with **198 distinct ids**, and the set of ids is **exactly `0..197`** —
a contiguous prefix with no interior holes.

```bash
grep -oP 'registerBlock\(\d+,' client/src/main/java/net/minecraft/block/Block.java | wc -l   # 198
grep -oP 'registerBlock\(\K\d+' client/src/main/java/net/minecraft/block/Block.java \
  | sort -n | uniq > /tmp/actual.txt
seq 0 197 > /tmp/want.txt
diff /tmp/want.txt /tmp/actual.txt                                                          # no output
```

The `diff` is the load-bearing half: it proves *contiguity*, which a count cannot.

Two independent facts make this the whole story rather than a sample:

- All 198 calls are inside `Block.java:1251–1461`. There are **zero** `registerBlock(` calls
  outside that span.
- `blockRegistry.register(` has exactly **one** call site in the entire vendored tree,
  `Block.java:1500`, inside `registerBlock`. Nothing else can add a block.

Registration stops at 197 and jumps straight to `validateKey()`:

```
client/src/main/java/net/minecraft/block/Block.java:1461
        registerBlock(197, "dark_oak_door", (new BlockDoor(Material.wood)).setHardness(3.0F)
            .setStepSound(soundTypeWood).setUnlocalizedName("doorDarkOak").disableStats());
    1462:        blockRegistry.validateKey();
```

`init/Blocks.java` corroborates it. It declares **198** `public static final` fields and
resolves all 198; mapping each field to its `getRegisteredBlock("<name>")` id yields exactly the
set `0..197`, one field per id. Four field names do **not** match their registry name, which is a
trap for anyone grepping by name rather than by field:

| field | registry name | id |
|---|---|---|
| `oak_door` | `wooden_door` | 64 |
| `oak_fence` | `fence` | 85 |
| `oak_fence_gate` | `fence_gate` | 107 |
| `slime_block` | `slime` | 165 |

**Vanilla 1.8.9 has 256 block ids. Ids 198–255 — all 58 — are registered by nothing here.** The
1.8 additions that follow `dark_oak_door` in the vanilla table are absent: probing
`init/Blocks.java` for `wooden_slab2`, `chiseled_bookshelf`, `end_bricks`,
`mossy_stonebrick_stairs`, `stonebrick_stairs`, `iron_brick_stairs` returns **zero hits each**,
while `double_stone_slab2` and `stone_slab2` return 2 each.

**Consequence to remember when reading the world decoder:** an id in 198–255 that arrives from a
server is not an error in this tree. It is silently replaced by air —
`ExtendedBlockStorage.java:45-48` returns `Blocks.air.getDefaultState()` when the state id
resolves to `null`, and `Chunk.java:602` has the same shape.

### 2.2 The item registry is sparse, and ItemBlocks sit on the block's own id

`Item` has **187** explicit `registerItem(int, ...)` calls with **187 distinct ids**, laid out as:

| range | ids | registered |
|---|---|---|
| `256–425` | 170 | all, contiguous |
| `426` | 1 | **nothing — a hole inside an otherwise contiguous run** |
| `427–431` | 5 | all (the wood doors) |
| `432–2255` | 1824 | **nothing** |
| `2256–2267` | 12 | all (the records) |

The hole is visible in the source, one line apart:

```
client/src/main/java/net/minecraft/item/Item.java:935
        registerItem(425, "banner", (new ItemBanner()).setUnlocalizedName("banner"));
    936:        registerItem(427, "spruce_door", (new ItemDoor(Blocks.spruce_door)).setUnlocalizedName("doorSpruce"));
```

The second registration mechanism is the part that matters, and it is **not** a numeric
allocation — it is an alias of the block's own id:

```
client/src/main/java/net/minecraft/item/Item.java:966
    protected static void registerItemBlock(Block blockIn, Item itemIn)
    {
        registerItem(Block.getIdFromBlock(blockIn), (ResourceLocation)Block.blockRegistry.getNameForObject(blockIn), itemIn);
        BLOCK_TO_ITEM.put(blockIn, itemIn);
    }
```

`Block.getIdFromBlock` is `blockRegistry.getIDForObject(blockIn)` (`Block.java:152-155`), so an
ItemBlock is registered **under its block's id**, which is bounded by §2.1. `Item` invokes
`registerItemBlock` 153 times over **150 distinct** block fields, resolving to **150 distinct
ids spanning 1..192** — inside the block range, and nowhere near the explicit `256+` range.

**The two mechanisms therefore do not collide, and the registry is internally consistent.** What
this repository does not record anywhere, and what this document therefore cannot answer, is
whether `0` is the correct base for a block's item form **in vanilla 1.8.9**. That number is a
fact about vanilla, not about this tree, and it was searched for in `docs/` and `.ai-notes/` and
not found. `Item.getIdFromItem` is what goes on the wire (`PacketBuffer.java:240`), so a wrong
base is a wire-level defect, not a cosmetic one — and it is **an open question, not a finding.**

**Summary of the bounds:**

| registry | populated ids | unreachable ids |
|---|---|---|
| block | `0–197` (198 of them, contiguous) | `198–255` (58) |
| item, explicit | `256–425`, `427–431`, `2256–2267` (187) | `426`, `432–2255` (1825) |
| item, via `registerItemBlock` | 150 ids in `1..192`, one per block | — |

---

## 3. Conspicuous absences, and why each is absent

An absent file has three possible causes, and they call for different responses. This section
names which is which, because **"the file is missing" and "the feature is missing" are different
facts** and conflating them is how a substitution gets reported as an omission.

### 3.1 `BlockLava.java` — a **substitution**, not an omission

The file does not exist. Lava still exists, and lava still burns you. The block is registered
under `BlockStaticLiquid` / `BlockDynamicLiquid` instead of a lava-specific class:

```
client/src/main/java/net/minecraft/block/Block.java:1263
        registerBlock(10, "flowing_lava", (new BlockDynamicLiquid(Material.lava))...
    1264:        registerBlock(11, "lava", (new BlockStaticLiquid(Material.lava))...
```

`init/Blocks.java:49-50` declares the fields with those types, and `init/Blocks.java:266-267`
casts them back out of the registry as `BlockStaticLiquid` / `BlockDynamicLiquid`.

**What follows from the substitution:** the *class* that would carry lava-specific behaviour is
gone, but the behaviour is not. `BlockStaticLiquid` converts a source into the flowing block and
schedules its tick:

```
client/src/main/java/net/minecraft/block/BlockStaticLiquid.java:35
    private void updateLiquid(World worldIn, BlockPos pos, IBlockState state)
    {
        BlockDynamicLiquid blockdynamicliquid = getFlowingBlock(this.blockMaterial);
        worldIn.setBlockState(pos, blockdynamicliquid.getDefaultState().withProperty(LEVEL, state.getValue(LEVEL)), 2);
        worldIn.scheduleUpdate(pos, blockdynamicliquid, this.tickRate(worldIn));
    }
```

`getFlowingBlock` (`BlockLiquid.java:379-393`) returns `Blocks.flowing_lava` for
`Material.lava`, and `BlockDynamicLiquid.updateTick` (`:27`) does the spreading.
**Lava does flow in this tree.** What the substitution costs is the lava-specific *logic*, which
lives in `BlockStaticLiquid.updateTick` (`:42-86`, the `doFireTick` spread-and-ignite branch)
and in `BlockLiquid.checkForMixing` (`:307-343`, water/lava interaction producing obsidian or
cobblestone) rather than in a class named `BlockLava`. The audit this document was derived from
asserted the opposite — that a `BlockStaticLiquid` "never schedules a fluid update", so lava
does not flow — and **that assertion is false**; `BlockStaticLiquid.java:39` is the schedule.

**What is unaffected:** the hazard. `Entity.setOnFireFromLava` is present and correct:

```
client/src/main/java/net/minecraft/entity/Entity.java:539
    protected void setOnFireFromLava()
    {
        if (!this.isImmuneToFire)
        {
            this.attackEntityFrom(DamageSource.lava, 4.0F);
            this.setFire(15);
        }
    }
```

called from `Entity.java:508-510` inside the `isInLava()` branch of `update`. **The hazard, the
flow, and the water interaction are all present.** Only the class name is gone, and the lava
logic it would have held now sits in the two liquid classes.

### 3.2 The wooden slab2 family — an **omission**, and asymmetric within one family

`BlockDoubleWoodSlabNew.java` and `BlockWoodSlabNew.java` **do not exist**. Their stone
counterparts all three do, and are registered:

| file | exists | registered |
|---|---|---|
| `block/BlockStoneSlabNew.java` | yes | abstract superclass, not registered itself |
| `block/BlockDoubleStoneSlabNew.java` | yes | `Block.java:1445`, id 181 `double_stone_slab2` |
| `block/BlockHalfStoneSlabNew.java` | yes | `Block.java:1446`, id 182 `stone_slab2` |
| `block/BlockWoodSlabNew.java` | **no** | — |
| `block/BlockDoubleWoodSlabNew.java` | **no** | — |

**Within one family, one half is complete and the other was never vendored at all.** Both
`wooden_slab2` and `double_wooden_slab2` return zero hits in `init/Blocks.java`.

Note the distinction this case exists to prevent: *the file exists* and *the game has it* are
different facts, and here the difference is one slab. An earlier reading of this tree recorded the
wooden classes as "present but unregistered", which the files contradict.

### 3.3 `client/util/` — the input layer lives outside `net/minecraft`

`net/minecraft/client/util/` holds exactly two files:

```
client/src/main/java/net/minecraft/client/util/JsonBlendingMode.java
client/src/main/java/net/minecraft/client/util/JsonException.java
```

There is **no `Keyboard.java` and no `Mouse.java`** anywhere under `net/minecraft`. All input
arrives through `org.lwjgl.input.Keyboard` / `Mouse`, which is **this project's own LWJGL2→3
shim**, not vendored Minecraft: `lwjgl2-shim/src/main/java/org/lwjgl/input/Keyboard.java`
(551 lines) and `Mouse.java` (396 lines), backed by
`lwjgl2-shim/src/main/java/org/lwjgl/impl/glfw/GLFWKeyboardImplementation.java` and
`GLFWMouseImplementation.java`. The shim is 34 `.java` files in total, and its facade javadoc
states the design: key codes are **LWJGL2 DirectInput scancodes**, because that is what 1.8.9
persists in its keybind options file.

23 vendored files import `org.lwjgl.input`.

**So a reader tracking a keystroke has to leave `net/minecraft` entirely.** The hop is:
`KeyBinding` (in-tree) → `org.lwjgl.input.Keyboard` (shim) → `InputImplementation` (shim) →
GLFW (the real library). Nothing under `net/minecraft` will answer the question on its own.

### 3.4 1.7.10-era MCP names — a **rename**, and the cost is to the reader

The tree is nominally 1.8.9 — 267 files reference `IBlockState`, 264 call `getBlockState`, 489
mention `BlockPos`. Alongside them, 1.7-era names are present and the 1.8 names they stand in
for are **absent**:

| file | exists? | stands in for | references |
|---|---|---|---|
| `world/DifficultyInstance.java` | yes | `world/Difficulty.java` (**absent**) | 40 occurrences in 15 files |
| `server/management/PlayerManager.java` | yes | `server/management/PlayerList.java` (**absent**) | 10 files |
| `server/management/ServerConfigurationManager.java` | yes | `PlayerList.java` (**absent**) | — |
| `server/management/ItemInWorldManager.java` | yes | `ItemInWorld` | — |
| `C03PacketPlayer.C04PacketPlayerPosition` | yes | `C06PacketPlayerPosLook` | 5 occurrences in 3 files |

There is also a real `world/EnumDifficulty.java` with `getDifficultyId()` at `:20` and
`getDifficultyEnum(int)` at `:25` — so `Difficulty` as a *concept* is present under two different
names, and the bare word `Difficulty` survives only inside NBT keys
(`world/storage/WorldInfo.java:189`, `:191`, `:360`) and a debug string
(`client/gui/GuiOverlayDebug.java:153`). Do not read those as evidence that the 1.8 class exists.

**This is a cost to the reader and not to the wire, and the wire half is provable:**

```
client/src/main/java/net/minecraft/network/play/client/C03PacketPlayer.java:121
        public void writePacketData(PacketBuffer buf) throws IOException
        {
            buf.writeDouble(this.x);        // :123
            buf.writeDouble(this.y);        // :124
            buf.writeDouble(this.z);        // :125
            super.writePacketData(buf);
        }
```

That is `C04PacketPlayerPosition` (`C03PacketPlayer.java:97`), a **1.7 name**, writing three
doubles — which is the 1.8 shape. And `C06PacketPlayerPosLook` at
`C03PacketPlayer.java:160` writes three doubles then two floats:

```
client/src/main/java/net/minecraft/network/play/client/C03PacketPlayer.java:190
        public void writePacketData(PacketBuffer buf) throws IOException
        {
            buf.writeDouble(this.x);        // :192
            buf.writeDouble(this.y);        // :193
            buf.writeDouble(this.z);        // :194
            buf.writeFloat(this.yaw);       // :195
            buf.writeFloat(this.pitch);     // :196
            super.writePacketData(buf);
        }
```

**Both encodings are 1.8.** The class is correctly encoded under a 1.7 name. The practical cost:
a citation that says "C04PacketPlayerPosition" *reads* as 1.7 and sends the reader to check an
encoding that is not the problem.

### 3.5 `C02PacketUseItem.java` — **correctly absent**, and this is the fourth gap that is not a gap

The file does not exist, and **it should not.** In 1.8.9 the use-item verb is
`C08PacketPlayerBlockPlacement`; there is no separate use-item packet. `C08PacketPlayerBlockPlacement`
exists and has two constructors — `(BlockPos, int, int, float, float, float)` for placement at
`:424` and `(int)` for use-item at `:465`. The use-item one is sent from `sendUseItem`:

```
client/src/main/java/net/minecraft/client/multiplayer/PlayerControllerMP.java:456
    public boolean sendUseItem(EntityPlayer playerIn, World worldIn, ItemStack itemStackIn)
    ...
            this.netClientHandler.addToSendQueue(new C08PacketPlayerBlockPlacement(playerIn.inventory.getCurrentItem()));
                                                        // :465
```

`network/play/client/` holds 23 packet classes and `C02PacketUseItem` is not among them;
`C02PacketUseItem` is a 1.7-era name. **Recorded here so the next reader does not file it as a
fourth gap.**

### 3.6 The consolidated absence list

| absent | cause | can you cite the feature? |
|---|---|---|
| `block/BlockLava.java` | substituted by `BlockStaticLiquid` / `BlockDynamicLiquid` | yes — lava, its flow, and its hazard are all present |
| `block/BlockWoodSlabNew.java`, `BlockDoubleWoodSlabNew.java` | omitted outright | no |
| block ids 198–255 (58 ids) | never registered | no |
| item id 426, and 432–2255 (1825 ids) | never registered | no |
| `client/util/Keyboard.java`, `Mouse.java` | lives in `lwjgl2-shim` | yes — cite the shim |
| `world/Difficulty.java` | renamed to `DifficultyInstance` | yes — cite the new name |
| `server/management/PlayerList.java` | renamed to `PlayerManager` / `ServerConfigurationManager` | yes — cite the new names |
| `network/play/client/C02PacketUseItem.java` | **never existed in 1.8.9** | n/a — use `C08PacketPlayerBlockPlacement` |

---

## 4. Rules for reasoning about this tree

### 4.1 Rules for writing about it

**R1 — A citation is a promise that somebody can check it.** Cite a line you opened. A line
number copied from a previous document, a brief, a stack trace, or a diff hunk header is a
confident wrong answer that is indistinguishable from a right one to every reader except the
person who opens the file.

**R2 — An anchor that cannot be resolved to a file on disk is a defect, not a citation.** If you
cannot open it, cite the symbol instead — `Entity.setOnFireFromLava`,
`FireDamage.LAVA_CONTACT_DAMAGE`, `Block.registerBlocks` — and say plainly that the line was not
verified. A symbol survives a refactor. A stale line number does not.

**R3 — A document that accumulates numbers will accumulate stale numbers. Re-open anchors rather
than adding new ones.** Two sweeps of `docs/agency/guarantees.md` found **46+ wrong anchors**,
essentially all off by a few lines, essentially all written from reading a diff rather than
opening the file. §1g of that document lists 16 of them with was/is pairs. And a later pass caught
three of the integrator's own claims that had **aged into false ones** — the paragraph saying two
javadoc citations named nonexistent classes had become wrong because someone *fixed* the classes,
without anyone touching the paragraph.

That is the whole failure mode: a number is a claim about a file, and the file moves. The only
defence is to re-derive before you publish.

**R4 — "The file exists" and "the game has it" are different facts.** §3.2 is a family where one
half is registered and the other was never vendored. Check registration, not the filesystem.

**R5 — Do not report a substitution as an omission, and do not carry a conclusion forward without
re-opening the file that produced it.** §3.1 is the worked example of both halves: the missing
class is a rename (§3.1), and the *behavioural claim attached to the rename* — that lava does not
flow — was inherited from the audit and was **false** until `BlockStaticLiquid.java:35-40` was
opened. R4 says check registration instead of the filesystem; this says check the mechanism
instead of the conclusion.

**R6 — Absence needs a reason or it is not a finding.** §3.6 is a table of absences because each
one has a reason. A list of files that are not there, with no reason each, is indistinguishable
from a list that was never checked.

**R7 — When re-deriving, prefer a property over a spot check.** "198 registrations, 198 distinct
ids, and the set is exactly `0..197`" is checkable by anyone in one `diff` against `seq 0 197`.
"ids 181 and 182 are present" is a spot check that would not have caught a gap at 200.

### 4.2 Rules about the tree itself

**T1 — The authority for lava damage is `Entity.setOnFireFromLava` (`Entity.java:539-546`), not
`BlockLava.java`, which does not exist.** The block class never applied damage; it carried lava's
own block behaviour (spread, ignite, water interaction), and that behaviour is here too — in
`BlockStaticLiquid` and `BlockLiquid` (§3.1). Damage is applied by the entity, guarded by the
fire-immunity flag. **Lava flows, burns, and interacts with water in this tree.**

**T2 — Damage constants in this project's own code are in half-hearts.** Vanilla deals float HP.
So `FireDamage.LAVA_CONTACT_DAMAGE = 8` (`FireDamage.java:61`) is **correct** against vanilla's
`4.0F` (`Entity.java:543`), and `FireDamage.DAMAGE_PER_TICK = 2` (`:52`) is correct against
`1.0F` at `Entity.java:501`. A reader who compares the two files without this rule will file both
as off-by-2 bugs. They are not.

**T3 — `setOnFireFromLava` is guarded by `if (!this.isImmuneToFire)` (`Entity.java:541`), and that
guard is missing from the method's javadoc.** The javadoc at `Entity.java:536-538` says only
*"Called whenever the entity is walking inside of lava"* and does not mention the immunity check,
so a reader who works from the doc will conclude that a fire-immune entity still takes lava
contact damage. **The javadoc is wrong; the code is right.** Any caller reasoning about
fire-immune mobs (blazes, water mobs, players in fire-resistance) must read the body, not the
comment. (`Entity.isImmuneToFire()` is at `Entity.java:1078-1081`; the field at `:199`.)

**T4 — A 1.7-era class name is not evidence of 1.7 encoding.** §3.4. Check the wire writes, not
the class name.

**T5 — Anything that reads the vendored world must assume air.** §2.1's missing 58 ids arrive from
the server and decode as air in `ExtendedBlockStorage.java:45-48` and `Chunk.java:602`. A cell
the client reads as passable may be solid on the server.

---

## 5. Provenance

Findings reached on the first pass are recorded, with their derivations, in
`.ai-notes/docs/audits/2026-10-02-wave13-vendored.md`. **That file is gitignored.** This document
is what reaches the repository; the audit is what explains how it was found. If the two disagree,
**this file is the one that was re-derived from disk** — and the fix is to re-derive, not to pick
whichever reads better.

Re-deriving turned up four places where the audit was wrong. All four are corrected above, and
all four are recorded here because **the audit is the thing that would otherwise have been
copied**:

| audit said | actually |
|---|---|
| `Entity.java:511` for the `setOnFireFromLava()` call in the `isInLava()` branch | `Entity.java:508-510` |
| "76 package paths" | **97** (`find -printf '%h\n' \| sort -u \| wc -l`) |
| `DifficultyInstance` "(33 references)" | **40 occurrences in 15 files** |
| a `BlockStaticLiquid` "never schedules a fluid update", so **lava does not flow** | it schedules one — `BlockStaticLiquid.java:35-40`, converting the source to `flowing_lava`. **Lava flows.** See §3.1. |

And one is a live wrong anchor in the tree's own documentation, kept as R3's proof:
`FireDamage.java:46` cites `Entity.update:499-503` for the fire-damage block, which is actually
`Entity.java:499-502` (`if (this.fire % 20 == 0)` at 499, the guard at 500,
`attackEntityFrom(DamageSource.onFire, 1.0F)` at 501, the closer at 502). Off by one at the end,
in a file whose own §6.10 exists because of exactly this.