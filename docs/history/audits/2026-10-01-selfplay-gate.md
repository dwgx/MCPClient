# Self-play eval harness: fidelity audit

Scope: `core/src/test/java/net/marcloud/mcp/core/eval/` -- EvalHarness, EvalSuite, GoalPolicy,
SimWorld, SimBody, SimCraftWindow, SimMob, SelfPlayEval, SelfPlayEvalTest. Read-only.
Line numbers are from the tree as of this pass; the parent is mid-edit on `SimWorld.canSee`
and `SimWorld.damagePlayer`, and both are cited as they now stand.

---

## 1. What the harness can actually score today

23 tasks, `EvalSuite.all()` at `EvalSuite.java:72-97`.

| id | class:line | world fact it asserts |
|---|---|---|
| T01 | `:137-161` | posZ decreased by > 4 blocks, posY == 64.0, onGround() |
| T02 | `:167-200` | horizontal distance to (0.5,-14.5) <= 1.0, on ground, y==64, MOVE==COMPLETE |
| T03 | `:204-249` | the same, from all 8 yaws, zero failures |
| T04 | `:253-298` | inputTrace() neutral on the arrival tick, non-zero on effectiveTick |
| T05 | `:301-323` | MOVE==FAILED **and** posZ() > -8.0 |
| T06 | `:338-377` | the **plan**, not the world: plan.found() and >= 1 Move.Kind.STEP_UP |
| T07 | `:381-430` | cell (0,64,-3) is air, count(cobblestone)==1, count(stone)==0 |
| T08 | `:434-513` | inputTrace()[i][2] != 0 on some tick, and stance == the plan's last move destination |
| T09 | `:518-601` | >=1 BRIDGE move, posX > 7.5, dist<=1.0, on ground y==64, >=1 cobblestone block in the trench |
| T10 | `:606-750` | (a) feet at y in (61.9,64.0), grounded, dist<=1.0, MOVE==COMPLETE; (b) record(MOVE).hazard().kind()==WATER with blocksAhead() > 0, then after cancel posZ()<10, feet cell not water, health>0 |
| T11 | `:753-838` | client and server copies of the dest slot both hold 5 dirt, source empty on both sides, w.count(dirt)==5, cursor null |
| T12 | `:843-885` | count(crafting_table)==1, count(planks)==0, matrix (clear), null cursor, resultClicks()==1 |
| T13 | `:889-929` | count(furnace)==1, count(cobblestone)==0, clear matrix, null cursor, resultClicks()==1 |
| T14 | `:932-955` | dist to (0.5,-20.5) <= 1.0, health > 0 && health == 3.0, on ground y==64 |
| T15 | `:969-1044` | inside: mob health fell and entityHits>=1 after walking to (0.5,-1.5). outside: mob at (0.5,-14.5), entityHits==0 && entityHealth==20.0 |
| T16 | `:1076-1146` | (a) health > 10.0, count(bread)==1, INTERACT==COMPLETE; (b) after guiOpened(), count(bread)==2 && health==10.0 |
| T17 | `:1176-1271` | two runs against a refusing server: count(crafting_table)==0, out.terminal(), !out.ok() |
| T18 | `:1287-1360` | ore cell is air, count(iron_ore)>0, health>0, policy returned true |
| T19 | `:1385-1455` | gold cell is air, count(gold_ore)>0, health>0, policy true |
| T20 | `:1472-1571` | stone gone, count(cobblestone)>0, health>0, **zero ticks with the feet in a lava cell**, and lineCellsHolding(lava) > 0 as a self-check |
| T21 | `:1578-1676` | count(cobblestone)>0, health>0, stoneSpent <= held, logsSpent > 0 |
| T22 | `:1695-1762` | count(ladder)>0, exactly 1 crafting_table **standing as a block**, 0 crafting_table items left, health>0 |
| T23 | `:1788-1821` | count(cobblestone)>0, health>0, with all 36 slots pre-filled |

### Is "survive one night" among them?

**No.** Nothing in the eval package models time of day. There is no worldTime, no isDaytime, no
light level anywhere under core/src/test/java/net/marcloud/mcp/core/eval/ -- grep for
`worldTime|dayTime|24000|isDaytime|lightLevel|sunrise|sunset` returns nothing. The longest run in the
suite is 900 ticks (T09, `:551`); a night is 12,000 ticks. The world is permanently full daylight and
permanently empty of anything that spawns.

