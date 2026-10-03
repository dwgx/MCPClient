package net.marcloud.mcp.core.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.marcloud.mcp.core.drivers.act.ActSlot;
import net.marcloud.mcp.core.drivers.plan.Stance;

/**
 * The STAND-IN OBSERVATION: everything a model is shown about the world, rendered from readings
 * {@link SimWorld} can actually take, and nothing else.
 *
 * <p><b>The name is the point.</b> This is not {@code world_view}, it is not
 * {@code scan_surroundings}, and a result obtained through it may never be written up as "the
 * north star is met". The reason is structural and is not fixable from this side:
 * {@code WorldViewCapture.capture} takes a {@code GameAccess} and
 * {@code LocalGrid.sampleColumnar} takes a {@code WorldClient}, so on a harness with no live
 * client BOTH of those tools are un-drivable. That is why {@code SimWorld:110} carries a
 * {@code KNOWN_GAPS} note saying the same thing. So the model gets a substitute, the substitute
 * is strictly weaker than the real thing, and the gap is enumerated in
 * {@link #SEES} and {@link #CANNOT_SEE} rather than left for a reader to discover.
 *
 * <p><b>Why that matters more than it looks.</b> This repository has already produced seven
 * instances of "a capability is claimed and its producer is not there" -- {@code craft_plan}'s
 * description once advertised a seam that was never built. {@code guarantees.md} §6.16 names the
 * failure mode: <i>a claim about an absence is the hardest kind of rot to notice</i>, because the
 * sentence that is wrong is the one about something nobody checked. So the one thing this class
 * must not do is let a substitute observation be reported as the real observation.
 * {@link #THE_BOUNDARY} exists to be quoted verbatim into the artifact.
 *
 * <p><b>Every reading here is a SimWorld getter, and they are counted.</b> The 5x5 local cross
 * section goes through {@link SimWorld#blockAt}, which is the controller seam and increments
 * {@code seamReads}; the enclosure line goes through {@link SimWorld#enclosureGrid()}. Nothing
 * calls into a controller, opens a file, or reaches for a {@code World}. A model told to "look
 * around" here sees 25 cells, which is a strictly smaller world than the radius-16 scan a real
 * client gives it, and the run must be read knowing that.
 *
 * <p><b>And what it does NOT do is hide the limits from the model.</b> The header line states
 * that this is a substitute view. A model that is told its eyes are 5 blocks wide will make
 * different plans than one that believes it can see a forest, and pretending otherwise would
 * flatter the result.
 */
public final class StandInObservation {

    /** Half-width of the local cross section, in blocks, on each horizontal axis. */
    public static final int RADIUS = 2;

    /**
     * The honest-boundary paragraph, written once so the artifact and the failure message cannot
     * drift apart. Quoted verbatim into the report; {@link #cannotSee()} appends the enumerated
     * gaps to it.
     */
    public static final String THE_BOUNDARY =
            "STAND-IN OBSERVATION, NOT world_view. world_view, scan_surroundings and find_block all"
                    + " take a live GameAccess / WorldClient and cannot be driven on a harness with"
                    + " no client attached, so this run shows the model a substitute rendered from"
                    + " SimWorld getters only. A round won through this view is evidence about the"
                    + " MODEL'S DECISION QUALITY, and is NOT evidence that the north star was met.";

    /** What the substitute does show, enumerated so a reader does not have to diff two texts. */
    public static final List<String> SEES = List.of(
            "the player's own position, eye height, yaw, on-ground flag",
            "health, the lowest health this run has held, air, alive/dead",
            "worldTime, and night/day read through the PRODUCTION Daylight curve",
            "ticksUntilDawn",
            "the full inventory, slot by slot, with counts and the held slot",
            "a 5x5x2 local cross section (radius " + RADIUS + ") at feet and head level, by"
                    + " block registry name",
            "night_shelter's REAL production payload: NightShelter and NightEnclosure are"
                    + " main-tree and read no game state, so the ledger the model sees here is"
                    + " the shipped instrument, only fed by this harness's night loop instead of"
                    + " the EventBus tick subscriber a live client attaches",
            "act_status / act_set / act_cancel / act_plan replies, verbatim, from the PRODUCTION"
                    + " ActTools handlers over the production ActRuntime");

