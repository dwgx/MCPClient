package net.marcloud.mcp.core.flt;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Custom Byte Buddy mapping annotation for binding a routeKey constant into
 * {@link GenericEntryAdvice}. The {@code FltDynamicManager} uses
 * {@code Advice.withCustomMapping().bind(HookId.class, routeKey)} to carry the
 * per-hook identifier into the inlined advice body without requiring a field in
 * the target class.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface HookId {
}
