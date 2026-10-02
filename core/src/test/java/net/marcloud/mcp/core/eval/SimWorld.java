package net.marcloud.mcp.core.eval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.marcloud.mcp.core.drivers.act.ActActuator;
import net.marcloud.mcp.core.drivers.plan.BlockView;
import net.marcloud.mcp.core.drivers.plan.Stance;
import net.marcloud.mcp.core.drivers.world.Daylight;
import net.marcloud.mcp.core.util.Graded;
import net.minecraft.block.Block;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovementInput;
import net.minecraft.util.Vec3;

/**
 * A deterministic headless Minecraft 1.8.9 world, driven through the production
 * {@link ActActuator} seam and readable as facts.
 *
 * <p><b>What this is for.</b> {@code _scratch/smoke/first_night.py} answers "can a scripted
 * player reach dawn in one world", which is not the Owner's question. The question is whether the
 * AGENT can do the task, and that is a property of the controllers: given the same world, does
 * {@code NavController} actually walk the player there, does {@code DigController} actually break
 * the block, does {@code CraftController} actually spend the ingredients. Those controllers are
 * already client-free by design, so the whole question is reachable with no game running -- which
 * is what makes it a gate rather than an anecdote.
 *
 * <p><b>Why the physics is transcribed rather than simplified.</b> A simulator that teleports the
 * player to the target passes every navigation task no matter how broken the steering is. Every
 * constant below is vanilla's own, read out of the frozen {@code client/} source, and each says
 * where it came from. If a change to {@code NavController} points the axes the wrong way, the
 * player walks the wrong way here too and the task fails -- which is the entire point.
 *
 * <p><b>Blocks are real {@code Block} objects</b>, resolved from the registry after
 * {@code Bootstrap.register()}. So hardness, slipperiness, full-cube-ness and material are
 * vanilla's, not a table someone typed. A dig takes exactly as long as the block's real hardness
 * says, on the real {@code /30} (or {@code /100} unharvestable) curve.
 *
 * <p><b>What this is NOT.</b> Not a client, not a server, and not a claim about a live session.
 * The three places it deliberately differs from a real client are named in the constants and in
 * {@link #KNOWN_GAPS}: it has no lighting, no mob AI, and no network. Nothing here should be read
 * as evidence about those.
 *
 * <p><b>Threading:</b> single-threaded by construction, like the game thread it stands in for.
 */
public final class SimWorld implements ActActuator, BlockView {

    // ===== vanilla constants, each with its source line =====

    /** {@code EntityLivingBase:1610} — air friction. */
    public static final double FRICTION_AIR = 0.91D;
    /** {@code EntityLivingBase:1617} — the acceleration divisor. */
    public static final double ACCEL = 0.16277136D;
    /** {@code EntityPlayer:193} — the movementSpeed attribute's base value. */
    public static final double WALK_SPEED = 0.1D;
    /** {@code EntityLivingBase:1626} — airborne acceleration (jumpMovementFactor). */
    public static final double AIR_ACCEL = 0.02D;
    /** {@code EntityLivingBase:2031-2032} — the 0.98 applied to both input axes. */
    public static final float INPUT_DAMP = 0.98F;
    /** {@code EntityLivingBase:1677} — gravity. */
    public static final double GRAVITY = 0.08D;
    /** {@code EntityLivingBase:1680} — vertical damping. */
    public static final double Y_DAMP = 0.98D;
    /** {@code EntityLivingBase:1561} — jump impulse. */
    public static final double JUMP = 0.42D;
    /** {@code EntityLivingBase:2021} — ticks before another jump is allowed. */
    public static final int JUMP_COOLDOWN_TICKS = 10;
    /** {@code EntityLivingBase:208} — step height. */
    public static final double STEP_HEIGHT = 0.6D;
    /**
     * {@code Entity.update:514-517} -- {@code if (this.posY < -64.0D) this.kill();}. The one
     * hazard the game resolves with no {@code DamageSource} and no amount, and the floor of the
     * world: a body that walks off the edge of a build is dead, not falling.
     */
    public static final double VOID_Y = -64.0D;
    /** {@code EntityPlayer:580} — body width. */
    public static final double WIDTH = 0.6D;
    /** {@code EntityPlayer:580} — body height. */
    public static final double HEIGHT = 1.8D;
    /** {@code EntityPlayer:2328} — eye height. */
    public static final double EYE_HEIGHT = 1.62D;

    /**
     * The places this simulator is knowingly not a client. Read before quoting any result as
     * evidence about a live session.
     *
     * <ul>
     *   <li><b>Lighting and mob AI.</b> There is none. Light level cannot gate a task here, and a
     *       hostile mob does not path toward the player on its own; where a task needs a threat it
     *       spawns one at a stated position and says so.</li>
     *   <li><b>Sprint speed.</b> 1.8.9 applies the sprint multiplier on the SERVER, off the
     *       client's prediction path — there is no sprint constant anywhere in the client's
     *       movement code. So sprint is recorded as a flag and deliberately does not change local
     *       speed. This cannot affect any task: {@code MoveApplier} publishes
     *       {@code sprint=false} for every navigation intent (its own comment, "Sprint stays
     *       off"), so the flag is only ever observed on a raw {@code MoveIntent} that asks for
     *       it.</li>
     *   <li><b>Network.</b> None. Container agreement here is "the client's prediction and the
     *       server's copy moved together", which is the property a locked window breaks; the
     *       packet round trip itself is the live-only part.</li>
     * </ul>
     */
    public static final List<String> KNOWN_GAPS = List.of(
            "no lighting and no network. Mob AI EXISTS for exactly two types (zombie, spider -- see"
                    + " SimMob.kinds()) and only when a task spawns one through spawnMob():"
                    + " spawn() still returns a target that stands still, so every task that predates"
                    + " the mob line keeps its old meaning. What the mob line does NOT model, and"
                    + " says so rather than approximating: PathNavigate (a mob walks straight at its"
                    + " target, so a wall is a wall), wander, home range, despawn, sunlight burning,"
                    + " loot, ranged AI, and the server-side half of an exchange -- damage reduction,"
                    + " armour absorption and difficulty scaling are not transcribed at all, so a"
                    + " zombie lands its flat attackDamage attribute and a fact measured here is"
                    + " 'the substrate's model of the server decided it', never 'the game confirmed"
                    + " it'",
            "sight is a 0.2-block walk of the eye-to-eye ray rather than a traced one: no cube can be"
                    + " thinner than 1.0 so nothing solid is skipped, but partial blocks, glass and"
                    + " water do not occlude the way the block grid would need them to",
            "the CLOCK exists and the LIGHTING does not, and the two are not the same gap. worldTime"
                    + " advances one tick per tick (WorldServer.java:206-209) and isDaytime/isNight are"
                    + " computed from it through the production Daylight helper, so a task can say"
                    + " WHEN it is and can assert that a night happened. What is still absent is"
                    + " everything the night would CAUSE: no hostile mob spawns in the dark, no"
                    + " zombie burns at dawn, no light level gates a cell, and a torch changes"
                    + " nothing. So 'it got dark' is assertable here and 'it was dangerous' is not,"
                    + " and no task may quote a row below as evidence about surviving a night -- it"
                    + " is evidence about the controllers composing, measured under a clock that"
                    + " ran past midnight",
            "nothing in this substrate KILLS an acting mob: attackEntity(int) works on the"
                    + " SimEntity targets spawn() returns, and spawnMob() deliberately does not"
                    + " register one, so the two id spaces cannot drift into disagreeing about"
                    + " who is where. So 'the mob is dead' is not yet expressible and no task may"
                    + " claim it",
            "sprint does not change local speed (1.8.9 applies it server-side), and the MOVE slot"
                    + " never requests it anyway",
            "dig duration uses the real block hardness curve but no block is unbreakable and no"
                    + " tool is enchanted",
            "dig drops come from Block.getItemDropped plus the real ItemStack.canHarvestBlock gate,"
                    + " but not from Block.getDrops itself: that needs a World, a BlockPos and a"
                    + " LootContext, so a silk-touch or explosion loot roll is not reproduced",
            "the routing COMPOSITION McpCore performs at startup is verified by nothing here:"
                    + " RoutePlanning.executorFor(GameAccess, ...) is the only public entry to"
                    + " routing and needs a GameAccess, a World and a live EntityPlayerSP, so this"
                    + " harness supplies its own route factory over the same Planner and"
                    + " RouteExecutor with SimWorld as the BlockView. That substitution is legal --"
                    + " MoveApplier takes a routeFactory parameter precisely so it is -- but if a"
                    + " change makes the SERVER-world-vs-client-world choice matter, no task here"
                    + " will notice",
            "a body that overlaps a solid block while DESCENDING is raised onto that block's top,"
                    + " which is how vanilla's down-axis collision works, and the consequence here"
                    + " is that a player is lifted onto a full one-block ledge with NO jump at all:"
                    + " measured with RouteExecutor.jump() forced to false, this world still puts"
                    + " the player at y=65.12. So 'the body ended up above the step' is NOT evidence"
                    + " that the jump axis was used -- T08 measures a mutant with the jump removed"
                    + " and passes on that proxy -- and a task claiming the jump must read"
                    + " SimWorld.inputTrace()",
            "SCOPE OF CONTAINER OPENING, added for the goal-directed task: right-clicking a"
                    + " crafting table opens a real SimCraftWindow (ContainerWorkbench's own layout"
                    + " over this world's live inventory), which is what BlockWorkbench."
                    + "onBlockActivated does. Nothing else opens: a chest, a furnace and an"
                    + " enchanting table all still return a plain placement refusal, so this is a"
                    + " ONE-BLOCK container model and not a screen system. A live client reaches"
                    + " the same window through GuiScreen and GuiContainer, and the click that"
                    + " opens it is a packet this world never sends -- so a task that opens a bench"
                    + " measures the CONTROLLER composing around a window, not the GUI"
                    + " interaction that produces one",
            "DAMAGE exists and DAMAGE REDUCTION does not, and the two are not the same gap. The"
                    + " health bar is a real ledger now (SurvivalDamage): the fire clock"
                    + " (Entity:499-501), lava contact and its ignition (Entity:543-544), the"
                    + " drown tick (ELB:303-315), the fall (ELB:1156), the void kill"
                    + " (Entity:514-517) and the 10-tick hurt window (ELB:896) are all transcribed,"
                    + " so a task CAN observe the player dying and a survival row can come back"
                    + " false. What is still absent is everything that changes HOW MUCH a hit"
                    + " costs: armour and its enchantments (CombatRules.getDamageAfterAbsorb),"
                    + " absorption hearts, the Resistance and Fire Resistance potions, difficulty"
                    + " scaling, and the cactus/anvil/falling-block sources that ride on"
                    + " onFallenUpon. A zombie therefore lands its flat attackDamage attribute and"
                    + " a body in lava takes the unenchanted bill. So 'the player died' here is"
                    + " 'the substrate's model of the server decided it' -- the same caveat the mob"
                    + " line above carries, and for the same reason. Fire RESISTANCE POTION is the"
                    + " one that will bite soonest: Entity:505 refuses every fire hit outright when"
                    + " it is active, and this world has no potions, so a task that ever gives one"
                    + " will get a wrong answer rather than a missing one",
            "ENCLOSURE is measurable and DANGER is not, and the two are not the same gap. The"
                    + " block grid is real, so Enclosure can ask vanilla's own question -- is this"
                    + " cell's column blocked above (Chunk.canSeeSky:904-910, read through"
                    + " generateHeightMap:221-236, whose rule is getLightOpacity() != 0) and are"
                    + " all eight horizontal neighbours of the two cells the body occupies cells a"
                    + " body cannot enter (SimWorld.solid) -- and NightEnclosure can fold that"
                    + " across a whole night sampled on the Daylight clock. What is still absent"
                    + " is every reason a player gives for wanting a roof at all: no light level, so"
                    + " the mob spawn gate cannot fire; no pathfinding reachability, so 'nothing"
                    + " could get in' is a statement about the eight neighbour CELLS and not about"
                    + " whether anything could walk a corridor to them; and nothing in the night"
                    + " damages anybody (see the clock entry above). So 'the player was enclosed'"
                    + " and 'the player was never hurt' are two MEASUREMENTS of one body over one"
                    + " night and are NOT a claim that the enclosure caused the survival",
            "A BOX IS NOW MEASURABLE and a chest's CONTENTS are still not, and the two are not the"
                    + " same gap. DawnChest asks the block grid vanilla's own question -- is this"
                    + " cell holding a BlockChest -- and folds it across a night on the Daylight"
                    + " clock, and it reports the POINT reading (a chest on the last night tick) and"
                    + " the REGION reading (a chest on every night tick) separately, because a box"
                    + " built at 3am passes the first and fails the second. It has a PRODUCER now:"
                    + " EvalSuite T26 (the_agent_built_the_box_and_it_stood_at_dawn) builds a chest"
                    + " through the real recipe table in a world that had none -- planks x8 in a"
                    + " 3x3, so a bench, which is planks x4 in a 2x2, which is a log -- and stands"
                    + " it down through a production InteractIntent.place. What that row measures is"
                    + " therefore two things and not one: the agent BUILT the box (the world is"
                    + " censused for chests and for chest items BEFORE the policy's first action and"
                    + " the pass condition requires both to have been zero, which is a measurement"
                    + " and not a fixture promise -- a chest the fixture planted leaves the row red"
                    + " with the box standing throughout), and the agent did not dig it out."
                    + " Nothing else in this substrate can remove a chest -- there is no fire block"
                    + " anywhere in it, and a chest is not flammable in 1.8.9 anyway (Blocks.chest"
                    + " is absent from BlockFire.init()'s 36 entries, BlockFire.java:74-108, so"
                    + " getEncouragement returns 0 for it); BlockLiquid never calls setBlockToAir,"
                    + " so lava destroys no blocks in the real game either; SimMob.kinds() refuses"
                    + " creeper and nothing here calls getExplosionResistance. CONTENTS are still"
                    + " NOT MEASURED, with no accessor on DawnChest that could pretend otherwise:"
                    + " this grid is a Map<Long, Block> and a chest here is a block with nothing"
                    + " behind it, so 'what is in it' has no answer to give. Note that even WITH a"
                    + " container the third part of that criterion would not be a decay question:"
                    + " the only decay clock in the vendored tree is"
                    + " InventoryPlayer.decrementAnimations (InventoryPlayer.java:352-362), which"
                    + " walks the PLAYER's mainInventory, and ItemFood overrides no part of that"
                    + " chain -- there is no onUpdate in that file at all. What actually empties a"
                    + " chest in 1.8.9 is the BREAK (BlockChest.breakBlock ->"
                    + " InventoryHelper.dropInventoryItems, BlockChest.java:414-425), and what"
                    + " then loses the stack is the EntityItem despawn at age >= 6000"
                    + " (EntityItem.java:145-147) against a night of 8,386 ticks: a stack knocked"
                    + " out of the box at dusk is gone 2,386 ticks before dawn. Neither that clock"
                    + " nor the container is transcribed here, so the honest sentence for the"
                    + " fourth north-star criterion is that it measures the box the agent built and"
                    + " kept, not its contents"
        );