### Is "a full round" among them?

**No, and the suite says so itself.** T18's javadoc (`:1188-1218`) enumerates what it cannot show:
no threat so no decision, no light so no caves and no night, no network so no other player, no
furnace, and "it is a policy, not a model". `GoalPolicy:26-34` repeats it. No task spans more than
one goal item; T18-T23 each end at "the player holds X".

---

## 2. Where the harness lies

Ordered by how likely each is to produce a false green. Each row is paired: harness line, then the
client line it claims to transcribe.

### 2.1 `alive` is a constant in six tasks (the clearest false green)

health is mutated in exactly three places in SimWorld: atHealth (SimWorld.java:362), damagePlayer
(SimWorld.java:553) and food (SimWorld.java:1308). damagePlayer is only ever called from
SimMob.tick (SimMob.java:291). **No EvalSuite task calls spawnMob()** -- grep confirms spawnMob
appears only at SimWorld.java:478 and in AMobThatActsIsMeasuredInTheWorldTest. So in T18-T23 the
player cannot be damaged by anything, and these are all `20.0 > 0.0`:

- EvalSuite.java:1325 (T18) `boolean alive = w.health() > 0.0D;`
- EvalSuite.java:1413 (T19) same
- EvalSuite.java:1505 (T20) same -- **in a world that contains a lava band**
- EvalSuite.java:1609 (T21), :1726 (T22), :1811 (T23) same

T20 is the sharpest: the pass condition already carries the real check (inLava == 0, `:1513`), so
`alive` is decoration dressed as a second opinion.

T14's EvalSuite.java:946 -- `w.health() > 0.0D && w.health() == 3.0D` -- compares health against the
literal the task itself wrote into the world with atHealth(3.0D) at `:940`. It asserts that nothing
happened, not that the agent survived anything.

### 2.2 air() is a hardcoded 300 -- drowning cannot happen

SimWorld.java:214 declares `private int submergedTicks;` and **nothing ever assigns it**. The only
read is SimWorld.java:1405-1407, which is therefore always 300. Vanilla decrements air every
submerged tick and applies 2 damage at -20: EntityLivingBase.java:301-317. The javadoc at
SimWorld.java:1400-1404 says "-1 is never returned, because this world always knows the answer --
there is no such thing as an unread cell here". The real reason is that the counter is never run.
This is the shape the previous session's signature failure took: a model that returns a constant,
with a comment explaining a design choice the code does not make. It also makes T10's fatal-water
half (EvalSuite.java:723 `alive = w.health() > 0.0D`) unfalsifiable.

### 2.3 No fall damage, anywhere

SimBody maintains fallDistance exactly as vanilla does (SimBody.java:185-189, transcribed from
Entity.updateFallState:1034-1055) and SimWorld.fallDistance() exposes it (SimWorld.java:1367-1370)
for the crit window. Nothing ever charges it. Vanilla charges
`MathHelper.ceiling_float_int(this.fallDistance - 3.0F)` at EntityLivingBase.java:237 and :1156. A
30-block drop costs zero health in this world. T10's survivable-drop half (`:645-680`) asserts only
posY in a range, onGround, distance and phase -- none of which can distinguish a 2-block step from
a 30-block cliff, because neither costs anything. Undeclared.

### 2.4 inWater() reads the eye cell; vanilla reads the body box

SimWorld.java:1394-1397 samples the cell at y + EYE_HEIGHT. Vanilla Entity.handleWaterMovement
(Entity.java:1113-1127) tests
`getEntityBoundingBox().expand(0.0D, -0.4000000059604645D, 0.0D).contract(0.001,...)` -- the body from
the feet up, shrunk, not the eye. A player standing chest-deep in water reads **not** in water here.
That term gates the crit window (CritWindow.java:68-71) and would gate any swim. Combined with 2.2,
no water behaviour is reachable at all.

### 2.5 Bread heals health directly; vanilla fills the hunger bar

