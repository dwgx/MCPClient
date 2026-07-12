package net.marcloud.pg;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type (or method) for build-time hardening by PatchGuard.
 *
 * <p>Business code writes only this annotation; the {@code pg-maven-plugin} scans
 * for it after compile and applies the {@link Level}-selected hardening passes to
 * the emitted {@code .class} bytecode. There is zero runtime behavior here and
 * zero source pollution beyond the annotation itself.
 *
 * <p>Retention is {@link RetentionPolicy#CLASS}: the marker must survive into the
 * {@code .class} file so the build-time scanner can read it from bytecode, but it
 * is not needed at runtime (the hardening is already baked in by then). It is not
 * {@code RUNTIME} on purpose — leaving a runtime-visible "this class is hardened"
 * marker would hand a reverse engineer a free index of exactly which classes to
 * look at.
 *
 * <p>Applying {@code @Guarded} to a type hardens the whole type; applying it to a
 * method narrows hardening to that method (passes that support method-level
 * granularity honor it; type-wide passes treat any annotated member as
 * "harden this type").
 *
 * <p>Honest boundary: hardening buys time against reverse engineering on a machine
 * the attacker controls; it is not an information-theoretic wall. See the pg design
 * doc. The value is raising per-session and per-copy analysis cost, paired with a
 * fast release cadence.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.CLASS)
public @interface Guarded {

    /**
     * How aggressively to harden. Higher levels apply strictly more passes, so a
     * level is a superset of the one below it. The engine maps each level to a set
     * of {@code HardenPass}es; adding a new pass is a change in the engine, not in
     * this contract, so business annotations never churn.
     *
     * @return the hardening level (default {@link Level#STANDARD})
     */
    Level value() default Level.STANDARD;

    /**
     * The strength tiers. Ordered from cheapest/lightest to strongest/heaviest so
     * callers can reason about cost, and the engine can select "this level and
     * everything below it".
     */
    enum Level {
        /**
         * Metadata and constant hardening only: decompiler-poisoning and
         * string/constant obfuscation. Zero runtime overhead, low risk. The
         * baseline every {@code @Guarded} type gets.
         */
        STANDARD,

        /**
         * STANDARD plus control-flow hardening (flattening, opaque predicates)
         * on the annotated members. Small runtime overhead.
         */
        FLOW,

        /**
         * FLOW plus virtualization of the annotated members into the custom ISA
         * interpreter (kernel-as-data). Highest cost and highest resistance;
         * reserve for the smallest, coldest, highest-value logic.
         */
        VIRTUALIZE
    }
}
