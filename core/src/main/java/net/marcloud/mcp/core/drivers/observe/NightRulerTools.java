package net.marcloud.mcp.core.drivers.observe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import net.marcloud.mcp.core.eval.DawnChestRegion;
import net.marcloud.mcp.core.eval.NightEnclosure;
import net.marcloud.mcp.core.eval.NightHealth;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.http.Json;
import net.marcloud.mcp.core.se.Ring;

/**
 * The two north-star rulers that are not the shelter: {@code night_health} and {@code night_box}.
 *
 * <p><b>Why they are tools and not {@code world_view} sections.</b> The reason
 * {@link ShelterTools} gives, verbatim and for the same reason: a {@code world_view} section is a
 * SAMPLE read at the instant of the call, and {@code mode=diff} compares two samples of it. These
 * payloads are ledgers over 8,386 night ticks, maintained by the tick seam whether or not anyone
 * asked. Reading one costs a field read; putting it in a sample surface would have charged a poll
 * for it and made {@code diff} report the counter as changed on every call.
 *
 * <p><b>Why they are R3 and read no world.</b> Both payloads are integers and floats the game
 * thread already published. There is no {@code GameBridge.onGameThread} marshal here, so neither
 * inherits the one-tick minimum latency or the deliberately non-cancelling timeout
 * {@code KeGameDispatcher.awaitOrCancel:99-113} documents, and neither can stall the game thread at
 * all. {@link Ring#forBuiltin} supplies the declared ring from the table rather than a literal
 * here, so the gate and the registration cannot disagree.
 *
 * <p><b>Why the two are registered together in one file.</b> They share the write domain
 * ({@code McpCore.registerBuiltins}), share the gate ring, and share the one shape that matters:
 * {@code measuredOnce} checked before {@code measured}, with the two causes of an empty payload
 * named separately in {@code fact}. Two files would be two places to forget that.
 *
 * <p><b>What a model is supposed to DO with these, stated in the payload.</b> Neither says where to
 * build and neither forecasts damage; those are the model's calls and belong to the tools that
 * place blocks. What they remove is the reason those calls could not be made earlier: there was no
 * number to make them from.
 */
public final class NightRulerTools {

    private final NightHealth health;
    private final DawnChestRegion box;

    public NightRulerTools(NightHealth health, DawnChestRegion box) {
        this.health = health;
        this.box = box;
    }

    /** Register both rulers into the supervised registry. */
    public void registerAll(IoManager registry) {
        SyncToolSpecification h = nightHealth();
        Tool ht = h.tool();
        registry.register(ht.name(), h, null, ht.description(), true,
                Ring.forBuiltin(ht.name(), Ring.R3));
        SyncToolSpecification b = nightBox();
        Tool bt = b.tool();
        registry.register(bt.name(), b, null, bt.description(), true,
                Ring.forBuiltin(bt.name(), Ring.R3));
    }

