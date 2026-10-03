package net.marcloud.mcp.core.drivers.action;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import net.marcloud.mcp.core.drivers.act.ActRuntime;

/**
 * A test-tree door onto {@link ActTools}' package-private handlers.
 *
 * <p><b>Why this exists instead of widening {@code ActTools}.</b> The four act handlers are
 * package-private, and {@code ModelPolicy} needs to drive them verbatim -- same parser, same
 * refusals, same reply shape -- rather than reimplementing what {@code ActIntentParser} already
 * owns. The obvious fix is to make them public, and that is exactly the wrong fix: it would be a
 * PRODUCTION edit made for a test's convenience, on the class the live client talks through, in
 * a project whose standing rule is that a capability whose only reason to exist is a test does
 * not belong in the shipping tree.
 *
 * <p><b>So the access is borrowed instead of granted.</b> This class is declared in
 * {@code net.marcloud.mcp.core.drivers.action} but lives under {@code src/test}, which puts it in
 * the same package at compile time and lets it reach the package-private handlers without
 * changing one line of shipped code. Nothing here re-implements anything: each method returns the
 * handler object {@link ActTools} itself built, so the code under test is the production code and
 * a regression in the parser shows up here unchanged.
 *
 * <p><b>What it deliberately does not add.</b> No {@code world_view}, no {@code find_block}, no
 * craft executor. The point of the round is to find out what the SHIPPED surface can do, and a
 * helper that quietly widened it would answer a different question than the one asked.
 */
public final class ActToolsHeadless {

    private ActToolsHeadless() {
    }

    /** The shipped {@code act_set} handler, over {@code runtime}. */
    public static SyncToolSpecification actSet(ActRuntime runtime) {
        return new ActTools(runtime).actSet();
    }

    /** The shipped {@code act_status} handler, over {@code runtime}. */
    public static SyncToolSpecification actStatus(ActRuntime runtime) {
        return new ActTools(runtime).actStatus();
    }

    /** The shipped {@code act_cancel} handler, over {@code runtime}. */
    public static SyncToolSpecification actCancel(ActRuntime runtime) {
        return new ActTools(runtime).actCancel();
    }

    /** The shipped {@code act_plan} handler, over {@code runtime}. */
    public static SyncToolSpecification actPlan(ActRuntime runtime) {
        return new ActTools(runtime).actPlan();
    }
}