    // ===== the world =====

    private static Block boot() {
        net.minecraft.init.Bootstrap.register();
        return null;
    }

    static {
        boot();
    }

    private final Map<Long, Block> grid = new HashMap<>();
    private final Map<Integer, SimEntity> entities = new LinkedHashMap<>();
    /**
     * Mobs that ACT, as opposed to the {@link #entities} above, which are targets that stand still
     * and take damage. The split is deliberate and it is the whole point of {@link SimMob}: a
     * "kill the zombie" task scored against a mob that never walks, never loses sight and never
     * turns would be measuring the absence of a transcription while looking like a combat test.
     * A task opts in explicitly, so the tasks that already existed cannot change meaning.
     */
    private final List<SimMob> mobs = new ArrayList<>();
    /**
     * The health bar and every clock that can move it, in vanilla's order, transcribed once.
     *
     * <p>This replaces a bare {@code double health} field that only the fixture setter, the food
     * path and the mob swings could write. A world with a lava band in it therefore had no way to
     * hurt anybody, which is what made every survival assertion in the suite a comparison of
     * {@code 20.0 > 0.0}.
     */
    private final SurvivalDamage survival = new SurvivalDamage();
    /**
     * Calls through the {@code ActActuator.blockAt} seam, so "the damage cost no world read" is a
     * number rather than an intention. See {@link #seamReads()}.
     */
    private int seamReads;
    private final InventoryPlayer inventory = new InventoryPlayer(null);

    private double x;
    private double y;
    private double z;
    private double motionX;
    private double motionY;
    private double motionZ;
    private float yaw;
    private float pitch;
    private boolean onGround = true;
    private boolean collidedHorizontally;
    private boolean sprinting;
    private int jumpTicks;
    /** Whether a sneak key is down this tick. Recorded, never integrated (see tick()). */
    private boolean sneaking;
    /**
     * Whether a sprint key is down this tick. Recorded but not integrated, because 1.8.9 applies
     * the sprint multiplier server-side -- see {@link #KNOWN_GAPS}.
     */
    private boolean sprintKey;
    /**
     * Vanilla's maximum air, in ticks ({@code EntityLivingBase:301}). Full when out of water.
     */
    public static final int MAX_AIR_TICKS = 300;
    /**
     * {@code EntityLivingBase.maxHurtResistantTime = 20} ({@code :95}), which
     * {@code EntityPlayer} keeps. A second hit equal to or below the last is refused outright inside
     * the window, so two zombies swapping swings halve each other's damage. The band is HALF of
     * it -- {@code :896} compares against {@code maxHurtResistantTime / 2.0F} -- and the
     * arithmetic lives in {@link SurvivalDamage#HURT_WINDOW_TICKS}.
     */
    public static final int MAX_HURT_RESISTANT_TIME = SurvivalDamage.MAX_HURT_RESISTANT_TICKS;
    private int nextEntityId = 1;
    private boolean inWorld = true;

    /** Per-tick record of what the INPUT LAYER handed the player. Read by the timing tasks. */
    private final List<float[]> inputTrace = new ArrayList<>();
    private final List<double[]> posTrace = new ArrayList<>();
    private int ticks;
    /**
     * Vanilla's {@code Entity.fallDistance}, maintained by {@link #moveWithCollision} exactly as
     * {@code Entity.updateFallState:1034-1055} maintains it.
     */
    private double fallDistance;
    /**
     * The sneak key as read DURING this tick's move, which is when vanilla's edge guard reads it.
     *
     * <p>Separate from {@link #sneaking}, which is the end-of-tick record a task asserts on:
     * {@code Entity.moveEntity:626} asks {@code isSneaking()}, which on a client is
     * {@code movementInput.sneak} ({@code EntityPlayerSP:684-687}) -- the input, mid-tick, before
     * anything this class records afterwards.
     */
    private boolean sneakingThisTick;

    // ===== the clock =====
    //
    // The substrate's clock, transcribed rather than stood in for, and the reason it is 1:1 with
    // ticks is that a night is 8,386 ticks long. A clock that ran at some other rate would make
    // "can the agent get through the night" a question about the fixture's arithmetic, so the
    // increment below is WorldServer.tick's own line (WorldServer.java:206-209) rather than
    // anything chosen here.
    //
    // It starts at 0 because that is what a client world is constructed with: WorldInfo's
    // populateFromWorldSettings (WorldInfo.java:253-262) assigns seed, game type, map features,
    // hardcore, terrain type, generator options and allowCommands, and NOT worldTime, so the
    // field keeps its default. 0 is sunrise, which is why a task that does not pin the clock
    // starts in the morning -- deliberately, so "this task happened to run at dawn" is visible
    // rather than accidental.
    private long worldTime;
    /**
     * Vanilla's {@code doDaylightCycle} game rule, which gates the increment
     * ({@code WorldServer.java:206}). It is a control rather than a convenience: with it off, a
     * pinned clock NEVER reaches night however long the task runs, and that is the property a
     * task needs in order to say its result is about something other than the clock.
     */
    private boolean doDaylightCycle = true;


