package net.marcloud.mcp.core.drivers.observe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import net.marcloud.mcp.core.eval.NightEnclosure;
import net.marcloud.mcp.core.eval.NightShelter;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.http.Json;
import net.marcloud.mcp.core.se.Ring;

/**
 * {@code night_shelter} — the one verb that answers "how is the night going", read from a ledger
 * the tick seam has been filling whether or not anybody asked.
 *
 * <p><b>Why this is a tool and not a {@code world_view} section.</b> Two reasons, and the second
 * is the one that matters. First, {@code world_view} captures a SAMPLE: every section is read at
 * the instant of the call, and asking for one costs what asking for any other costs. This payload
 * is a ledger over 8,386 ticks, and reading it costs nothing but a field read — the world was
 * already read, once per night tick, by the accumulator. Second, {@code mode=diff} compares two
 * samples of the same section, and a monotone counter differs on every single call; a diff view
 * that reports "the shelter changed" 20 times a second is noise wearing a change-detection
 * costume. A ledger is not a sample and putting it in a sample surface would have mis-fitted it
 * twice.
 *
 * <p><b>Why the tool call is R3 and reads no world.</b> It reads integers the game thread already
 * published. There is no {@code GameBridge.onGameThread} marshal here, so it does not inherit the
 * one-tick minimum latency or the deliberately non-cancelling timeout that
 * {@code KeGameDispatcher.awaitOrCancel:99-113} documents, and it cannot stall the game thread at
 * all. {@link Ring#forBuiltin} supplies the declared ring from the table rather than a literal
 * here, so the gate and the registration cannot disagree.
 *
 * <p><b>The decision this is meant to inform, stated in the payload rather than in prose.</b>
 * {@code openSamples} is monotone WITHIN a night: once it is above zero,
 * {@code shelteredSoFar} can never be true again for this night, and nothing the body builds
 * afterwards changes that. So the number splits the night into two situations with different
 * correct behaviour, and the split is visible while the night is still running:
 *
 * <ul>
 *   <li>{@code measuredOnce} false — this payload has never held a single measurement, so every
 *       other key is a placeholder. This is checked before {@code measured}, because
 *       {@code measured} is also false in daylight and the two demand opposite readings.</li>
 *   <li>{@code measured} false with {@code measuredOnce} true — the counter is alive and no night
 *       tick has been sampled yet, which in daylight is the ordinary case. {@code intoNight}
 *       being 0 is what says so. Do not read this as a shelter.</li>
 *   <li>{@code openSamples == 0} — every night tick so far was enclosed and the claim is still
 *       winnable. {@code nightTicksTotal - samples} is the budget left to keep it that way, and
 *       it is never negative: {@code samples} counts ONE night (see below).</li>
 *   <li>{@code openSamples > 0} — the night's "enclosed throughout" answer is already spent.
 *       {@code longestUnreachableTicks} still moves and is the number that is still worth
 *       shortening; the remaining night is a survival question, not a shelter one.</li>
 * </ul>
 *
 * <p><b>The window is ONE night, and saying so is load-bearing rather than tidy.</b> The ledger
 * starts empty at each dusk and holds exactly that night. It used to hold every night since the
 * client connected, which made the arithmetic above go negative on the second night —
 * {@code 8386 - 16772} — and handed a model deciding whether to build a roof a budget below
 * zero. {@link NightEnclosure} is where that was fixed; the description is what has to keep
 * saying so, because a description that over-promises is the defect arriving by another road.
 * Two things follow for a reader, and both are stated in the payload's own words below: a night
 * that went badly stops counting when the next one starts, and {@code samples} is this night's
 * count rather than a running total, which is why it can be compared against
 * {@code nightTicksTotal} at all.
 *
 * <p><b>What it does not do.</b> It does not say WHERE to build, it does not forecast damage, and
 * it does not promise that enclosing the body is worth the ticks — that judgement is the model's
 * and belongs to the tools that place blocks. What it removes is the reason that judgement could
 * not be made earlier: there was no number to make it from.
 */
public final class ShelterTools {

    private final NightShelter shelter;

    public ShelterTools(NightShelter shelter) {
        this.shelter = shelter;
    }

    /** Register {@code night_shelter} into the supervised registry. */
    public void registerAll(IoManager registry) {
        SyncToolSpecification spec = nightShelter();
        Tool t = spec.tool();
        registry.register(t.name(), spec, null, t.description(), true,
                Ring.forBuiltin(t.name(), Ring.R3));
    }