SimWorld.java:1306-1309 does `health = Math.min(20.0D, health + food.getHealAmount(finished))`.
Vanilla ItemFood.onItemUseFinish (ItemFood.java:58-61) calls `playerIn.getFoodStats().addStats(...)`,
which raises **food level and saturation** (FoodStats.java:27-36); health rises only through
regeneration, 1 point per 4 ticks, and only while foodLevel >= 18 (FoodStats.java:60-69). Bread is
`new ItemFood(5, 0.6F, false)` (Item.java:806), so vanilla bread at a full bar heals **nothing**: it
converts to food plus saturation. T16's comment at `:1103-1106` claims the heal is "the ITEM's own
number, read from the registered ItemFood" -- true, and the measured 10.0 -> 15.0 at `:1106` matches
the code -- but the mechanism is not vanilla's. There is no foodLevel field anywhere in the eval
package, so hunger, exhaustion, regeneration and starvation are all absent and undeclared.

### 2.6 The mob-AI path is never exercised by the suite

SimMob (354 lines, a careful transcription) is reachable only through SimWorld.spawnMob()
(`:478-486`). No EvalSuite task calls it. T15 -- the suite's only combat task -- uses
`w.spawn("zombie", ...)` (EvalSuite.java:987, :1022), which returns a SimEntity that never moves
(SimWorld.java:441-444). So T15 measures a static target against EntityCombat.serverAccepts, and
the whole transcription is covered only by AMobThatActsIsMeasuredInTheWorldTest, which is not part
of the gate.

### 2.7 A mob chases forever

SimMob.acquire sets hasPath = true (SimMob.java:342) and **nothing ever sets it false**; the gate at
SimMob.java:240 only ever returns early when it is false. Vanilla
EntityAIAttackOnCollide.continueExecuting (`:86-91`) returns false once the target dies or, for
longMemory == false, once the navigator has no path, and resetTask (`:100-104`) clears the path
entity. So here a zombie that has noticed the player walks toward a stale point at any distance,
forever, outside its own follow range. Undeclared.

### 2.8 Acquisition ignores the sneaking reduction

SimMob.canNotice (SimMob.java:209-216) uses eye-to-eye Y within 4.0 and horizontal distance within
followRange. Vanilla expands the mob's own bounding box by (d0, 4.0D, d0) and takes the entities in it
(EntityAINearestAttackableTarget.java:105), and its target selector multiplies d0 by **0.8** when the
player is sneaking (EntityAINearestAttackableTarget.java:52-54). Sneaking *is* modelled here
(SimWorld.sneakingThisTick, SimWorld.java:637), so a sneaking player is over-noticed by 25% of the
follow range. Undeclared.

### 2.9 The mob's walk is a constant forward axis, not a bearing

SimMob.java:281-284 walks with
`body.travel(cells, 0.0D, (double) SimWorld.INPUT_DAMP, kind.movementSpeed())` -- the forward axis is
the literal 0.98 every tick. Vanilla's tryMoveToEntityLiving steers. The walk gate
`dx*dx + dz*dz > ARRIVED*ARRIVED` at `:281` measures against the **stale** path point captured at the
last refresh, and yaw turns only 30 degrees a tick (`:250-254`, EntityLookHelper.updateRotation:108-123).
Combined: a mob that has been flanked can walk away from the player indefinitely. KNOWN_GAPS names
"no PathNavigate" but not this.

### 2.10 The invulnerability window is twice vanilla's

SimWorld.java:543-547 refuses a hit while `ticks - hurtResistantTime < MAX_HURT_RESISTANT_TIME` (20).
Vanilla gates on `this.hurtResistantTime > this.maxHurtResistantTime / 2.0F`
(EntityLivingBase.java:896), and because hurtResistantTime counts **down** from 20 (`:910` set,
`:342-345` decremented), the effective window is **10 ticks**, not 20. So two zombies swapping
swings halve each other half as often here as in the game. SimMob.java:52-56 states the rule as a
20-tick window. This one is wrong in the substrate's favour: the player takes *less* damage than
vanilla.

### 2.11 Reach is 0.5 blocks too generous, and the comment is wrong

SimWorld.java:926-930 returns 5.0D with the comment "LivePlayerActuator: a survival player's block
reach. 5.0 is vanilla's constant." PlayerControllerMP.getBlockReachDistance()
(PlayerControllerMP.java:344-347) returns `isCreative() ? 5.0F : 4.5F`, and
LivePlayerActuator.reachDistance() (LivePlayerActuator.java:67-70) returns exactly that, i.e. **4.5**
in survival. The comment names the creative number and calls it vanilla's constant. This widens
mouseOver()'s ray (SimWorld.java:943), InteractController's attack gate (InteractController.java:159),
and SimWorld.attackEntity's bound (`:1325`).