    /**
     * Drops the bag had no room for, which vanilla leaves lying on the ground.
     *
     * <p>Collected rather than discarded so a caller can tell "the world has none of this" from
     * "the world had it and I could not carry it". Those two are the same empty inventory and
     * opposite problems, and a policy that cannot tell them digs the block out of the world for
     * nothing and then concludes the block does not exist.
     */
    private final List<ItemStack> droppedOntoFloor = new ArrayList<>();
    // Stacks this agent threw out of its own inventory on purpose. Kept apart from
    // `droppedOntoFloor` because the two mean opposite things -- see dropStack's javadoc.
    private final List<ItemStack> thrownByPlayer = new ArrayList<>();
    // Dig progress, keyed by the block position, mirroring PlayerControllerMP.curBlockDamageMP.
    private final Map<Long, Float> digProgress = new HashMap<>();

    /**
     * The inventory's own cursor, i.e. {@code InventoryPlayer.itemStack}. Vanilla holds it as
     * {@code null} when empty, and so does this: an "air stack" would make {@code null} and
     * "carrying nothing" two different things, and {@code ItemStack.getItem} dereferences its
     * item on every call.
     */
    private ItemStack cursor;

    /** A live entity: position, health, and the damage an attack lands. */
    public static final class SimEntity {
        final int id;
        double x;
        double y;
        double z;
        double health;
        String name;
        int hits;

