package org.lwjgl.impl.input;

/**
 * Combined input SPI tying the keyboard and mouse backends together. The
 * {@link org.lwjgl.input.Keyboard} and {@link org.lwjgl.input.Mouse} facades
 * each hold a reference to a single InputImplementation and call the keyboard
 * or mouse half as needed. See {@link CombinedInputImplementation} for the
 * concrete delegating implementation and
 * {@link org.lwjgl.impl.LWJGLImplementationUtils} for the factory holder.
 */
public interface InputImplementation extends KeyboardImplementation, MouseImplementation {
}