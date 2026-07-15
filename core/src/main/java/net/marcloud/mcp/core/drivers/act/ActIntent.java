package net.marcloud.mcp.core.drivers.act;

/**
 * Base of the sealed intent hierarchy — a single, immutable "what the AI wants a
 * slot to do". An intent carries only data (never live game references), so it
 * can be constructed and validated off the game thread and read back verbatim in
 * {@code act_status}. The controllers ({@link LookController}, {@link
 * DigController}, {@link InteractController}, {@link HotbarController}) turn an
 * intent into per-tick calls against an {@link ActActuator}.
 *
 * <p>Sealed so the three concrete intents are the whole set the runtime must
 * understand; a new action family is a new permitted subtype plus its controller.
 */
public sealed interface ActIntent
        permits MoveIntent, LookIntent, InteractIntent {

    /** The slot this intent occupies. */
    ActSlot slot();
}
