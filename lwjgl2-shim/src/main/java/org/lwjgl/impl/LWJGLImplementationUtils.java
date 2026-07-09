package org.lwjgl.impl;

import org.lwjgl.impl.glfw.GLFWKeyboardImplementation;
import org.lwjgl.impl.glfw.GLFWMouseImplementation;
import org.lwjgl.impl.input.CombinedInputImplementation;
import org.lwjgl.impl.input.InputImplementation;

/**
 * Lazy singleton holder for the process-wide input backend. Both the Keyboard
 * and Mouse facades resolve the same InputImplementation through here so they
 * share one set of GLFW callbacks on the single Display window.
 */
public final class LWJGLImplementationUtils {

    private static InputImplementation inputImplementation;

    private LWJGLImplementationUtils() {
    }

    /** Return the shared input backend, constructing it on first use. */
    public static synchronized InputImplementation getOrCreateInputImplementation() {
        if (inputImplementation == null) {
            inputImplementation = new CombinedInputImplementation(
                new GLFWKeyboardImplementation(),
                new GLFWMouseImplementation());
        }
        return inputImplementation;
    }
}