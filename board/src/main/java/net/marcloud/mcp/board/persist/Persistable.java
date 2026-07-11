package net.marcloud.mcp.board.persist;

/**
 * A value that knows how to (de)serialize ITSELF. The persistence engine
 * ({@link Store}) never switches on concrete type — it just hands each registered
 * value a {@link DataView} and lets the value read/write its own fields. This
 * mirrors the "every system serializes itself" pattern (Meteor's {@code System},
 * Faiths' per-type codecs): the engine owns the file, the envelope, atomicity and
 * corruption recovery; the value owns its field layout.
 *
 * <p>Contract for implementors:
 * <ul>
 *   <li>{@link #save(DataView)} writes the value's fields into {@code out}. Only
 *       stable field keys — NEVER a display name — since keys are the on-disk
 *       schema.</li>
 *   <li>{@link #load(DataView)} reads fields back, TOLERANTLY: a missing key must
 *       leave that field at its default (use {@code getX(key, default)}), and an
 *       unknown key in {@code in} must simply be ignored. This is what makes old
 *       and future envelopes forward/backward compatible.</li>
 *   <li>{@link #reset()} restores every field to its default. The engine calls
 *       this before every load (reset-before-load) so fields absent from the file
 *       are left at defaults rather than stale values.</li>
 * </ul>
 *
 * <p>An implementation must never throw from these methods for ordinary missing
 * or mis-typed data — {@link DataView} already coerces and defaults. The engine
 * still fault-isolates each call, but a well-behaved value tolerates any input.
 */
public interface Persistable {

    /** Write this value's fields into {@code out} using stable field keys. */
    void save(DataView out);

    /** Read this value's fields from {@code in} tolerantly (missing → default). */
    void load(DataView in);

    /** Restore every field to its default. Called before each load. */
    void reset();
}