    /** What it does not show, and what a real world_view would have added. */
    public static final List<String> CANNOT_SEE = List.of(
            "anything more than " + (2 * RADIUS + 1) + " blocks away in any direction -- the trees"
                    + " that hold the logs are outside the window, so the model is told they exist"
                    + " only if the prompt says so and otherwise cannot find them",
            "chunk loading state, so a cell that simply was never loaded reads the same as air",
            "light levels, mobs, weather, other players, entities",
            "the block drop table, so 'what does this block become when broken' must be guessed"
                    + " rather than read",
            "no recipe tables, so 'what do I make this from' must be asked of craft_plan rather"
                    + " than read off a block",
            "world_view's SPARSE/PROFILE rendering, its chunk map, and find_block's nearest-block"
                    + " search, all three of which are the difference between 'I can see a forest'"
                    + " and 'a forest exists somewhere'");

    private StandInObservation() {
    }

    /**
     * The whole substitute view for one turn, as the string the model is shown.
     *
     * <p>Deterministic in the world: no timestamps, no run ids, nothing that would make two runs
     * over the same world differ. That is what lets the regression test pin an exact substring.
     *
     * <p>{@code shelter} is the production {@link NightShelter} accumulator, driven by this
     * harness's own night loop rather than by the {@code EventBus} tick subscriber a live client
     * uses. Same class, same field names, so the model reads the shipped payload and not a
     * lookalike.
     */
    public static String render(SimWorld w, NightShelter shelter, String lastToolReply,
                                String lastToolName,
                                java.util.function.Function<ActSlot, String> phaseOf) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append(THE_BOUNDARY).append('\n');

        long t = w.worldTime();
        sb.append("\n-- CLOCK --\n")
                .append("worldTime=").append(t)
                .append("  night=").append(w.isNight())
                .append("  ticksUntilDawn=").append(w.ticksUntilDawn())
                .append('\n');

        Stance f = w.stance();
        sb.append("\n-- YOU --\n")
                .append(String.format(Locale.ROOT,
                        "feet=(%d,%d,%d) pos=(%.2f,%.2f,%.2f) eyeY=%.2f yaw=%.1f onGround=%s%n",
                        f.x(), f.y(), f.z(), w.posX(), w.posY(), w.posZ(), w.eyeY(), w.yaw(),
                        w.onGround()))
                .append("health=").append(fmt(w.health()))
                .append("  lowestSoFar=").append(fmt(w.minimumHealth()))
                .append("  air=").append(w.air())
                .append("  alive=").append(w.alive())
                .append('\n');

        // The production shelter accumulator, field for field with what ShelterTools emits for
        // night_shelter. Same class, same names, so the model reads the real payload.
        sb.append("\n-- night_shelter (PRODUCTION NightShelter; fed by this harness's night")
                .append(" loop, not by the live tick subscriber) --\n")
                .append("measured=").append(shelter.measured())
                .append(" nightTicksTotal=").append(NightEnclosure.nightTicks())
                .append(" intoNight=").append(shelter.intoNight())
                .append(" samples=").append(shelter.samples())
                .append(" openSamples=").append(shelter.openSamples())
                .append(" enclosedSamples=").append(shelter.enclosedSamples())
                .append(" longestUnreachableTicks=").append(shelter.longestUnreachableTicks())
                .append(" firstOpenTick=").append(shelter.firstOpenTick())
                .append(" firstNightTick=").append(shelter.firstNightTick())
                .append(" lastNightTick=").append(shelter.lastNightTick())
                .append(" sampledLastTick=").append(shelter.sampledLastTick())
                .append(" exposedNow=").append(shelter.exposedNow())
                .append(" shelteredSoFar=").append(shelter.sheltered())
                .append(" samplingPeriodTicks=").append(shelter.periodTicks())
                .append('\n');