    /** Package-private for direct handler testing, mirroring {@code ShelterTools.nightShelter}. */
    SyncToolSpecification nightHealth() {
        Tool tool = Tool.builder()
                .name("night_health")
                .title("Health floor so far this night")
                .description("Read-only: the LOWEST your health bar has been this night, and"
                        + " separately whether anything hit you. ONE NIGHT: the count starts empty"
                        + " at each dusk and covers that night alone. Calling this costs no world"
                        + " read — the numbers are maintained on every night tick by the kernel,"
                        + " so it is a field read and safe to call as often as you like.\n"
                        + "READ THESE IN THIS ORDER:\n"
                        + "1. measuredOnce — false means this payload has NEVER held a single"
                        + " measurement, so every other number is a placeholder and none of them"
                        + " is a reading. That includes floorHeld:false, which here means 'never"
                        + " looked', NEVER 'safe'.\n"
                        + "2. measured — false with measuredOnce true means the counter is alive"
                        + " and no NIGHT tick has been sampled yet, which in daylight is the normal"
                        + " case. Do not read it as a breach.\n"
                        + "3. floorHeld — the verdict. true means the bar never went below 18. It"
                        + " is decided by minHealth ALONE, on purpose: the claim is about the BAR."
                        + " minHealth:null means the bar was never read (a player being"
                        + " constructed reads 1.0 before the server has spoken), and is not 0.\n"
                        + "THE THREE NUMBERS ARE INDEPENDENT AND YOU NEED ALL THREE:\n"
                        + " minHealth — the low-water mark. Blind to damage the game's"
                        + " invulnerability guard REFUSES, because a refused hit never moves the"
                        + " bar.\n"
                        + " healthDrops — how many times the bar actually went DOWN. This is what"
                        + " distinguishes 'it really got low' from 'something was registered"
                        + " against you'.\n"
                        + " hitEdges — rising edges of the hurt flag: how many times the game"
                        + " registered a hit OR A KNOCKBACK against you (the same flag is set by"
                        + " both, so this is not proof of damage). Two hits landing on one tick"
                        + " read as one.\n"
                        + " absorbedHits — hitEdges minus healthDrops, never negative: the"
                        + " registered hits that did not move the bar. This is the number that"
                        + " makes 'I was hit and the game refused it' visible at all, and minHealth"
                        + " alone is blind to it. It is a DIFFERENCE between two series counted"
                        + " independently, not a tick-by-tick pairing, so read it as 'some"
                        + " registered hit did not move the bar', not as a diagnosed cause.\n"
                        + " maxLastDamage — the biggest drop the client was told about, or null"
                        + " when that field could not be read at all (lastDamageReadable says"
                        + " which). null is NOT zero: zero would claim nothing ever landed.\n"
                        + " samples is the RESOLUTION of the window: how many night ticks were"
                        + " actually looked at. Compare it against nightTicksTotal — while it is"
                        + " below that, part of this night was never observed, and a floor held"
                        + " over fewer ticks than the night has is a weaker claim than it looks.\n"
                        + " floorHeld:true with hitEdges above 0 is NOT a quiet night — it means"
                        + " you were hit and it did not get through. Compare absorbedHits against"
                        + " healthDrops to see which happened before you decide to fight or"
                        + " hide. Other fields: worldTime, samplingPeriodTicks, nightTicksTotal,"
                        + " firstBelowFloorTick, firstHitTick, firstNightTick, lastNightTick,"
                        + " belowFloorNow, fact.")
                .inputSchema(Map.of("type", "object", "properties", Map.of(), "required", List.of()))
                .annotations(ToolAnnotations.builder()
                        .title("Health floor so far this night")
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("measuredOnce", health.measuredOnce());
            out.put("measured", health.measured());
            out.put("floorHeld", health.floorHeld());
            out.put("northStarFloor", NightHealth.NORTH_STAR_FLOOR);
            out.put("minHealth", boxed(health.minHealth()));
            out.put("healthDrops", health.healthDrops());
            out.put("hitEdges", health.hitEdges());
            out.put("absorbedHits", health.absorbedHits());
            out.put("maxLastDamage", boxed(health.maxLastDamage()));
            out.put("lastDamageReadable", health.lastDamageReadable());
            out.put("firstBelowFloorTick", health.firstBelowFloorTick());
            out.put("firstHitTick", health.firstHitTick());
            out.put("belowFloorNow", health.belowFloorNow());
            out.put("worldTime", health.worldTime());
            out.put("samples", health.samples());
            out.put("nightTicksTotal", NightEnclosure.nightTicks());
            out.put("samplingPeriodTicks", health.periodTicks());
            out.put("fact", health.fact());
            return CallToolResult.builder().addTextContent(Json.write(out)).isError(false).build();
        });
    }

    /** {@code null} for NaN, so the JSON carries a JSON null rather than a bare NaN token. */
    private static Object boxed(float v) {
        return Float.isNaN(v) ? null : Float.valueOf(v);
    }

    /** Package-private for direct handler testing. */
    SyncToolSpecification nightBox() {
        Tool tool = Tool.builder()
                .name("night_box")
                .title("Chest standing at dawn, region reading")
                .description("Read-only: whether a CHEST stood through this night, measured over a"
                        + " REGION around you rather than at one named cell — so nothing has to be"
                        + " nominated and no cell has to be remembered. ONE NIGHT. Calling this"
                        + " costs no world read at call time; the kernel censuses the region and"
                        + " re-reads the chests it found on every night tick.\n"
                        + "READ THESE IN THIS ORDER:\n"
                        + "1. measuredOnce — false means this payload has NEVER held a single"
                        + " measurement, so every other number is a placeholder and none of them"
                        + " is a reading.\n"
                        + "2. measured — false with measuredOnce true means no NIGHT tick has been"
                        + " sampled yet, which is normal in daylight.\n"
                        + "3. chestsSeen — ZERO means a census ran and found NO chest at all. That"
                        + " is NOT MEASURED, not a failed box: the row is about a box that was"
                        + " never seen, and you should read it that way rather than concluding a"
                        + " chest was destroyed.\n"
                        + "4. standingAtDawn and heldThroughout — the two readings. The POINT one is"
                        + " every chest found holding on the last sampled tick. The REGION one is"
                        + " every chest found holding on EVERY tick since it was censused, and it"
                        + " is the one to quote: a box that appeared one tick before dawn is not a"
                        + " box that stood through a night. If they disagree, the region one is the"
                        + " honest claim.\n"
                        + "firstSeenTick is when the OLDEST chest in the ledger appeared and"
                        + " latestFirstSeenTick when the newest did. The region claim is only as old"
                        + " as the oldest of them, so compare firstSeenTick against firstNightTick:"
                        + " a chest censused at 3am has only been judged since 3am.\n"
                        + "WHAT THIS IS NOT, in the order it is most likely to be misread:\n"
                        + " - It is a REGION reading. It says A chest stood through this night, NOT"
                        + " that the chest YOU built did. A world that already held a chest"
                        + " satisfies it.\n"
                        + " - CONTENTS are not measured AT ALL. The 1.8.9 protocol layer"
                        + " recognises only six tile-entity types and TileEntityChest is not one of"
                        + " them, so a closed chest's inventory is not on this client. There is no"
                        + " field here that could tell you what is inside.\n"
                        + " - On a LIVE client mobs, creepers, explosions and lava ARE present."
                        + " Nothing in this row rules any of them out.\n"
                        + " - The server's change to a block reaches the client a few ticks late,"
                        + " so 'throughout' also includes 'has just now gone'.\n"
                        + " - A cell that left load range reads as no chest, and that is"
                        + " indistinguishable from one that was demolished. Those are counted"
                        + " separately: unreadableSamples is a read that could not be made,"
                        + " missingSamples is a cell read back as definitely some other block. A"
                        + " heldThroughout:false with unreadableSamples above 0 and missingSamples"
                        + " at 0 failed on a READ, not on a demolition — read it that way.\n"
                        + "Other fields: samples, censuses, censusRadiusCells, firstAbsentTick,"
                        + " worldTime, samplingPeriodTicks, censusPeriodTicks, fact.")
                .inputSchema(Map.of("type", "object", "properties", Map.of(), "required", List.of()))
                .annotations(ToolAnnotations.builder()
                        .title("Chest standing at dawn, region reading")
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("measuredOnce", box.measuredOnce());
            out.put("measured", box.measured());
            out.put("chestsSeen", box.chestsSeen());
            out.put("standingAtDawn", box.standingAtDawn());
            out.put("someStandingAtDawn", box.someStandingAtDawn());
            out.put("heldThroughout", box.heldThroughout());
            out.put("firstSeenTick", box.firstSeenTick());
            out.put("latestFirstSeenTick", box.latestFirstSeenTick());
            out.put("missingSamples", box.missingSamples());
            out.put("unreadableSamples", box.unreadableSamples());
            out.put("firstAbsentTick", box.firstAbsentTick());
            out.put("samples", box.samples());
            out.put("censuses", box.censuses());
            out.put("censusRadiusCells", box.radius());
            out.put("censusPeriodTicks", box.censusPeriodTicks());
            out.put("worldTime", box.worldTime());
            out.put("nightTicksTotal", NightEnclosure.nightTicks());
            out.put("samplingPeriodTicks", box.periodTicks());
            out.put("fact", box.fact());
            return CallToolResult.builder().addTextContent(Json.write(out)).isError(false).build();
        });
    }
}