### 2.12 The attack bound is eye-to-eye and has no line-of-sight term

SimWorld.attackEntity (`:1319-1327`) computes eye-to-eye distance and refuses beyond
reachDistance() + 1.0 = 6.0. Vanilla's server gate is `playerEntity.canEntityBeSeen(entity)` then
`getDistanceSqToEntity(entity) < (canSee ? 36.0 : 9.0)` (NetHandlerPlayServer.java:907-917), where
getDistanceSqToEntity is **feet** to feet (Entity.java:1360-1367). Three divergences: eye vs feet,
no canSee term at all (the substrate has canSee at SimWorld.java:502 and never calls it from the
attack path), and the blind bound 9.0 is unreachable.

T15 hardcodes the LOS answer: `EntityCombat.serverAccepts(distSq, true)` at EvalSuite.java:1000 and
:1029. The canSee=false branch of vanilla's gate is never exercised anywhere in the eval.

### 2.13 Sword damage omits the base attribute; every non-sword scores 1.0

SimWorld.java:1336-1346: bare hand 1.0, ItemSword 4.0 + getDamageVsEntity(), everything else 1.0.
Vanilla: the player's base attackDamage attribute is 1.0 (EntityPlayer.java:192) and the item's modifier
is **added on top** -- ItemSword.java:26,141 sets `4.0F + material.getDamageVsEntity()`,
ItemTool.java:31,103 sets `attackDamage + material.getDamageVsEntity()`, and ItemAxe.java:20 passes
3.0. So an iron sword is 1.0 + 4.0 + 2.0 = 7.0 in vanilla and 6.0 here; a stone axe is
1.0 + 3.0 + 1.0 = 5.0 in vanilla and **1.0** here. T15 asserts only `after < before` (`:1005`), so
both pass.

### 2.14 T05's wall bound is one block too loose, and the comment states the wrong face

EvalSuite.java:310 builds the wall with `w.box(-4, 64, -8, 4, 66, -8, "stone")`. SimWorld.box
(SimWorld.java:291-300) loops bz from z0 to z1 inclusive, so this is a single cell layer at bz == -8,
occupying world z in [-8.0, -7.0). The face nearest the player at z = 0.5 is therefore **z = -7.0**.
The comment at EvalSuite.java:315 says "The wall's near face is z = -8.0" and the assertion at `:317`
is `w.posZ() > -8.0D`. A body whose centre reached z = -7.5 is entirely inside the wall cell and
still passes. The bound is one full cell too permissive.

### 2.15 SelfPlayEvalTest has no task-count floor

SelfPlayEvalTest.java:29-42 asserts `failures.length() == 0`. It never asserts how many tasks ran.
SelfPlayEval.runAll (SelfPlayEval.java:38-56) returns whatever EvalSuite.all() returns. Delete or
accidentally exclude a task and the gate goes **quieter and still green**. There is no
assertEquals(23, scored.size()) anywhere.

### 2.16 Smaller ones, one line each

- **T06 scores the plan, not the world** (EvalSuite.java:352-354), against the class-level claim at
  `:28` that "Every verdict below is a world fact". Declared locally at `:328-337`, not in the header.
- **T23's freeBefore measures the wrong moment.** fillInventory (EvalSuite.java:1857-1862) computes
  freeSlots(w) *before* filling, on a fresh world, so it is always 36. The fact line prints
  "free inventory slots 36 -> 0 of 36" as though the run started full.
- **Dig has no blockHitDelay.** PlayerControllerMP.java:328 sets blockHitDelay = 5 after a break and
  `:246-283` refuses to accumulate while it is set. SimWorld.pumpDig (`:990-1016`) accumulates
  unconditionally.
- **plain() floors at y-1** (SimWorld.java:280-289), which the suite helper comment at
  EvalSuite.java:110-116 correctly documents.
- **No ladder/vine physics.** SimWorld.onClimbable()/isClimbable() (SimWorld.java:1367-1370,
  `:1521-1525`) answer the seam, but SimBody has no ladder branch, so the 0.15 motion clamp, the
  fallDistance = 0 and the motionY = 0.2 on horizontal collision (EntityLivingBase.java:1639-1658)
  are all absent. A ladder is climbable and also not solid, so a body walks through it.
