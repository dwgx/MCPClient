package net.marcloud.mcp.core.security;

/**
 * Test-only fixture whose FQN sits under {@code net.marcloud.mcp.core.security.*}
 * and is therefore reported {@code true} by {@link ProtectedClasses#isProtected}
 * (the whole-package prefix rule). Used by the declaring-class guard regression
 * test: a NON-protected subclass in another package inherits these members, so a
 * field/method resolved here has a PROTECTED declaring class even though the
 * runtime class passed the initial guard. Real protected classes are all
 * final/record/enum today (no present exploit), so this synthetic protected base
 * is the way to drive the latent path.
 */
public class DeepAccessProtectedBase {

    private int secret = 7;
    private final int finalSecret = compute();

    public DeepAccessProtectedBase() {
    }

    private static int compute() {
        return 55;
    }

    private int hidden(int a) {
        return a * 2;
    }
}
