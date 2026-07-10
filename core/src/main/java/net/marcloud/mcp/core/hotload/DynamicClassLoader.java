package net.marcloud.mcp.core.hotload;

import java.util.HashMap;
import java.util.Map;

/**
 * Loads freshly-compiled bytecode as brand-new classes into the running JVM.
 *
 * <p>Parented to the classloader that loaded the game (passed in), so
 * AI-authored classes can reference {@code net.minecraft.*}, guava, netty, etc.
 * Each new class name is defined exactly once per loader instance; to reload a
 * changed class under the same name, create a fresh loader (the old class stays
 * resolved by whoever already holds a reference — this is normal JVM behavior
 * and why redefinition, not reloading, is used to change *existing* classes).
 *
 * <p>This handles capability (1): adding wholly new classes. Changing an
 * already-loaded class's body/shape is {@link Redefiner}'s job.
 */
public final class DynamicClassLoader extends ClassLoader {

    private final Map<String, byte[]> pending = new HashMap<>();

    public DynamicClassLoader(ClassLoader parent) {
        super(parent);
    }

    /** Register bytecode for a class this loader should be able to define. */
    public synchronized void register(String className, byte[] bytecode) {
        pending.put(className, bytecode);
    }

    /** Register all entries from a successful compilation. */
    public synchronized void registerAll(Map<String, byte[]> bytecodeByName) {
        pending.putAll(bytecodeByName);
    }

    @Override
    protected synchronized Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = pending.get(name);
        if (bytes == null) {
            throw new ClassNotFoundException(name);
        }
        return defineClass(name, bytes, 0, bytes.length);
    }

    /** Register {@code bytecode} then load and return the class. */
    public Class<?> define(String className, byte[] bytecode) throws ClassNotFoundException {
        register(className, bytecode);
        return loadClass(className);
    }
}