        // The same question Enclosure answers for the box cell, asked of the PLAYER, so the model
        // can see whether it has walls. Production class, production grid.
        Enclosure.Verdict enc = Enclosure.at(w.enclosureGrid(), f.x(), f.y(), f.z());
        sb.append("enclosureAtYourFeet=").append(enc.describe())
                .append("  enclosed=").append(enc.enclosed())
                .append('\n');

        sb.append("\n-- INVENTORY --\n");
        String inv = describeInventory(w);
        sb.append(inv.isEmpty() ? "(empty)" : inv).append('\n');

        sb.append("\n-- WHAT IS WITHIN ").append(2 * RADIUS + 1).append(" BLOCKS OF YOU --\n");
        // THREE layers, and the floor is one of them. The first version drew feet and head only,
        // and on a flat plain that is two layers of AIR with the dirt at y-1 never drawn -- so the
        // model was looking at an empty void standing on nothing and could not see the ground it
        // was standing on, let alone anything built on it. Found by running the round: the first
        // transcript was five rows of '.' at both levels. A cross-section that omits the floor is
        // not a small gap, it is the gap.
        for (int layer = -1; layer <= 1; layer++) {
            int y = f.y() + layer;
            sb.append("  y=").append(y)
                    .append(layer == -1 ? " (the floor you stand on)\n"
                            : layer == 0 ? " (feet level)\n" : " (head level)\n");
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                sb.append(String.format(Locale.ROOT, "    z=%+3d  ", dz + f.z()));
                for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                    int bx = f.x() + dx;
                    int bz = f.z() + dz;
                    if (dx == 0 && dz == 0) {
                        sb.append(pad("YOU"));
                    } else {
                        String name = w.blockAt(bx, y, bz);
                        sb.append(pad(name == null ? "." : name));
                    }
                }
                sb.append('\n');
            }
        }
        sb.append("  (a name is a vanilla registry name; '.' is empty space. The column axis is x")
                .append(" running left to right.\n");

        if (lastToolReply != null) {
            sb.append("\n-- REPLY OF YOUR LAST CALL (")
                    .append(lastToolName)
                    .append(") --\n").append(lastToolReply).append('\n');
        }

        // The slot phases, read from the runtime rather than printed from the enum. The first
        // version appended `slot` itself, which printed "MOVE: MOVE" -- a line that looks like a
        // reading and says nothing at all, on the same footing as a wrong reading but harder to
        // catch because it is not obviously false.
        sb.append("\n-- SLOT PHASES --\n");
        for (ActSlot slot : ActSlot.values()) {
            sb.append("  ").append(slot.name()).append(": ")
                    .append(phaseOf == null ? "(unavailable)" : phaseOf.apply(slot)).append('\n');
        }
        return sb.toString();
    }

    /** The substitution, verbatim, for the artifact's honest section. */
    public static String cannotSee() {
        StringBuilder sb = new StringBuilder(THE_BOUNDARY).append("\n\nIT DID NOT SEE:\n");
        for (String gap : CANNOT_SEE) {
            sb.append("  - ").append(gap).append('\n');
        }
        sb.append("\nWHAT A REAL world_view WOULD HAVE ADDED:\n");
        sb.append("  - a nearest-block search over the loaded chunks, so 'the logs are 12 blocks")
                .append(" north-east' is a READ rather than a guess\n");
        sb.append("  - a light-level reading, which is what tells a model night has actually")
                .append(" fallen rather than what the clock says\n");
        sb.append("  - mobs and their positions, so 'something is coming' is an observation\n");
        sb.append("  - the block at an arbitrary far coordinate, so a model can plan a route")
                .append(" before it walks it\n");
        sb.append("  - the drop table for a block, so 'digging this yields X' is a read\n");
        return sb.toString();
    }

    private static String describeInventory(SimWorld w) {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            String name = w.slotName(i);
            if (name == null) {
                continue;
            }
            rows.add("slot " + i + (i == w.heldSlot() ? " (HELD)" : "") + ": "
                    + w.slotCount(i) + " x " + name);
        }
        return String.join("\n", rows);
    }

    private static String pad(String s) {
        StringBuilder b = new StringBuilder(" [");
        b.append(s);
        while (b.length() < 7) {
            b.append(' ');
        }
        return b.append(']').toString();
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }
}