        SimEntity(int id, double x, double y, double z, double health, String name) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
            this.health = health;
            this.name = name;
        }
    }

    public SimWorld() {
    }

    // ===== world building =====

    private static long key(int bx, int by, int bz) {
        return ((long) bx & 0x1FFFFF) | (((long) by & 0x1FFFFF) << 21) | (((long) bz & 0x1FFFFF) << 42);
    }

    /** Resolve a registry name to the real vanilla block. Fails loudly on a typo. */
    public static Block block(String name) {
        Block b = Block.getBlockFromName(name);
        if (b == null) {
            throw new IllegalArgumentException("no such block in the registry: " + name);
        }
        return b;
    }

    /** Resolve a registry name to the real vanilla item. Fails loudly on a typo. */
    public static Item item(String name) {
        Item i = Item.getByNameOrId(name);
        if (i == null) {
            throw new IllegalArgumentException("no such item in the registry: " + name);
        }
        return i;
    }

    public SimWorld put(int bx, int by, int bz, String name) {
        grid.put(key(bx, by, bz), block(name));
        return this;
    }

    public SimWorld put(int bx, int by, int bz, Block b) {
        grid.put(key(bx, by, bz), b);
        return this;
    }

    /** A flat plain of {@code name} at {@code y}, spanning the given inclusive X and Z range. */
    public SimWorld plain(int y, String name, int x0, int x1, int z0, int z1) {
        Block b = block(name);
        for (int bx = x0; bx <= x1; bx++) {
            for (int bz = z0; bz <= z1; bz++) {
                grid.put(key(bx, y - 1, bz), b);
            }
        }
        return this;
    }

    /** A solid cuboid of {@code name}, inclusive on all three axes. */
    public SimWorld box(int x0, int y0, int z0, int x1, int y1, int z1, String name) {
        Block b = block(name);
        for (int bx = x0; bx <= x1; bx++) {
            for (int by = y0; by <= y1; by++) {
                for (int bz = z0; bz <= z1; bz++) {
                    grid.put(key(bx, by, bz), b);
                }
            }
        }
        return this;
    }

    public SimWorld remove(int bx, int by, int bz) {
        grid.remove(key(bx, by, bz));
        return this;
    }

    /** Stand the player at the CENTRE of a block's floor, which is the only unambiguous spot. */
    public SimWorld standOn(int bx, int floorY, int bz) {
        this.x = bx + 0.5D;
        this.y = floorY;
        this.z = bz + 0.5D;
        this.motionX = 0.0D;
        this.motionY = 0.0D;
        this.motionZ = 0.0D;
        this.onGround = true;
        return this;
    }

    public SimWorld facing(float yawDeg) {
        this.yaw = yawDeg;
        return this;
    }

    public SimWorld atHealth(double hp) {
        survival.setHealth((float) hp);
        return this;
    }


    public SimWorld outOfWorld() {
        this.inWorld = false;
        return this;
    }

    // ===== inventory =====

    /** Put a stack in a specific inventory slot. Slot 0..8 is the hotbar. */
    public SimWorld give(int slot, String itemName, int count) {
        inventory.mainInventory[slot] = new ItemStack(item(itemName), count);
        return this;
    }

    /** Put a stack in the first empty slot, the way a drop is picked up. */
    public SimWorld give(String itemName, int count) {
        int slot = inventory.getFirstEmptyStack();
        if (slot < 0) {
            throw new IllegalStateException("inventory is full, cannot carry " + count + "x " + itemName);
        }
        inventory.mainInventory[slot] = new ItemStack(item(itemName), count);
        return this;
    }

    /** How many of an item the player is carrying, across every slot. */
    public int count(String itemName) {
        Item want = item(itemName);
        int total = 0;
        for (ItemStack s : inventory.mainInventory) {
            if (s != null && s.getItem() == want) {
                total += s.stackSize;
            }
        }
        return total;
    }

    /** The name of what is in a slot, or null. */
    public String slotName(int slot) {
        ItemStack s = inventory.mainInventory[slot];
        if (s == null || s.stackSize <= 0) {
            return null;
        }
        return nameOf(s.getItem());
    }

    public int slotCount(int slot) {
        ItemStack s = inventory.mainInventory[slot];
        return s == null ? 0 : s.stackSize;
    }

    public InventoryPlayer inventory() {
        return inventory;
    }

    /** What the player is holding on the container cursor, or null. */
    public ItemStack cursor() {
        return cursor;
    }

    void setCursor(ItemStack s) {
        this.cursor = s;
    }

    /** The registry name of an item, namespace-stripped, matching what {@code blockAt} returns. */
    public static String nameOf(Item i) {
        Object o = Item.itemRegistry.getNameForObject(i);
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o);
        int colon = s.indexOf(':');
        return colon >= 0 ? s.substring(colon + 1) : s;
    }

    // ===== entities =====

    public int spawn(String name, double ex, double ey, double ez, double hp) {
        int id = nextEntityId++;
        entities.put(id, new SimEntity(id, ex, ey, ez, hp, name));
        return id;
    }

    public boolean entityAlive(int id) {
        SimEntity e = entities.get(id);
        return e != null && e.health > 0.0D;
    }

    public double entityHealth(int id) {
        SimEntity e = entities.get(id);
        return e == null ? -1.0D : e.health;
    }

    public int entityHits(int id) {
        SimEntity e = entities.get(id);
        return e == null ? 0 : e.hits;
    }

    /** The entity's eye position, which is what {@link ActActuator#entityEyePos} promises. */
    public double[] entityEye(int id) {
        SimEntity e = entities.get(id);
        return e == null ? null : new double[] {e.x, e.y + EYE_HEIGHT, e.z};
    }

    // ===== mobs =====

    /**
     * Spawn a mob that acts: it acquires on its own duty cycle, turns at 30 degrees a tick, walks
     * toward a stale path point and swings when it is close enough ({@link SimMob}).
     *
     * @return the mob, or null when {@code name} is not one of {@link SimMob#kinds()} -- a refusal
     *         rather than a stand-in, because a "fight the creeper" task scored against something
     *         that is not a creeper measures nothing.
     */
    public SimMob spawnMob(String name, double ex, double ey, double ez) {
        SimMob.Kind kind = SimMob.kindOf(name);
        if (kind == null) {
            return null;
        }
        SimMob mob = new SimMob(nextEntityId++, kind, ex, ey, ez, kind.maxHealth());
        mobs.add(mob);
        return mob;
    }

    /** The mobs that are acting, in spawn order. */
    public List<SimMob> mobs() {
        return List.copyOf(mobs);
    }

    /**
     * {@code EntitySenses.canSee:31-58} -> {@code EntityLivingBase.canEntityBeSeen:2155-2157}: a
     * ray from eye to eye that must not hit a block.
     *
     * <p>Vanilla traces the ray against the block grid; this walks it in {@link #SIGHT_STEP}-block
     * steps instead. A step of 0.2 cannot skip a full cube, and only whole cubes stop sight in this
     * world, so the discretisation cannot report "sees through a wall". The endpoint cells are not
     * sampled: vanilla's trace starts inside the looker and stops at the lookee.
     */
    public boolean canSee(double[] from, double[] to) {
        double dx = to[0] - from[0];
        double dy = to[1] - from[1];
        double dz = to[2] - from[2];
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-6D) {
            return true;
        }
        for (double d = SIGHT_STEP; d < len - 1.0E-6D; d += SIGHT_STEP) {
            double t = d / len;
            if (solid(blockAtObj((int) Math.floor(from[0] + t * dx),
                    (int) Math.floor(from[1] + t * dy),
                    (int) Math.floor(from[2] + t * dz)))) {
                return false;
            }
        }
        return true;
    }

    /** {@link #SIGHT_STEP}, in blocks. */
    private static final double SIGHT_STEP = 0.2D;

    /**
     * {@code EntityLivingBase.attackEntityFrom:863-912}, applied to this world's survival ledger.
     *
     * <p>The window arithmetic now lives in {@link SurvivalDamage} rather than here, because the
     * environmental damage that lands every tick has to obey the same window the mob swings do --
     * two copies of that rule is how a body ends up invulnerable to lava and mortal to a zombie.
     * It is {@code :896-911} verbatim: inside the band a hit equal to or below the last is
     * refused, and a bigger one lands only the difference; outside it the whole hit lands.
     *
     * <p><b>No armour, no absorption, no difficulty scaling.</b> Those are server-side and this
     * substrate has none of them, so {@code CombatRules.getDamageAfterAbsorb} is not transcribed
     * rather than approximated -- see {@link #KNOWN_GAPS}. A zombie therefore hits for its flat
     * {@code attackDamage} attribute, which is the number {@link SimMob.Kind#attackDamage()} holds.
     *
     * @return whether damage actually landed, which is what {@link SimMob#hits} counts
     */
    public boolean damagePlayer(double amount, SimMob source) {
        return survival.attackFrom(amount, SurvivalDamage.Source.MOB);
    }

    // ===== the tick =====

    public int ticks() {
        return ticks;
    }

    public double[] pos() {
        return new double[] {x, y, z};
    }

    public double posX() {
        return x;
    }

    public double posY() {
        return y;
    }

    public double posZ() {
        return z;
    }

    /**
     * The health bar as it stands at the end of the run.
     *
     * <p><b>Read {@link #minimumHealth()} for a survival claim, not this.</b> A body can be hurt
     * and then healed inside one run, and this figure would report a survivor who was one lava
     * tick from dead three hundred ticks ago. That is the whole difference between the assertion
     * this accessor used to carry -- {@code w.health() > 0.0D} against a value nothing could move
     * -- and one that can come back with a different answer.
     */
    public double health() {
        return survival.health();
    }

    /** The lowest the bar has ever been on this run, in half-hearts. */
    public double minimumHealth() {
        return survival.lowestHealth();
    }

    /** Whether the bar ever went below the project's north-star floor of 18 half-hearts. */
    public boolean brokeNorthStar() {
        return survival.brokeNorthStar();
    }

    /** Whether the player is still alive, which a hazard that reached the body can now answer. */
    public boolean alive() {
        return survival.alive();
    }

    /**
     * Whether anything at all damaged the player on this run.
     *
     * <p>The question a survival assertion should ask about itself. A world with no lava, no
     * water, no drop and no mob answers false, and a task whose row claims to have checked
     * survival in such a world is reporting a constant.
     */
    public boolean anyHazard() {
        return survival.anyHazard();
    }

    /** Ticks whose bounding box held lava. Zero means the lava row was never exercised. */
    public int ticksInLava() {
        return survival.ticksInLava();
    }

    /** The survival ledger itself, for a task that wants the per-source breakdown. */
    public SurvivalDamage survival() {
        return survival;
    }

    /** Whether a sneak key is down this tick. Recorded, never integrated (see tick()). */
    public boolean sneaking() {
        return sneaking;
    }

    /** Whether a sprint key is down this tick. */
    public boolean sprinting() {
        return sprinting;
    }

    /** The horizontal distance walked on tick {@code n}, for tasks about stalled progress. */
    public double step(int n) {
        if (n <= 0 || n >= posTrace.size()) {
            return 0.0D;
        }
        double[] a = posTrace.get(n - 1);
        double[] b = posTrace.get(n);
        return Math.hypot(b[0] - a[0], b[2] - a[2]);
    }

    public double eyeY() {
        return y + EYE_HEIGHT;
    }

    /** What the input layer handed over on each tick, as {forward, strafe, jump}. */
    public List<float[]> inputTrace() {
        return inputTrace;
    }

    /** Where the player ended up on each tick. */
    public List<double[]> posTrace() {
        return posTrace;
    }

    // ===== the clock, as a fact a task can assert on =====
    //
    // Four fixture setters and two predicates, and every answer comes out of Daylight rather than
    // out of arithmetic written here. That is the whole discipline: if this class kept its own
    // copy of the day/night rule then a task asserting isNight() would be asserting the fixture
    // agreeing with itself, and the production helper could be wrong by any amount without a
    // single row going red.

    /** The clock. Advances one per tick while the daylight cycle is on. */
    @Override
    public long worldTime() {
        return worldTime;
    }

    /** {@link Daylight#isDaytime(long)} on this world's clock -- the same call the live one makes. */
    @Override
    public boolean isDaytime() {
        return Daylight.isDaytime(worldTime);
    }

    /**
     * Whether it is night, computed through the same {@link Daylight} call as
     * {@link #isDaytime()} -- the fixture and the predicate cannot drift apart because there is
     * only one of them.
     */
    public boolean isNight() {
        return Daylight.isNight(worldTime);
    }

    /**
     * Ticks from now until this world is in daylight again, or {@code 0} if it already is.
     *
     * <p>Computed by asking {@link Daylight#isDaytime(long)} forward tick by tick rather than by
     * subtracting two hardcoded boundaries, so a change to the curve moves this answer instead of
     * leaving it quietly stale. Bounded at one full day, which is unreachable: vanilla's own
     * curve is night for 8,386 consecutive ticks and then day for the rest, so the search always
     * terminates well inside the bound.
     */
    public int ticksUntilDawn() {
        for (int i = 0; i < 24000; i++) {
            if (Daylight.isDaytime(worldTime + i)) {
                return i;
            }
        }
        return 24000;
    }

    /**
     * Pin the clock. The fixture setter, kept separate from {@link #isDaytime()} so a task can
     * establish WHEN it is rather than asserting THAT it is: the assertion is the task's job and
     * belongs in the task's own body where a reader can see it.
     */
    public SimWorld atWorldTime(long t) {
        this.worldTime = t;
        return this;
    }

    /**
     * Vanilla's {@code doDaylightCycle} game rule. Off means the clock does not advance, which is
     * the control a task needs to show its result is about something other than the time of day.
     */
    public SimWorld doDaylightCycle(boolean on) {
        this.doDaylightCycle = on;
        return this;
    }

    /**
     * Jump to the next sunrise, as a night-skip does ({@code WorldServer.java:177-186}).
     *
     * <p>Vanilla's own arithmetic, including the {@code - i % 24000} that lands on the day
     * boundary rather than merely 24,000 ticks later -- so this lands on a world time of exactly
     * 0, not on "somewhere in the morning". That distinction is the trap this method exists for: a
     * task that assumes a night lasts a fixed number of ticks and does not account for the skip
     * will report a night that never ended, when what actually happened is that the player slept
     * through it.
     */
    public SimWorld skipToNextDay() {
        long i = worldTime + 24000L;
        this.worldTime = i - i % 24000L;
        return this;
    }

    /**
     * One game tick, in vanilla's order: the input layer is read, then the body integrates.
     *
     * <p>The caller owns the input: it calls {@code input.updatePlayerMoveState()} itself (that is
     * what {@code EntityPlayerSP.onLivingUpdate:786} does) so the override the act layer installed
     * is the thing being measured. This method then does the rest of the tick.
     */
    public void tick(MovementInput input) {
        // EntityPlayerSP:784 — the sprint test reads the axis from BEFORE this tick's state
        // update, which is the whole reason a hold-forever walk is not a sprint.
        boolean wasForwardHigh = input.moveForward >= 0.8F;

        // EntityPlayerSP:786 — the call the act layer overrides. It is the caller's job to make,
        // not this method's: the point of the eval is to measure what the INPUT LAYER produced,
        // and doing it here would let this class quietly publish the answer it is being asked to
        // publish.
        input.updatePlayerMoveState();

        // EntityPlayerSP:684-687 — isSneaking() IS movementInput.sneak, and Entity.moveEntity:626
        // reads it mid-tick to decide the edge guard. Sampled before the move so the movement code
        // reads the key as vanilla reads it rather than as it was recorded at the end of last tick.
        this.sneakingThisTick = input.sneak;

        // EntityPlayerSP:788-792 — a use in progress scales BOTH axes to 0.2x on the input
        // itself, before the body ever sees them. This is why a walk that starts while eating
        // crawls, and it is a real behaviour rather than a quirk, so it is transcribed.
        if (usingItem()) {
            input.moveStrafe *= 0.2F;
            input.moveForward *= 0.2F;
        }

        // EntityPlayerSP:696-698 — the body's axes ARE the MovementInput's, copied every tick.
        double moveStrafing = input.moveStrafe;
        double moveForward = input.moveForward;
        boolean isJumping = input.jump;

        // EntityPlayerSP:801-821 — sprint engages on a high forward axis and drops on a collision.
        boolean sprintKey = sprinting;
        if (onGround && !input.sneak && wasForwardHigh && !sprinting && !usingItem()) {
            sprinting = true;
        }
        if (sprinting && (moveForward < 0.8 || collidedHorizontally || usingItem())) {
            sprinting = false;
        }
        // NOTE: sprinting only sets the flag above. 1.8.9 applies the sprint multiplier on the
        // SERVER, so it does not change the client's own movement -- see KNOWN_GAPS.

        // EntityLivingBase:2031-2032.
        moveStrafing *= INPUT_DAMP;
        moveForward *= INPUT_DAMP;

        if (isJumping) {
            // EntityLivingBase:2008-2022.
            if (onGround && jumpTicks == 0) {
                motionY = JUMP;
                jumpTicks = JUMP_COOLDOWN_TICKS;
            }
        } else {
            jumpTicks = 0;
        }
        if (jumpTicks > 0) {
            jumpTicks--;
        }

        // Where the body IS this tick, sampled before it moves. Vanilla asks isInLava at
        // Entity.update:508, which runs before onLivingUpdate's travel, so the contact test is
        // against the position the body woke up in -- not the one it is about to walk into. One
        // tick of difference, and it decides whether a body that ends a tick inside lava was
        // standing in it when the damage was applied.
        boolean inLavaThisTick = inLava();
        boolean inWaterThisTick = bodyInWater();

        // EntityLivingBase:2034 — travel, i.e. moveEntityWithHeading.
        travel(moveStrafing, moveForward, WALK_SPEED);

        // The server's turn, and the whole reason a health bar can move in this world. Vanilla
        // runs its damage from Entity.onEntityUpdate, which Entity.onUpdate:405 calls before
        // onLivingUpdate ever travels, so the two hazard probes above are sampled at the right
        // instant and the landing from this tick's move is handed over with them. SurvivalDamage
        // then applies the four in the source's own order: fire clock, lava contact, void kill,
        // drown tick, window decrement, fall -- see its class doc for the line numbers.
        //
        // Every damage path in this world goes through attackFrom, so the hurt window applies to
        // lava exactly as it applies to a zombie. That is not a detail: it is why standing in
        // lava costs 20 half-hearts over 23 ticks in this substrate and not 10 every tick.
        survival.step(new SurvivalDamage.Hazards(inLavaThisTick, inWaterThisTick,
                (float) playerBody.landedFrom, y < VOID_Y));
        playerBody.landedFrom = 0.0D;

        // A sneak key down is a walk-speed change in vanilla, applied to the attribute rather
        // than the axis, so it is recorded rather than integrated. Nothing in the act layer sets
        // it; the field exists so a task can assert the input reached the body at all.
        this.sneaking = input.sneak;
        this.sprintKey = sprintKey;

        // EntityPlayer.onUpdate:286 — the use counts down inside the player's own update, so it
        // lives HERE and not in the harness. An earlier version left it to the caller, and the
        // consequence was sharp: HoldController watched itemInUseCount() sit at 32 for 72 ticks
        // and correctly refused to believe a use that was not progressing. The controller was
        // right and the world was wrong, which is the correct order of things but an unusable
        // test. The use clock is the player's, so the player advances it.
        advanceUse();

        ticks++;
        inputTrace.add(new float[] {input.moveForward, input.moveStrafe, input.jump ? 1f : 0f});
        posTrace.add(new double[] {x, y, z});

        // WorldServer.java:206-209 -- the clock advances by exactly one per tick, and only while
        // doDaylightCycle holds. Transcribed rather than approximated because a night is 8,386
        // ticks long (measured: 22193 - 13807 against vanilla's own calculateSkylightSubtracted),
        // so any other rate would answer a different question than the one the task is asking.
        // The client advances the same counter at WorldClient.java:71-74, so this is the shape
        // both sides of the wire actually run.
        if (doDaylightCycle) {
            worldTime++;
        }

        // World.tickEntities runs the non-player entities in the same tick as the player, after it.
        for (SimMob mob : mobs) {
            mob.tick(this, cells);
        }
    }

    /**
     * {@code EntityLivingBase.moveEntityWithHeading} for a walker on land, plus the gravity and
     * damping that follow it.
     *
     * <p>The arithmetic and the collision now live in {@link SimBody}, which the mobs in
     * {@link SimMob} run as well. The player keeps its fields here because a hundred call sites read
     * them, and this method is the one place they are copied into the body and back -- so the code
     * that decides whether a block stops you is the same object the code that decides whether a
     * block stops a zombie is.
     *
     * @param moveSpeed {@code getAIMoveSpeed()}: the {@code movementSpeed} attribute, which for
     *                  the player is the {@link #WALK_SPEED} base value from {@code EntityPlayer:193}
     */
    private void travel(double strafe, double forward, double moveSpeed) {
        SimBody b = playerBody;
        b.x = x;
        b.y = y;
        b.z = z;
        b.motionX = motionX;
        b.motionY = motionY;
        b.motionZ = motionZ;
        b.onGround = onGround;
        b.fallDistance = fallDistance;
        b.sneakingThisTick = sneakingThisTick;
        b.yaw = yaw;

        b.travel(cells, strafe, forward, moveSpeed);

        x = b.x;
        y = b.y;
        z = b.z;
        motionX = b.motionX;
        motionY = b.motionY;
        motionZ = b.motionZ;
        onGround = b.onGround;
        fallDistance = b.fallDistance;
        collidedHorizontally = b.collidedHorizontally;
    }

    /** Horizontal distance from a point, for tasks that care about closing it. */
    public double horizontalDistanceTo(double tx, double tz) {
        return Math.hypot(tx - x, tz - z);
    }

    public double distanceTo(double tx, double ty, double tz) {
        return Math.sqrt((tx - x) * (tx - x) + (ty - y) * (ty - y) + (tz - z) * (tz - z));
    }

    // ===== collision =====

    private Block blockAtObj(int bx, int by, int bz) {
        return grid.get(key(bx, by, bz));
    }

    /** Whether a block stops a body. Lava and water are liquids, so they do not. */
    public static boolean solid(Block b) {
        return b != null && b.getMaterial().blocksMovement() && b.isFullCube();
    }

    /**
     * The player's body. Reused, never reallocated: {@link #travel} copies the scalar fields into
     * it and back, so a hundred call sites can keep reading {@code x}/{@code y}/{@code z} while the
     * collision that decides what stops the body lives in one place shared with {@link SimMob}.
     */
    private final SimBody playerBody = new SimBody(WIDTH, HEIGHT, 0.0D, 0.0D, 0.0D);

    /** The world's own answers, handed to {@link SimBody} rather than re-decided inside it. */
    private static final class Cells implements SimBody.Solid {
        private final SimWorld world;

        Cells(SimWorld world) {
            this.world = world;
        }

        @Override
        public boolean solid(int bx, int by, int bz) {
            return SimWorld.solid(world.blockAtObj(bx, by, bz));
        }

        /**
         * {@code Block.slipperiness}, and 1.0 for an empty cell so that {@code * 0.91} reproduces
         * the airborne friction the inlined lookup used to leave standing.
         */
        @Override
        public double slipperiness(int bx, int by, int bz) {
            Block b = world.blockAtObj(bx, by, bz);
            return b == null ? 1.0D : b.slipperiness;
        }
    }

    private final SimBody.Solid cells = new Cells(this);


    // ===== digging =====

    /**
     * How many pumps vanilla needs to break this block, holding this item.
     *
     * <p>{@code Block.getPlayerRelativeBlockHardness:593} is
     * {@code toolEff / hardness / 30} when the block is harvestable and
     * {@code / 100} when it is not, accumulated until 1.0. Both the harvest test
     * ({@code InventoryPlayer.canHeldItemHarvest}) and the tool efficiency
     * ({@code ItemStack.getStrVsBlock}) are the real ones, so a bare hand on stone really is slow
     * and a wooden pickaxe really is not.
     */
    public int digTicksFor(int bx, int by, int bz) {
        Block b = blockAtObj(bx, by, bz);
        if (b == null) {
            throw new IllegalStateException("no block at " + bx + "," + by + "," + bz);
        }
        float hardness = b.getBlockHardness(null, new BlockPos(bx, by, bz));
        if (hardness < 0.0F) {
            throw new IllegalStateException("block " + nameOfBlock(b) + " is unbreakable");
        }
        ItemStack held = inventory.getCurrentItem();
        float toolEff = held == null ? 1.0F : held.getStrVsBlock(b);
        boolean canHarvest = b.getMaterial().isToolNotRequired()
                || (held != null && held.canHarvestBlock(b));
        float perPump = canHarvest ? toolEff / hardness / 30.0F : toolEff / hardness / 100.0F;
        if (perPump <= 0.0F) {
            throw new IllegalStateException("nothing can break " + nameOfBlock(b) + " here");
        }
        return (int) Math.ceil(1.0F / perPump);
    }

    /**
     * What breaking this block drops, through vanilla's OWN hooks and in vanilla's OWN order.
     *
     * <p>Two things, and both were wrong before this method was checked against the client:
     *
     * <ul>
     *   <li><b>Which item.</b> {@code Block.getItemDropped(IBlockState, Random, int)}, not
     *       {@code Item.getItemFromBlock}. The two are not the same:
     *       {@code BlockStone.getItemDropped:48-51} returns <b>cobblestone</b> for a stone block,
     *       because that is what vanilla hands you. The block-to-item map answers "stone", so a
     *       task that mines stone and expects cobblestone fails for a reason that has nothing to
     *       do with the dig controller -- a simulator that lies about the world teaches you to
     *       distrust the right code.</li>
     *   <li><b>Whether anything drops at all.</b> {@code Block.getDrops} is only reached when the
     *       breaker can harvest the block ({@code Block.isMined} /
     *       {@code ItemStack.canHarvestBlock}), so bare hands on stone yield <b>nothing</b> in
     *       real 1.8.9. Reading the drop unconditionally invents an item the game never gives,
     *       which is the same lie in the opposite direction.</li>
     * </ul>
     *
     * <p>Metadata 0 is passed, which is stone's own default variant, and the {@link java.util.Random}
     * is seeded 0 because the eval is deterministic: no task may depend on a random roll. That
     * seeded Random is also why {@code getDrops} itself is not called -- it needs a {@code World},
     * a {@code BlockPos} and a {@code LootContext}, and the only piece of it that varies by tool is
     * the harvest gate above. See {@link #KNOWN_GAPS}.
     */
    public String dropOf(int bx, int by, int bz) {
        Block b = blockAtObj(bx, by, bz);
        return b == null ? null : dropOf(b);
    }

    /**
     * What breaking {@code b} drops, by name, or null when nothing drops.
     *
     * <p>Overload taking the block rather than its cell, because the caller must be able to ask
     * <b>before</b> the cell is emptied. Asking a cell-based question after removing the block
     * answers "nothing", and a dig that yields no item because it asked the wrong order looks
     * exactly like a dig that yields no item because the controller is broken.
     */
    public String dropOf(Block b) {
        if (b == null || !canHarvest(b)) {
            return null;
        }
        Item dropped = b.getItemDropped(b.getStateFromMeta(0), new java.util.Random(0L), 0);
        // A block that names no drop (and a fortune-gated one that rolled nothing) drops nothing,
        // which is a real outcome and not a gap.
        return dropped == null ? null : nameOf(dropped);
    }

    /**
     * {@code Block.isMined} in the form {@code Block.getDrops} is reached through: a block that
     * needs no tool always drops, and one that does drops only into a tool that can harvest it.
     */
    private boolean canHarvest(Block b) {
        if (b.getMaterial().isToolNotRequired()) {
            return true;
        }
        ItemStack held = inventory.getCurrentItem();
        return held != null && held.canHarvestBlock(b);
    }

    static String nameOfBlock(Block b) {
        Object o = Block.blockRegistry.getNameForObject(b);
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o);
        int colon = s.indexOf(':');
        return colon >= 0 ? s.substring(colon + 1) : s;
    }

    // ===== ActActuator =====

    @Override
    public boolean inWorld() {
        return inWorld;
    }

    @Override
    public double[] eyePos() {
        return inWorld ? new double[] {x, y + EYE_HEIGHT, z} : null;
    }

    @Override
    public float yaw() {
        return yaw;
    }

    @Override
    public float pitch() {
        return pitch;
    }

    @Override
    public double reachDistance() {
        // LivePlayerActuator: a survival player's block reach. 5.0 is vanilla's constant.
        return 5.0D;
    }

    @Override
    public Graded<Target> mouseOver() {
        if (!inWorld) {
            // No world, so no ray was traced at all -- the same UNKNOWN the live seam gives when
            // there is no client, and NOT the miss below.
            return ActActuator.noRay();
        }
        Vec3 from = new Vec3(x, y + EYE_HEIGHT, z);
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double dx = -Math.sin(yawRad) * Math.cos(pitchRad);
        double dy = -Math.sin(pitchRad);
        double dz = Math.cos(yawRad) * Math.cos(pitchRad);
        double reach = reachDistance();
        for (double d = 0.0D; d <= reach; d += 0.05D) {
            int bx = (int) Math.floor(from.xCoord + dx * d);
            int by = (int) Math.floor(from.yCoord + dy * d);
            int bz = (int) Math.floor(from.zCoord + dz * d);
            if (solid(blockAtObj(bx, by, bz))) {
                return ActActuator.tracedLastFrame(Target.block(bx, by, bz, Face.UP,
                        new double[] {from.xCoord + dx * d, from.yCoord + dy * d, from.zCoord + dz * d}, d));
            }
        }
        return ActActuator.tracedLastFrame(Target.miss());
    }

    @Override
    public boolean blockPresent(int bx, int by, int bz) {
        return blockAtObj(bx, by, bz) != null;
    }

    @Override
    public String blockAt(int bx, int by, int bz) {
        seamReads++;
        Block b = blockAtObj(bx, by, bz);
        return b == null ? null : nameOfBlock(b);
    }

    /**
     * The vanilla block object at a cell, read off {@link #blockAtObj} and therefore off
     * {@code seamReads}.
     *
     * <p><b>This exists so the enclosure predicate can run on every tick of a night.</b>
     * {@link #blockAt} is the one seam a controller has and it is counted, so a measurement that
     * went through it would spend hundreds of reads per night and drift the exact numbers
     * {@code TheBeliefLayerCostsNoWorldReadTest} and {@code TheClockCostsTheWalkNoWorldReadTest}
     * pin at 25. The enclosure is the eval's own instrument reading a grid the world already
     * holds, exactly as {@link #materialInBodyBox} does for the hazard test, and it costs the
     * walk the same nothing.
     */
    public Block blockObjectAt(int bx, int by, int bz) {
        return blockAtObj(bx, by, bz);
    }

    /**
     * The enclosure predicate's view of this grid, which is the method reference above and
     * nothing else.
     */
    public Enclosure.CellGrid enclosureGrid() {
        return this::blockObjectAt;
    }

    /**
     * Calls made through the {@code ActActuator.blockAt} seam, which is the only way a controller
     * can look at a cell and the number {@code TheBeliefLayerCostsNoWorldReadTest} and
     * {@code TheClockCostsTheWalkNoWorldReadTest} are stated in.
     *
     * <p>Counted here rather than in a wrapper because {@code SimWorld} is final and the eval
     * harness is handed one directly. The counter is on the SEAM method only, never on
     * {@link #blockAtObj}, and that distinction is the whole point: the per-tick hazard test in
     * {@link #materialInBodyBox} reads the grid the world already holds, and the assertion in
     * {@code TheSurvivalDamageCostsNoWorldReadTest} is that doing so costs the seam nothing.
     */
    public int seamReads() {
        return seamReads;
    }

    @Override
    public int heldSlot() {
        return inventory.currentItem;
    }

    @Override
    public double[] entityEyePos(int id) {
        return entityEye(id);
    }

    @Override
    public void setRotation(float yawDeg, float pitchDeg) {
        this.yaw = yawDeg;
        this.pitch = pitchDeg;
    }

    @Override
    public void setRotationInterp(float pYaw, float pPitch, float yawDeg, float pitchDeg) {
        setRotation(yawDeg, pitchDeg);
    }

    @Override
    public boolean startDig(int bx, int by, int bz, Face face) {
        digProgress.remove(key(bx, by, bz));
        return blockAtObj(bx, by, bz) != null;
    }

    @Override
    public boolean pumpDig(int bx, int by, int bz, Face face) {
        Block b = blockAtObj(bx, by, bz);
        if (b == null) {
            digProgress.remove(key(bx, by, bz));
            return false;
        }
        ItemStack held = inventory.getCurrentItem();
        float hardness = b.getBlockHardness(null, new BlockPos(bx, by, bz));
        float toolEff = held == null ? 1.0F : held.getStrVsBlock(b);
        boolean canHarvest = b.getMaterial().isToolNotRequired()
                || (held != null && held.canHarvestBlock(b));
        float perPump = canHarvest ? toolEff / hardness / 30.0F : toolEff / hardness / 100.0F;
        long k = key(bx, by, bz);
        float progress = digProgress.getOrDefault(k, 0.0F) + perPump;
        if (progress >= 1.0F) {
            digProgress.remove(k);
            // Ask what drops WHILE the block is still there. This ordering is load-bearing and it
            // was wrong: the cell is emptied on the line below, and a cell-based drop lookup
            // therefore answered "nothing" every single time, so pickUp never ran and NO DIG IN
            // THIS SUBSTRATE EVER YIELDED AN ITEM. The dig curve was measurable and correct, so a
            // task asserting only "the block is gone" would have passed forever -- which is exactly
            // how the part with a test kept working while the part next to it never ran at all.
            String drop = dropOf(b);
            grid.remove(k);
            if (drop != null) {
                pickUp(new ItemStack(Item.getByNameOrId(drop), 1));
            }
            // The breaking tick has nothing left to damage, exactly as PlayerControllerMP
            // reports: the block is gone and there is no progress left to make.
            return false;
        }
        digProgress.put(k, progress);
        return true;
    }

    /**
     * A drop entering the inventory: merge into a matching partial stack, else the first empty.
     *
     * <p><b>A drop that cannot be carried is COUNTED, not discarded in silence.</b> The first
     * version dropped it and returned as though it had landed, so a dig into a full bag looked
     * exactly like a dig into an outcrop of nothing: the block went, the world lost it, the
     * inventory did not gain it, and nothing anywhere said why. A caller that watched only the
     * inventory could not tell "this world has no more of that" from "my bag is full" -- two
     * opposite problems that call for opposite responses, one of which is to stop digging.
     *
     * @return how many of the drop were actually stowed
     */
    private int pickUp(ItemStack drop) {
        for (int i = 0; i < inventory.mainInventory.length; i++) {
            ItemStack s = inventory.mainInventory[i];
            if (s != null && s.getItem() == drop.getItem() && s.getMetadata() == drop.getMetadata()
                    && s.stackSize < s.getMaxStackSize()) {
                int room = s.getMaxStackSize() - s.stackSize;
                int moved = Math.min(room, drop.stackSize);
                s.stackSize += moved;
                drop.stackSize -= moved;
                if (drop.stackSize <= 0) {
                    return moved;
                }
            }
        }
        int slot = inventory.getFirstEmptyStack();
        if (slot >= 0) {
            inventory.mainInventory[slot] = drop;
            return drop.stackSize;
        }
        // No room. The drop still existed -- vanilla would leave it on the ground -- and the caller
        // is told, because "I dug it and the world lost it and my bag did not gain it" is a fact
        // about the FULL BAG, not about the block.
        droppedOntoFloor.add(drop.copy());
        return 0;
    }

    /** Drops the bag had no room for, each as {@code count x item/meta}, in the order they were lost. */
    public List<String> droppedOntoFloor() {
        return droppedOntoFloor.isEmpty() ? List.of()
                : java.util.stream.IntStream.range(0, droppedOntoFloor.size())
                        .mapToObj(i -> droppedOntoFloor.get(i).stackSize + "x "
                                + nameOf(droppedOntoFloor.get(i).getItem()) + "/"
                                + droppedOntoFloor.get(i).getMetadata())
                        .collect(java.util.stream.Collectors.toList());
    }

    @Override
    public int slotStackSize(int playerSlot) {
        return playerSlot < 0 || playerSlot >= inventory.mainInventory.length ? 0
                : slotCount(playerSlot);
    }

    /**
     * The substrate's answer to {@link net.marcloud.mcp.core.drivers.act.ActActuator#dropStack}:
     * vanilla's own drop-from-a-slot branch, on a real {@code InventoryPlayer}.
     *
     * <p>{@code Container.slotClick:445-456}, mode 4 with {@code clickedButton} 1:
     * {@code slot3.decrStackSize(clickedButton == 0 ? 1 : slot3.getStack().stackSize)} then
     * {@code slot3.onPickupFromSlot} and {@code dropPlayerItemWithRandomChoice}. So the WHOLE stack
     * leaves the slot and the slot ends empty -- which is the fact
     * {@link net.marcloud.mcp.core.drivers.act.DropController} reads back, and the reason the
     * controller accepts nothing less than an empty slot as a successful drop.
     *
     * <p><b>The items do not vanish; they leave the BAG.</b> That is the distinction this
     * substrate exists to keep sharp, because the whole point of the drop is that the world can
     * now hold them: {@link #droppedOntoFloor} records drops the bag had no room for, and
     * {@link #thrownByPlayer} records stacks this agent deliberately threw away. They are separate
     * lists because they mean opposite things -- one is the world refusing to give, the other is
     * the agent giving back -- and a caller asking "did I lose anything" must not read its own
     * decision as a loss.
     */
    @Override
    public boolean dropStack(int playerSlot) {
        if (playerSlot < 0 || playerSlot >= inventory.mainInventory.length) {
            return false;
        }
        ItemStack stack = inventory.mainInventory[playerSlot];
        if (stack == null || stack.stackSize <= 0) {
            return false;
        }
        // splitStack is the real ItemStack method, and the real arithmetic of "the whole stack":
        // it hands back a copy of what it removed and leaves the remainder at 0.
        ItemStack thrown = stack.splitStack(stack.stackSize);
        if (stack.stackSize <= 0) {
            inventory.mainInventory[playerSlot] = null;
        }
        thrownByPlayer.add(thrown.copy());
        return true;
    }

    /** Stacks this agent threw out of its own inventory, each as {@code count x item/meta}. */
    public List<String> thrownByPlayer() {
        return thrownByPlayer.isEmpty() ? List.of()
                : java.util.stream.IntStream.range(0, thrownByPlayer.size())
                        .mapToObj(i -> thrownByPlayer.get(i).stackSize + "x "
                                + nameOf(thrownByPlayer.get(i).getItem()) + "/"
                                + thrownByPlayer.get(i).getMetadata())
                        .collect(java.util.stream.Collectors.toList());
    }

    /**
     * How many inventory slots are free to receive a stack outright.
     *
     * <p>Asked before a dig, because the answer changes what a dig MEANS: zero free slots does not
     * mean the block is unminable, it means mining it throws the result away. A partial stack of a
     * matching item is not counted here on purpose -- {@link #pickUp} merges into those, so they are
     * room, but only for that one item, and the honest question "can I put anything away at all" is
     * the empty-slot count.
     */
    public int freeSlots() {
        int free = 0;
        for (ItemStack s : inventory.mainInventory) {
            if (s == null || s.stackSize <= 0) {
                free++;
            }
        }
        return free;
    }

    @Override
    public void cancelDig() {
        digProgress.clear();
    }

    /**
     * {@code PlayerControllerMP.onPlayerDestroyBlock} in one line: the block is GONE and the drop
     * is in the player's hand.
     *
     * <p>It used to hand over the drop and leave the block standing, so an actuator asked to
     * "break" a block returned true and the block was still there. The contract is
     * {@code returns whether it broke}, and a caller that trusted the true would act on a world
     * state that never changed.
     */
    @Override
    public boolean instantBreak(int bx, int by, int bz, Face face) {
        Block b = blockAtObj(bx, by, bz);
        if (b == null) {
            return false;
        }
        String drop = dropOf(b);
        grid.remove(key(bx, by, bz));
        digProgress.remove(key(bx, by, bz));
        if (drop != null) {
            pickUp(new ItemStack(Item.getByNameOrId(drop), 1));
        }
        return true;
    }


    /**
     * The container this world currently has open, or null.
     *
     * <p>Set by {@link #rightClickBlock} when the clicked block is a crafting table, which is
     * {@code BlockWorkbench.onBlockActivated} opening a {@code ContainerWorkbench}. Read by a task
     * that has to decide whether it is holding a grid, and by the goal-directed policy, which has
     * to OPEN one before a 3x3 recipe is even possible.
     */
    private SimCraftWindow openWindow;

    /** The open container, or null when no screen is up. */
    public SimCraftWindow openWindow() {
        return openWindow;
    }

    /** Close whatever screen is open, as {@code displayGuiScreen(null)} does. */
    public void closeWindow() {
        if (openWindow != null) {
            openWindow.close();
            openWindow = null;
        }
    }

    /**
     * Put a specific window on screen, or take the current one down.
     *
     * <p>Separate from {@link #rightClickBlock} so a caller can hold a screen aside while it opens
     * another. Only one screen is ever up in vanilla, so replacing one closes it -- but a caller
     * that is part-way through a nested sequence needs to put the first one BACK, which is what
     * this allows and what closing outright would have destroyed.
     */
    public void setScreen(SimCraftWindow window) {
        if (openWindow != null && openWindow != window) {
            openWindow.close();
        }
        // Reopening is the other half of closing, and omitting it is what made this method a
        // reference assignment wearing a screen's clothes: a window set aside mid-sequence came
        // back holding the same object with `open` still false, so it answered every query as a
        // dead one. `displayGuiScreen(gui)` does not inspect the gui it is given; it displays it.
        if (window != null) {
            window.reopen();
        }
        openWindow = window;
    }

    /**
     * Whether a block opens a crafting screen when it is right-clicked.
     *
     * <p>Asked about the BLOCK rather than pattern-matched on its name, because the question is
     * really "does this block have an {@code onBlockActivated} that opens a container", and the
     * honest test of that in a headless world is the block's own type. {@code BlockWorkbench} is
     * 1.8.9's crafting table: {@code Block:1315} registers {@code crafting_table} as one, and
     * {@code BlockWorkbench.onBlockActivated} calls {@code displayGuiScreen} and returns true.
     *
     * <p>The empty-hand condition is {@code PlayerControllerMP.onPlayerRightClick:404-409}: the
     * activation is tried FIRST and, because the bench returns true, the placement that follows it
     * never runs. So right-clicking a bench with a building block in hand still opens the screen --
     * which is why {@link #rightClickBlock} asks this before it looks at what is held.
     */
    private static boolean opensCraftingScreen(Block b) {
        return b instanceof net.minecraft.block.BlockWorkbench;
    }

    /**
     * Right-click a block: open its screen if it has one, otherwise place against the face.
     *
     * <p>The order is {@code PlayerControllerMP.onPlayerRightClick}'s and it is load-bearing.
     * Vanilla tries {@code block.onBlockActivated} first and only falls through to the
     * {@code ItemBlock} placement when that returned false, so a crafting table opens whether or
     * not the player is holding something. Checking the held stack first would make a bench
     * un-openable while carrying planks -- and a goal-directed policy is holding planks for most of
     * exactly the sequence that needs the bench.
     */
    @Override
    public boolean rightClickBlock(int bx, int by, int bz, Face face,
                                   double hitX, double hitY, double hitZ) {
        Block clicked = blockAtObj(bx, by, bz);
        if (clicked != null && opensCraftingScreen(clicked)) {
            // A screen opening drops every key, which is the hazard guiOpened() exists for.
            guiOpened();
            openWindow = SimCraftWindow.bench(this);
            return true;
        }
        // vanilla places at pos.offset(face), so the face is what decides the cell.
        int tx = bx + (face == Face.EAST ? 1 : face == Face.WEST ? -1 : 0);
        int ty = by + (face == Face.UP ? 1 : face == Face.DOWN ? -1 : 0);
        int tz = bz + (face == Face.SOUTH ? 1 : face == Face.NORTH ? -1 : 0);
        if (blockAtObj(tx, ty, tz) != null) {
            return false;
        }
        ItemStack held = inventory.getCurrentItem();
        if (held == null || !(held.getItem() instanceof net.minecraft.item.ItemBlock)) {
            return false;
        }
        Block placed = ((net.minecraft.item.ItemBlock) held.getItem()).getBlock();
        grid.put(key(tx, ty, tz), placed);
        held.stackSize--;
        if (held.stackSize <= 0) {
            inventory.mainInventory[inventory.currentItem] = null;
        }
        return true;
    }

    @Override
    public boolean useItemInAir() {
        ItemStack held = inventory.getCurrentItem();
        // Vanilla's own gate, not an ItemFood test: EntityPlayer.setItemInUse starts a use for any
        // stack whose item declares a duration, which is food (32) and every indefinite item
        // (72000 -- a sword blocks, a bow draws). Testing the class instead of the duration made
        // every non-food use unreachable here, so the blocking path had nothing to block with and
        // ActActuator.blocking() could only ever be false.
        if (held == null || held.getMaxItemUseDuration() <= 0) {
            return false;
        }
        // A use is STARTED, which is a DIFFERENT question from whether the stack changed -- the
        // distinction ActActuator's own javadoc turns on, and the one that made
        // InteractController fail with "use rejected in air" on a use that had begun.
        startUse(held.copy());
        return true;
    }

    /** The player's own use-in-progress, i.e. {@code EntityPlayer.itemInUse}. */
    private ItemStack heldUse;

    private int useElapsed;

    void startUse(ItemStack s) {
        this.heldUse = s;
        this.useElapsed = 0;
    }

    /** Whether a use is in progress. */
    public boolean usingItem() {
        return heldUse != null;
    }

    /**
     * Ticks of use REMAINING, which is the direction vanilla's counter runs.
     *
     * <p>Remainder rather than elapsed because that is what {@code itemInUseCount} means
     * everywhere else in the act layer, and a controller that read it the other way round would
     * decide "the draw ran out" at the start of a bow and never fire it.
     */
    public int useTicksLeft() {
        if (heldUse == null) {
            return 0;
        }
        return heldUse.getMaxItemUseDuration() - useElapsed;
    }

    /**
     * Advance an in-progress use by one tick, as {@code EntityPlayer.onUpdate:286} does.
     *
     * <p>When the duration is reached the item is actually CONSUMED out of the hotbar and, for
     * food, the heal is applied -- {@code ItemFood.onItemUseFinish}. A use that runs to
     * completion is therefore visible as a fact about the inventory and the health bar, not as a
     * flag, which is the only way a task can tell a finished meal from a started one.
     *
     * @return the stack finished this tick, or null
     */
    public ItemStack advanceUse() {
        if (heldUse == null) {
            return null;
        }
        useElapsed++;
        if (useElapsed < heldUse.getMaxItemUseDuration()) {
            return null;
        }
        ItemStack finished = heldUse;
        heldUse = null;
        useElapsed = 0;
        // ItemFood.onItemUseFinish: the stack is consumed first, then the effect applies.
        int slot = inventory.currentItem;
        ItemStack inHand = inventory.mainInventory[slot];
        if (inHand != null && inHand.getItem() == finished.getItem()) {
            inHand.stackSize -= 1;
            if (inHand.stackSize <= 0) {
                inventory.mainInventory[slot] = null;
            }
        }
        if (finished.getItem() instanceof net.minecraft.item.ItemFood food) {
            survival.heal(food.getHealAmount(finished));
        }
        return finished;
    }


    @Override
    public boolean attackEntity(int id) {
        SimEntity e = entities.get(id);
        if (e == null || e.health <= 0.0D) {
            return false;
        }
        double[] eye = eyePos();
        double ey = e.y + EYE_HEIGHT;
        double dist = Math.sqrt((e.x - eye[0]) * (e.x - eye[0])
                + (ey - eye[1]) * (ey - eye[1])
                + (e.z - eye[2]) * (e.z - eye[2]));
        if (dist > reachDistance() + 1.0D) {
            return false;
        }
        e.hits++;
        e.health -= attackDamage();
        if (e.health <= 0.0D) {
            entities.remove(id);
        }
        return true;
    }

    /** The damage the held item lands, from the real attribute modifiers. */
    public double attackDamage() {
        ItemStack held = inventory.getCurrentItem();
        if (held == null) {
            return 1.0D; // EntityPlayer:193 — the bare-hand attackDamage base value.
        }
        if (held.getItem() instanceof net.minecraft.item.ItemSword sword) {
            return 4.0D + sword.getDamageVsEntity();
        }
        return 1.0D;
    }

    @Override
    public double[] position() {
        return inWorld ? new double[] {x, y, z} : null;
    }

    @Override
    public boolean onGround() {
        return onGround;
    }

    @Override
    public boolean collidedHorizontally() {
        return collidedHorizontally;
    }

    /**
     * {@code EntityLivingBase.isOnLadder:1134-1135}: the cell the player's feet are in, and one
     * test. Never inferred from {@link #onGround()}, which a ladder leaves false.
     */
    @Override
    public boolean onClimbable() {
        return isClimbable((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    /**
     * {@code Entity.fallDistance}, as {@link #moveWithCollision} maintains it. The same number the
     * live actuator returns, so {@code CritWindow} cannot be green here and wrong there.
     */
    @Override
    public double fallDistance() {
        return fallDistance;
    }

    /**
     * {@code EntityPlayer.isBlocking:257-260} with both halves: a use in progress AND the used
     * item's own use ACTION being BLOCK. The action is read from the real {@code Item} rather than
     * inferred from its class, so a shield and a sword block here for vanilla's reason and bread
     * does not.
     */
    @Override
    public boolean blocking() {
        return heldUse != null
                && heldUse.getItem().getItemUseAction(heldUse) == net.minecraft.item.EnumAction.BLOCK;
    }

    /** Whether the player's EYE cell is water, which is vanilla's {@code Entity.isInWater}. */
    @Override
    public boolean inWater() {
        return isWater((int) Math.floor(x), (int) Math.floor(y + EYE_HEIGHT), (int) Math.floor(z));
    }

    /**
     * Vanilla's air supply, the number a swim actually spends: 300 when out of water, one per
     * submerged tick ({@code EntityLivingBase:301}), and reset to 0 by a drown tick
     * ({@code :305}) rather than to 300.
     *
     * <p><b>This used to be a constant.</b> It returned {@code MAX_AIR_TICKS - submergedTicks}
     * and {@code submergedTicks} was never incremented by anything, so every caller in every task
     * read 300 forever. It now reads the ledger the damage rules use, so the same body that
     * drowns is the body that reports the air going down.
     *
     * <p>The shape mirrors {@code LivePlayerActuator.air()}, which returns {@code p.getAir()}
     * verbatim -- so like the live path this can return a negative value, and {@code -1} here
     * means "one tick into drowning" rather than "unreadable". That collision is
     * {@code SwimSteering}'s to own and is identical in the sim and on a live client; it is
     * called out here because a reader comparing the two accessors will notice it.
     */
    @Override
    public int air() {
        return survival.air();
    }

    @Override
    public void swing() {
        // Animation only; nothing in the act layer reads it back.
    }

    // ===== sustained use, as a controller sees it =====
    //
    // TWO SEPARATE STATES, and keeping them separate is the point. `useKeyDown` is what vanilla's
    // KeyBinding believes -- set by our assert, cleared by our release, and ALSO cleared by
    // anything else that un-presses keys (Minecraft.displayGuiScreen calls
    // KeyBinding.unPressAllKeys). `heldUse` is the item use itself, which only vanilla's own
    // item-right-click starts. A controller that conflated them could not tell "the GUI ate my
    // hold" from "the use finished", and those need opposite endings.

    private boolean useKeyDown;

    @Override
    public boolean holdUseKey() {
        useKeyDown = true;
        return true;
    }

    @Override
    public boolean releaseUseKey() {
        useKeyDown = false;
        if (heldUse != null) {
            // EntityPlayer.onStoppedUsingItem: an interrupted use gets NO consume and NO effect.
            // So a hold that ends early leaves the food in the bar and the health bar unchanged,
            // which is exactly the fact a task about a failed meal can check.
            heldUse = null;
            useElapsed = 0;
        }
        return true;
    }

    @Override
    public boolean useKeyHeld() {
        return useKeyDown;
    }

    @Override
    public boolean isUsingItem() {
        return heldUse != null;
    }

    @Override
    public int itemInUseCount() {
        return useTicksLeft();
    }

    @Override
    public int maxItemUseDuration() {
        return heldUse == null ? 0 : heldUse.getMaxItemUseDuration();
    }

    /**
     * Drop the use key WITHOUT clearing the use, modelling {@code KeyBinding.unPressAllKeys}.
     *
     * <p>This is the hazard {@link #holdUseKey}'s contract exists for: a GUI opening between two
     * ticks silently ends a hold that only ever asserted once. Nothing in the act layer can notice
     * it by looking at itself -- it has to read {@link #useKeyHeld()} at the top of the next tick
     * and find it false.
     */
    public void guiOpened() {
        useKeyDown = false;
    }

    @Override
    public void setHeldSlot(int slot) {
        inventory.currentItem = slot;
    }

    // ===== BlockView, for the planner =====

    @Override
    public boolean isSolid(int bx, int by, int bz) {
        return solid(blockAtObj(bx, by, bz));
    }

    @Override
    public int walkVerdict(int bx, int by, int bz) {
        Block b = blockAtObj(bx, by, bz);
        if (b == null) {
            return WALK_CLEAR;
        }
        if (b.getMaterial().isLiquid()) {
            return "lava".equals(nameOfBlock(b)) ? WALK_LAVA : WALK_WATER;
        }
        if (solid(b)) {
            return WALK_BLOCKED;
        }
        // A fence, wall or rail is a panel in the cell, not a body-sized block.
        return b instanceof net.minecraft.block.BlockFence
                || b instanceof net.minecraft.block.BlockWall
                || b instanceof net.minecraft.block.BlockFenceGate
                || b instanceof net.minecraft.block.BlockRail
                || b instanceof net.minecraft.block.BlockRailPowered
                ? WALK_FENCE : WALK_CLEAR;
    }

    @Override
    public boolean canPlaceAt(int bx, int by, int bz) {
        return blockAtObj(bx, by, bz) == null || blockAtObj(bx, by, bz).getMaterial().isReplaceable();
    }

    /**
     * {@code EntityLivingBase.isOnLadder:1134-1135}, verbatim: the player's own cell, one test.
     *
     * <p>Asked rather than derived, and deliberately NOT from {@link #isSolid}: a ladder has a
     * collision box and so reads solid, while its material blocks no movement and so reads clear.
     * One cell, two true answers, and the wrong question here routes the player into a ladder.
     */
    @Override
    public boolean isClimbable(int bx, int by, int bz) {
        Block b = blockAtObj(bx, by, bz);
        return b == net.minecraft.init.Blocks.ladder || b == net.minecraft.init.Blocks.vine;
    }

    /** Whether water occupies this cell, which is a different question from {@code WALK_WATER}. */
    @Override
    public boolean isWater(int bx, int by, int bz) {
        Block b = blockAtObj(bx, by, bz);
        return b == net.minecraft.init.Blocks.water || b == net.minecraft.init.Blocks.flowing_water;
    }

    /** {@code World.getBlockState(...).getBlock().getMaterial()}, off this world's own grid. */
    private net.minecraft.block.material.Material materialAt(int bx, int by, int bz) {
        Block b = blockAtObj(bx, by, bz);
        return b == null ? net.minecraft.block.material.Material.air : b.getMaterial();
    }

    /**
     * {@code World.isMaterialInBB:2136-2160}, verbatim, over the box {@code Entity.isInLava:1216-1219}
     * asks about: the body's own bounding box shrunk by {@code (-0.1, -0.4, -0.1)}.
     *
     * <p>The cell walk is vanilla's and its upper bound is {@code floor(max + 1.0)}, which scans
     * one cell past the box on the high side. That looks like an off-by-one and is not one: it is
     * the rule the server runs, and a body whose shoulder clips a lava cell takes the contact hit
     * there too. Approximating it as "the feet cell is lava" would let a body stand beside a band
     * unhurt, which is the exact failure this exists to remove.
     *
     * <p>Read off {@link #blockAtObj}, the grid this world already holds, so the per-tick hazard
     * test costs no read through the controller seam. See
     * {@code TheSurvivalDamageCostsNoWorldReadTest}.
     */
    private boolean materialInBodyBox(net.minecraft.block.material.Material wanted) {
        double halfWidth = WIDTH / 2.0D;
        double minX = x - halfWidth + SurvivalDamage.LAVA_SHRINK_XZ;
        double maxX = x + halfWidth + SurvivalDamage.LAVA_SHRINK_XZ;
        double minY = y + SurvivalDamage.LAVA_SHRINK_Y;
        double maxY = y + HEIGHT + SurvivalDamage.LAVA_SHRINK_Y;
        double minZ = z - halfWidth + SurvivalDamage.LAVA_SHRINK_XZ;
        double maxZ = z + halfWidth + SurvivalDamage.LAVA_SHRINK_XZ;
        for (int bx = (int) Math.floor(minX); bx < (int) Math.floor(maxX + 1.0D); bx++) {
            for (int by = (int) Math.floor(minY); by < (int) Math.floor(maxY + 1.0D); by++) {
                for (int bz = (int) Math.floor(minZ); bz < (int) Math.floor(maxZ + 1.0D); bz++) {
                    if (materialAt(bx, by, bz) == wanted) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * {@code Entity.isInLava:1216-1219}: any cell of the shrunk bounding box is lava.
     *
     * <p>Public because a task that asserts "the body never entered the lava" must be able to ask
     * the same question the damage rule asked, at the same instant. Reading {@code posTrace} for a
     * lava cell instead is a different question: the trace records the position AFTER the tick has
     * already moved and already hurt.
     */
    public boolean inLava() {
        return materialInBodyBox(net.minecraft.block.material.Material.lava);
    }

    /**
     * {@code EntityLivingBase:297}'s {@code isInsideOfMaterial(Material.water)}, over the same
     * shrunk box. This is what spends the air bar, and it is the whole body rather than the eye
     * cell {@link #inWater()} reports for the swim controller -- vanilla asks {@code inWater} for
     * steering and {@code isInsideOfMaterial} for breath, and they are not the same test.
     */
    public boolean bodyInWater() {
        return materialInBodyBox(net.minecraft.block.material.Material.water);
    }

    @Override
    public int blockBudget() {
        return 64;
    }

    /** The feet stance the player currently occupies. */
    public Stance stance() {
        return new Stance((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    // ===== facts for the report =====

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "SimWorld[pos=(%.2f, %.2f, %.2f) yaw=%.0f onGround=%s hp=%.1f inv=%s blocks=%d]",
                x, y, z, yaw, onGround, survival.health(), describeInventory(), grid.size());
    }

    /** Every non-empty inventory slot as "slot:item xcount", for a failure message. */
    public String describeInventory() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < inventory.mainInventory.length; i++) {
            ItemStack s = inventory.mainInventory[i];
            if (s == null || s.stackSize <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(i).append(':').append(nameOf(s.getItem())).append(" x").append(s.stackSize);
        }
        return sb.length() == 0 ? "(empty)" : sb.toString();
    }
}
