package net.marcloud.mcp.dwm.theme;

/**
 * The MD3 token source of truth — pure data + math, NO drawing and NO backend
 * type. Components pull color roles / shape / typography / motion tokens from here
 * so a theme swap (light/dark/custom seed) never touches component code.
 *
 * <p>This is a skeleton contract; the concrete implementation derives a full tonal
 * palette from a single seed color (the MD3 dynamic-color algorithm, e.g. via the
 * material-color-utilities math) and exposes the color roles MD3 defines.
 */
public interface MdcTheme {

    /** A resolved MD3 color role, packed ARGB (0xAARRGGBB). */
    int color(ColorRole role);

    /** Corner radius token in DIP for a shape size. */
    float corner(ShapeSize size);

    /** Type scale size in DIP for a typography role. */
    float typeSizePx(TypeRole role);

    /** Whether this theme is the dark variant. */
    boolean dark();

    /** MD3 color roles (subset for the skeleton; extend as components need). */
    enum ColorRole {
        PRIMARY, ON_PRIMARY, PRIMARY_CONTAINER, ON_PRIMARY_CONTAINER,
        SECONDARY, ON_SECONDARY,
        SURFACE, ON_SURFACE, SURFACE_VARIANT, ON_SURFACE_VARIANT,
        OUTLINE, OUTLINE_VARIANT,
        ERROR, ON_ERROR
    }

    /** MD3 shape scale. */
    enum ShapeSize { NONE, EXTRA_SMALL, SMALL, MEDIUM, LARGE, EXTRA_LARGE, FULL }

    /** MD3 type scale (subset). */
    enum TypeRole { DISPLAY_LARGE, HEADLINE_MEDIUM, TITLE_MEDIUM, LABEL_LARGE, BODY_MEDIUM }
}