    /** Package-private for direct handler testing, mirroring {@code ObserveTools.packetsTail}. */
    SyncToolSpecification nightShelter() {
        Tool tool = Tool.builder()
                .name("night_shelter")
                .title("Night shelter so far")
                .description("Read-only: how the CURRENT night has gone, tick by tick, for the body"
                        + " you are playing. ONE NIGHT, and only one: the count starts empty at each"
                        + " dusk and covers that night alone, so what happened last night is not in"
                        + " it. Read it while the night is happening; in daylight it still holds the"
                        + " night that just ended, which is the last chance to read it. Nothing here"
                        + " costs a world read: the count is maintained on every night tick by the"
                        + " kernel, so calling this is a field read and is safe to call as often as"
                        + " you like.\n"
                        + "FIELDS: measuredOnce (false = this payload has NEVER held a single"
                        + " measurement, so every other number here is a placeholder and none of"
                        + " them is a reading — exposedNow:false included: that is 'not looked at',"
                        + " never 'safe'; measured (false = no NIGHT tick has been sampled yet,"
                        + " which is normal in daylight; do NOT read a false as a shelter — 'never"
                        + " looked' and 'safe' are different answers),"
                        + " nightTicksTotal (how many night ticks one night has), intoNight (night"
                        + " ticks that have happened so far this night, from the clock), samples"
                        + " (night ticks of THIS night actually looked at, never more than"
                        + " nightTicksTotal; on a run that observed every night tick the two are"
                        + " EQUAL, and samples < intoNight means the counter was not attached for"
                        + " the whole night), openSamples (night ticks the body"
                        + " could be reached"
                        + " from), enclosedSamples, longestUnreachableTicks (the longest unbroken"
                        + " run of those, in ticks), firstOpenTick (the clock tick the first one"
                        + " happened, -1 if none), exposedNow (whether the most recent SAMPLE"
                        + " found the body reachable; at the default samplingPeriodTicks of 1"
                        + " that is this very tick, and if that number were ever larger the"
                        + " answer could be up to samplingPeriodTicks-1 ticks old — so read"
                        + " measuredOnce and sampledLastTick alongside it: measuredOnce false"
                        + " means nothing has ever been observed and sampledLastTick false means"
                        + " this tick was not looked at; neither of them is the body being safe),"
                        + " shelteredSoFar, worldTime, samplingPeriodTicks, fact (one sentence saying"
                        + " what this row is, and while measuredOnce is false it names WHICH of the"
                        + " two causes it is: no tick ever reached the counter, or ticks are arriving"
                        + " with no world to measure).\n"
                        + "READ IT LIKE THIS: openSamples only ever goes UP within a night, so the"
                        + " moment it is above zero this night's 'enclosed throughout' answer is"
                        + " already spent and no roof built afterwards will change it — from then"
                        + " on longestUnreachableTicks is the number still worth shortening and the"
                        + " rest of the night is a survival question. While openSamples is 0 the"
                        + " claim is still winnable and (nightTicksTotal - samples) is the budget"
                        + " left to keep it that way; it counts down and never goes below zero."
                        + " This tells you whether and how urgent; it"
                        + " does not tell you where to build — world_view and find_block are for"
                        + " that.")
                .inputSchema(Map.of("type", "object", "properties", Map.of(), "required", List.of()))
                .annotations(ToolAnnotations.builder()
                        .title("Night shelter so far")
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("measuredOnce", shelter.measuredOnce());
            out.put("measured", shelter.measured());
            out.put("nightTicksTotal", NightEnclosure.nightTicks());
            out.put("intoNight", shelter.intoNight());
            out.put("samples", shelter.samples());
            out.put("openSamples", shelter.openSamples());
            out.put("enclosedSamples", shelter.enclosedSamples());
            out.put("longestUnreachableTicks", shelter.longestUnreachableTicks());
            out.put("firstOpenTick", shelter.firstOpenTick());
            out.put("firstNightTick", shelter.firstNightTick());
            out.put("lastNightTick", shelter.lastNightTick());
            out.put("sampledLastTick", shelter.sampledLastTick());
            out.put("exposedNow", shelter.exposedNow());
            out.put("shelteredSoFar", shelter.sheltered());
            out.put("worldTime", shelter.worldTime());
            out.put("samplingPeriodTicks", shelter.periodTicks());
            out.put("fact", shelter.fact());
            return CallToolResult.builder().addTextContent(Json.write(out)).isError(false).build();
        });
    }
}