- **solid() requires isFullCube()** (SimWorld.java:763-764), so ladders, fences, rails, glass and
  slabs are not solid, where vanilla collision uses the bounding box. Consequence: canSee
  (`:502-518`) sees through glass, which vanilla's rayTraceBlocks does not.

---

## 3. SimWorld.KNOWN_GAPS against reality

KNOWN_GAPS is at SimWorld.java:102-155, nine entries. The parent has already rewritten entry 1 to
admit mob AI, and added entries for the discretised sight ray (`:114-116`) and for "nothing kills an
acting mob" (`:117-121`). Those three are accurate.

### Stale

| declaration | reality |
|---|---|
| SimWorld.java:46 -- class javadoc: "it has no lighting, **no mob AI**, and no network" | contradicted by KNOWN_GAPS:103-113 and by SimMob.java existing at 354 lines. The class header is the one most readers see. |
| SimMob.java:164-166 -- "Declared in SimWorld#KNOWN_GAPS" (about PATH_REFRESH_TICKS = 4 + 7 being the deterministic stand-in for vanilla's 4 + rand(7) at EntityAIAttackOnCollide.java:129) | KNOWN_GAPS says nothing about the path-refresh rate, the tick-count stand-in for nextInt(targetChance), or the ARRIVED = 1/3 constant at SimMob.java:296-307. Three approximations, zero declarations. |
| SimMob.java:28-29 -- "the substitution is named in SimWorld#KNOWN_GAPS" (the ticks % 10 stand-in for nextInt(targetChance), EntityAINearestAttackableTarget.java:100) | same -- undeclared. |
| SimMob.java:65-68 -- "What is deliberately NOT here is in SimWorld#KNOWN_GAPS" | the list names wander/home range/despawn/sunlight/loot/armour/ranged AI, which is fair, but the things that actually make a mob behave wrongly here (2.7, 2.8, 2.9) are not among them. |

### Undeclared -- worse than stale, because an undeclared approximation is a silent lie

1. **No fall damage.** SimBody.java:185-189 keeps fallDistance; nothing charges ceil(distance - 3)
   (EntityLivingBase.java:237). A 30-block drop is free.
2. **air() is constant 300.** SimWorld.java:214, 1405-1407. No drowning
   (EntityLivingBase.java:301-317).
3. **inWater() is the eye cell**, not the body box. SimWorld.java:1395-1397 vs Entity.java:1113-1127.
4. **No hunger, saturation, exhaustion, regeneration or starvation.** Food heals health directly.
   SimWorld.java:1306-1309 vs ItemFood.java:58-61 + FoodStats.java:27-69.
5. **No lava damage and no fire.** solid() (`:762-764`) excludes liquids, so a body walks through
   lava at full speed taking nothing. Vanilla EntityLivingBase.java:1607-1610, 2013-2017.
6. **No day/night clock.** "no lighting" is declared; "no clock" is not, and they are different gaps.
7. **No water movement.** SimBody.travel (`:337-383`) has no handleWaterMovement branch, so no swim
   drag, no buoyancy, and fallDistance is not zeroed in water (Entity.java:1120).
8. **A mob never gives up** (2.7).
9. **Acquisition ignores sneak** (2.8).
10. **The mob's forward axis is a constant** (2.9).
11. **The invulnerability window is 20 where vanilla's effective is 10** (2.10).
12. **reachDistance() is 5.0 where the live actuator returns 4.5 in survival** (2.11).
13. **The attack bound is eye-to-eye with no LOS term** (2.12).
14. **Sword damage omits the base attribute; non-sword tools score 1.0** (2.13).
15. **No ladder/vine physics** (2.16).
16. **solid() requires isFullCube(),** so partial blocks are not solid and do not occlude (2.16).

### Correctly declared, worth keeping

"sprint does not change local speed" (`:122-123`) is right for 1.8.9. setSprinting
(EntityLivingBase.java:1467-1481) applies a +0.30 modifier at operation 2 to the movementSpeed
attribute, and EntityPlayer.getAIMoveSpeed() (`:1815-1817`) reads that attribute back -- but
moveEntityWithHeading is guarded by isServerWorld() (EntityLivingBase.java:1604-1607), so the client
never applies it. The reasoning in the gap entry holds.

"dig duration" / "dig drops" (`:124-128`) and the RoutePlanning substitution (`:129-136`) are all
accurate: Block.getPlayerRelativeBlockHardness really is toolEff / hardness / 30 harvestable and
/ 100 otherwise (Block.java:590-593), and getDrops really does need a World, a BlockPos and a
LootContext.

---

## 4. Cheapest honest next capability: survive the night

### The minimum that makes the word "night" mean anything

**A world clock.** One field, one method, transcribed from two sources this repo already has:

- worldTime advanced one per tick from SimWorld.ticks(), exposed as `long worldTime()`.
- isDaytime() delegating to EnvironmentWeather.isDaytime(skylightSubtracted), which already exists at
  core/src/main/java/net/marcloud/mcp/core/drivers/world/EnvironmentWeather.java:33-36 and already
  carries the warning that this is "a threshold on the daylight factor rather than on the clock".
- the time-of-day bucket reused verbatim from WorldViewCapture.timeBucket (`:522-529`), including the
  negative-time normalisation it documents. Two copies of that bucketing is the defect this repo has
  already paid for once.

Cost: about 15 lines. Nothing can honestly be called "night" until this exists.

### The minimum that makes "survive" falsifiable

**Night spawning, off the clock.** Once the clock exists, a maybeSpawnNightMob() that consults it:

- when !isDaytime(), spawn a zombie at a stated offset from the player using the numbers already
  transcribed at SimMob.java:126-131 (followRange 35.0 from EntityZombie.java:96, movementSpeed 0.23
  from :97, attackDamage 3.0 from :98).
- route the spawn position through the existing production floor-and-room gate
  BlockInspector.Spawn (drivers/world/BlockInspector.java:57-95), with the light term replaced by the
  clock. **That substitution must be declared as a new KNOWN_GAPS entry**, in the same shape as the
  existing ones: "spawns are clock-gated, not light-gated, because the light store is not modelled; a
  thunderstorm's effect on the gate is therefore absent" -- the last clause is the omission
  BlockInspector.java:190-194 warns about (a storm forces skylightSubtracted to 10 and turns midday
  into night).

That declaration is the whole difference between an honest capability and a fake one. Without it, a
task called "survive the night" that spawns on the clock while claiming to be about darkness is
exactly the instrument that reports success while being wrong.

### What that capability must model so it is not a fake

1. **health must be reachable.** Today `alive` is constant in six tasks (2.1). A night task makes it
   real the moment a mob spawns, which is the entire value of doing this first.
2. **Fall damage, or an explicit declaration that there is none.** The cheapest survival play in
   Minecraft is a tower, and this world's towers are free (2.3). An agent that builds a 30-block pillar
   and stands on it will "survive" here and die live. Either charge FallDamage.damageFor, which
   already exists and is already tested at drivers/world/FallDamage.java:62, or write the gap down.
3. **Hunger, or a declaration.** 12,000 ticks of walking and digging costs exhaustion in the game and
   nothing here (2.5). If the night task is scored without it, say so in the task's own javadoc the
   way T18 does at `:1195-1218`.
4. **A real endpoint, read at dawn.** The verdict must be health > 0 at the tick isDaytime() first
   returns true, plus `w.spawnedNightMobs() > 0` as the self-check in the shape T20 already uses at
   `:1513` (lineBlocked > 0). A task that passes without ever having faced a mob proved nothing, and
   the suite already owns that self-check idiom.
5. **Determinism.** SimWorld.ticks() is the clock, so a night is reachable by tick count. Keep the
   existing rule that no task asserts a tick *count* -- only a state at the dawn boundary -- from
   EvalSuite.java:43-46.

### What it must NOT do

Do not build a lighting engine to make this work. Full sky-light propagation is a large piece of work
whose only consumer would be one task, and the honest version of "it is dark" in this substrate is
!isDaytime(). If the Owner wants light-gated spawning, that is a separate capability with its own
KNOWN_GAPS entry about thunder.

### Also cheap, worth doing in the same pass

- assertEquals(23, scored.size()) in SelfPlayEvalTest (2.15).
- Fix T05's bound to `w.posZ() > -7.0D` and its comment (2.14).
- Fix reachDistance() to 4.5 and correct the comment (2.11).
- Either move one of the T18-T23 `alive` assertions into a task that spawns a mob, or delete it (2.1).
  A check that cannot fail is worse than no check, because it reads as a second opinion.
