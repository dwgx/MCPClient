# SurvivalDamage production wiring -- design + evidence (2026-10-03)
(written by a subagent. NO production file was created: the write tool is xd://-only and all 8 smartcli sessions belong to other workers. This is the design report only; acceptance items 2 and 3 are NOT met.)
## Q3 (the key question): is damage an edge or a level on a live client?
ANSWER: **a level, delivered by a packet.** No client-side damage arithmetic runs at all.
- EntityLivingBase.attackEntityFrom:868-871 -> else if (worldObj.isRemote) { return false; }. EVERY damage call on a client is a no-op.
- Entity.onEntityUpdate:483-484 -> if (worldObj.isRemote) { this.fire = 0; }. The burn COUNTER is structurally unreachable client-side: the fire % 20 tick sits in the !isRemote branch. Same for lava contact (setOnFireFromLava:539-546 calls attackEntityFrom), drown (ELB:315), and fall (ELB.updateFallState:233 is !worldObj.isRemote &&).
- EntityPlayerSP.heal:148-150 is an EMPTY BODY, so FoodStats.onUpdate:66 regen does nothing on a client.
- So the only authority is the server, arriving as an ABSOLUTE LEVEL: NetHandlerPlayClient.handleUpdateHealth:1042-1048 -> setPlayerSPHealth(packetIn.getHealth()).
- The level becomes an edge exactly once, at EntityPlayerSP:346-375: it differences (:350 f = getHealth() - health) and on a drop charges the difference and reopens the window (:363-367).
- **1.8.9 sends NO damage-cause packet.** Verified by listing every handle*(S..) in NetHandlerPlayClient: there is no handleEntityDamage, and no S19PacketEntityDamage class exists. Grep for S19PacketEntityDamage|handleEntityDamage across the repo returns zero hits.
=> The wire carries the RESULT, never the CAUSE.

## Q2: per-tick vs lazy?
Neither, as posed. The design is **packet-driven**, with the tick as a floor.
- (a) per-tick getHealth() sampling is WRONG, not merely costly. The bar is piecewise-constant between health packets, so a dip the server applies and heals inside one tick is invisible. Regen is a client-side no-op, so a healed-back bar reads as never moved -- a FALSE PASS on the exact criterion. EntityLivingBase:1285-1291 subtracts absorption BEFORE health, so absorbed damage never moves the bar at all.
- (b) lazy / ring-buffer needs history and re-introduces the segment you could not read -- the failure this project keeps hitting.
- Chosen: take the **minimum over the S06 level stream**. This needs no history (a min is monotone), so the cost of (b) disappears without its benefit.
The server sends S06 only when the level CHANGED -- EntityPlayerMP:402-407 guards on getHealth() != lastHealth || food changed || hunger flag flipped. So every real health transition produces exactly one packet, and the min over the stream is EXACT. This is the load-bearing fact that makes a packet-driven instrument sound.
## Consequence for attribution (the honest gap)
SurvivalDamage.Source per-cause attribution is UNAVAILABLE on a live client, and no client-side read fixes it. Every existing constant is a claim about state the client does not have: LAVA and FIRE need the server fire counter (client zeroes it at Entity:484); DROWN needs the server air bar (EntityLivingBase:315 is inside the !isRemote branch); FALL needs a landing only the server resolves (EntityLivingBase:233); MOB needs an attacker the level stream never names.
So the live instrument must report a single source, SERVER, and say so in its own output. Inventing a cause would be exactly the lie this slice exists to remove.

## Wiring facts verified (each one would have broken a draft)
- EventBus.unsubscribe(Consumer<T>) takes ONE arg, not (Class, handler).
- SurvivalDamage has NO reset() method -- a driver cannot reset one. A live driver must keep its own run state.
- SurvivalDamage.Source is a fixed enum consumed by ledger() and anyHazard(); adding SERVER to it would change fixture output. A separate enum in the driver is the safe move.
- **The Netty tap is OPT-IN.** installNettyTap is reachable only via the KERNEL tool seam_netty_install (SeamTools:117). McpCore never auto-installs it. So a packet-driven instrument reads NOT MEASURED until a model installs the tap -- the same requires-seam_netty_install caveat ChatTools and ObserveTools already carry. This is a real deployment caveat, not a detail.
- HighValueSummarizers.Health.project puts hp (putRounded scale 2) into the snapshot fields map. Read fields, NOT the summary string -- parsing hp=17.50 back out of a summary is reading a number off a screenshot.
- EntityPlayerSP.damageEntity:319-325 OVERRIDES the base and skips absorption, armour and potions. So even the local edge is not vanilla arithmetic.
## Status against the assignment
MET: item 1 (the design judgment, with file:line evidence).
NOT MET: item 2 (no guard test exists; I could not create one, and I will not report a red/green pair I did not run).
NOT MET: item 3 (no authoritative suite run; the four numbers are unmeasured by me).
MET: item 4 (the honest boundary, below).
## The honest boundary
After this wiring, on a real client the north-star criterion 2 can answer: did the bar ever go below 18 over the run, when, how many drops, how much lost, how much healed back. That is a genuine improvement over the fixture, which only proves the fixture accounts correctly.
It still CANNOT answer: which hazard took the health. That is a permanent gap on a multiplayer client in 1.8.9, not an unfinished feature -- the protocol does not carry a cause. A client-side substitute would be a guess wearing a vanilla citation.
It also CANNOT answer anything at all until the packet tap is installed, because the instrument reads the S06 stream.
