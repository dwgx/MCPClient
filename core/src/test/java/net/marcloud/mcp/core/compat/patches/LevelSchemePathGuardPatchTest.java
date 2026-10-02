package net.marcloud.mcp.core.compat.patches;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The traversal, executed rather than argued about.
 *
 * <p><b>Why a replica and not the real class.</b> {@code NetHandlerPlayClient} cannot be
 * instantiated here: loading it needs the whole Minecraft runtime, and calling
 * {@code handleResourcePack} needs a live {@code Minecraft}, {@code NetworkManager} and resource
 * pack repository. So the behavioural test builds a <b>replica</b> &mdash; a class this test
 * generates with ASM that reproduces vanilla's {@code handleResourcePack} instruction sequence over
 * the {@code level://} branch, copied from the {@code javap} listing of the shipped
 * {@code client/target/MCP-1.8.9.jar} &mdash; runs {@link LevelSchemePathGuardPatch#transform} on
 * it, forces the JVM to link (and therefore verify) the result, and then CALLS it.
 *
 * <p>The replica is faithful where it matters. The transform keys on the chain
 * {@code LDC "level://"} &rarr; {@code String.startsWith} &rarr; {@code IFEQ} &rarr;
 * {@code String.substring} &rarr; {@code new File(x, "saves")} &rarr; {@code new File(saves, s2)}
 * &rarr; {@code File.isFile()} &rarr; {@code IFEQ}, and every one of those instructions is
 * reproduced exactly. The two sinks that vanilla fills with {@code sendPacket(new
 * C19PacketResourcePackStatus(...))} become {@code record(verdict, path)} calls, and the
 * {@code gameController.mcDataDir} double field hop collapses to a single field on the replica,
 * because neither is part of the shape the transform keys on.
 *
 * <p>Running BOTH the unpatched and the patched replica on the SAME directory is the point: it is
 * what turns "the patch refuses a traversal" from an assertion about the patch's own helper method
 * into a statement about what the patched bytecode does with a server-supplied URL.
 */
public class LevelSchemePathGuardPatchTest {
    /** {@code File(File parent, String child)} -- the descriptor javac actually emits here. */
    private static final String FILE_PARENT_CTOR = "(Ljava/io/File;Ljava/lang/String;)V";

    private static final String PACKET = "net/minecraft/network/play/server/S48PacketResourcePackSend";
    private static final String REPLICA = "net/marcloud/mcp/core/compat/patches/replica/LevelSchemeReplica";
    private static final String FILE = "java/io/File";
    private static final String STRING = "java/lang/String";

    /** Verdict recorded by the replica when the candidate is loaded, as vanilla's ACCEPTED path does. */
    private static final String ACCEPTED = "accepted";
    /** Verdict recorded on the {@code if (file2.isFile())} else branch: vanilla's FAILED_DOWNLOAD. */
    private static final String REFUSED = "refused";
    /** Verdict recorded when the URL is not a {@code level://} URL at all (the HTTP branch). */
    private static final String HTTP = "http";

    private Path dataDir;
    private Path saves;

    @Before
    public void setUp() throws IOException {
        dataDir = Files.createTempDirectory("mcp-levelscheme-");
        saves = Files.createDirectories(dataDir.resolve("saves"));
    }

    @After
    public void tearDown() throws IOException {
        if (dataDir == null || !Files.exists(dataDir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dataDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort: a leftover temp file is not a test failure
                }
            });
        }
    }

    // ---- the hole, end to end -----------------------------------------------

    /**
     * THE TEST. A {@code level://} URL whose remainder climbs out of {@code saves/} with
     * {@code ..} reaches a real file in the vanilla method; the patched method refuses it.
     *
     * <p>The unpatched half is not decoration. Without it, "the patch refuses this string" could
     * mean the string simply named a file that was not there. Running vanilla on the same
     * directory with the same URL pins that the file EXISTS and is accepted before the patch, and
     * refused only after it.
     */
    @Test
    public void aTraversingLevelUrlIsAcceptedByVanillaAndRefusedByThePatchedMethod() throws Exception {
        Path secret = Files.writeString(dataDir.resolve("secret.txt"), "not a resource pack");

        // Vanilla's own decision, on a real file just outside saves/.
        Vanilla verdictBefore = runReplica(null, "level://../secret.txt");
        assertEquals("premise: the unpatched method must ACCEPT the traversal, or nothing is proved",
                ACCEPTED, verdictBefore.verdict);
        assertEquals("premise: and it read exactly the file outside saves/", secret.toFile(),
                verdictBefore.path.getCanonicalFile());

        byte[] patched = new LevelSchemePathGuardPatch().transform(replicaBytes());
        assertNotNull("the patch must transform the replica; null means it declined its own shape",
                patched);

        Vanilla verdictAfter = runReplica(patched, "level://../secret.txt");
        assertEquals("THE POINT: the patched method must refuse the same URL it accepted before",
                REFUSED, verdictAfter.verdict);
    }

    /** Several {@code ..} segments, not just one: containment is on the resolved path, not a count. */
    @Test
    public void anyNumberOfParentSegmentsOutOfSavesIsRefused() throws Exception {
        Files.writeString(dataDir.resolve("a.txt"), "x");
        Files.createDirectories(dataDir.resolve("mid"));
        Files.writeString(dataDir.resolve("mid/b.txt"), "x");
        Files.createDirectories(saves.resolve("World1"));

        byte[] patched = new LevelSchemePathGuardPatch().transform(replicaBytes());
        assertNotNull(patched);

        for (String url : new String[] {
                "level://../a.txt",
                "level://../../" + dataDir.getFileName() + "/a.txt",
                "level://World1/../../../a.txt",
                "level://./World1/../World1/../../mid/b.txt",
        }) {
            Vanilla before = runReplica(null, url);
            assertEquals("premise: vanilla accepts " + url, ACCEPTED, before.verdict);

            Vanilla after = runReplica(patched, url);
            assertEquals("the patched method must refuse " + url, REFUSED, after.verdict);
        }
    }

    /**
     * The honest other half: the fix must not break the feature. A pack genuinely inside
     * {@code saves/} is still accepted, at the top level and nested in a world folder.
     */
    @Test
    public void aPackThatReallyLivesInSavesIsStillAccepted() throws Exception {
        Path pack = Files.writeString(saves.resolve("pack.zip"), "PK");
        Path nested = Files.createDirectories(saves.resolve("World1"));
        Path nestedPack = Files.writeString(nested.resolve("pack.zip"), "PK");

        byte[] patched = new LevelSchemePathGuardPatch().transform(replicaBytes());
        assertNotNull(patched);

        Vanilla top = runReplica(patched, "level://pack.zip");
        assertEquals("a pack directly in saves/ must still load", ACCEPTED, top.verdict);
        assertEquals(pack.toFile(), top.path.getCanonicalFile());

        Vanilla deep = runReplica(patched, "level://World1/pack.zip");
        assertEquals("a pack nested in a world folder must still load", ACCEPTED, deep.verdict);
        assertEquals(nestedPack.toFile(), deep.path.getCanonicalFile());
    }

    /**
     * The reason this is a containment check and not a {@code ".."}-denylist. A symlink INSIDE
     * {@code saves/} pointing outside it passes every spelling-based filter and still leaves the
     * directory, so only the canonical-path check refuses it.
     *
     * <p>Symlink creation needs a privilege on Windows that a test process may not have, so this
     * SKIPS rather than fails when the OS refuses the link &mdash; a platform limit, not a
     * regression.
     */
    @Test
    public void aSymlinkOutOfSavesIsRefusedByContainmentButAcceptedByVanilla() throws Exception {
        Path outside = Files.createDirectories(dataDir.resolve("outside"));
        Path secret = Files.writeString(outside.resolve("secret.txt"), "x");
        Path link = saves.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue("platform refused to create a symlink here (" + e + "); skipping", false);
        }

        assertEquals("premise: vanilla follows the symlink and accepts",
                ACCEPTED, runReplica(null, "level://link/secret.txt").verdict);

        byte[] patched = new LevelSchemePathGuardPatch().transform(replicaBytes());
        assertNotNull(patched);
        assertEquals("no spelling of '..' appears in this URL, and it must still be refused",
                REFUSED, runReplica(patched, "level://link/secret.txt").verdict);
        assertEquals("and the file it would have read really was outside saves/", secret.toFile(),
                new File(saves.toFile(), "link/secret.txt").getCanonicalFile());
    }

    /** A non-level:// URL must still take the HTTP branch; the patch must not touch that path. */
    @Test
    public void aNonLevelUrlIsUnaffected() throws Exception {
        byte[] patched = new LevelSchemePathGuardPatch().transform(replicaBytes());
        assertNotNull(patched);
        assertEquals("the HTTP branch must be untouched", HTTP,
                runReplica(null, "https://example.invalid/pack.zip").verdict);
        assertEquals("the HTTP branch must be untouched", HTTP,
                runReplica(patched, "https://example.invalid/pack.zip").verdict);
    }

    // ---- the guard itself, against real filesystem layouts -------------------

    @Test
    public void theGuardRefusesNullsAndTheSavesDirectoryItself() {
        assertEquals(false, LevelSchemePathGuardPatch.isInsideSaves(null, new File("x")));
        assertEquals(false, LevelSchemePathGuardPatch.isInsideSaves(saves.toFile(), null));
        // The directory itself is not "inside" itself under a trailing-separator comparison.
        assertEquals(false, LevelSchemePathGuardPatch.isInsideSaves(saves.toFile(), saves.toFile()));
    }

    /** A sibling whose name merely starts with {@code saves} is not a child of it. */
    @Test
    public void aSiblingDirectoryNamedLikeSavesIsNotInsideIt() throws IOException {
        Path sibling = Files.createDirectories(dataDir.resolve("saves-evil"));
        Path pack = Files.writeString(sibling.resolve("pack.zip"), "PK");
        assertEquals(false, LevelSchemePathGuardPatch.isInsideSaves(saves.toFile(), pack.toFile()));
    }

    // ---- fail-safe ----------------------------------------------------------

    @Test
    public void aClassWithoutTheAnchoredShapeIsLeftAlone() {
        assertEquals("a class with no such method must be left untouched (JDK transformer convention)",
                null, new LevelSchemePathGuardPatch().transform(replicaWithoutTheLevelSchemeBranch()));
        assertEquals("empty input is not a class", null,
                new LevelSchemePathGuardPatch().transform(new byte[0]));
    }

    @Test
    public void applyingThePatchTwiceDoesNotStackTheGuard() {
        byte[] once = new LevelSchemePathGuardPatch().transform(replicaBytes());
        assertNotNull(once);
        assertEquals("idempotent: a second application must decline", null,
                new LevelSchemePathGuardPatch().transform(once));
    }

    // ---- driving the replica ------------------------------------------------

    private record Vanilla(String verdict, File path) {
    }

    /**
     * Load the packet stub and the replica (patched or not) in a throwaway loader, LINK them (which
     * is what forces bytecode verification), and invoke {@code handleResourcePack} once.
     */
    private Vanilla runReplica(byte[] replicaOrNull, String url) throws Exception {
        VerdictLoader loader = new VerdictLoader();
        Class<?> packetCls = loader.define(packetStub());
        Class<?> replicaCls = loader.define(replicaOrNull != null ? replicaOrNull : replicaBytes());
        Field verdictField = replicaCls.getField("lastVerdict");
        Field pathField = replicaCls.getField("lastPath");
        verdictField.set(null, null);
        pathField.set(null, null);

        Object replica = replicaCls.getConstructor(File.class).newInstance(dataDir.toFile());
        Object packet = packetCls.getConstructor(String.class, String.class)
                .newInstance(url, "d41d8cd98f00b204e9800998ecf8427e");
        Method handle = replicaCls.getMethod("handleResourcePack", packetCls);
        handle.invoke(replica, packet);

        Object path = pathField.get(null);
        return new Vanilla((String) verdictField.get(null), path == null ? null : new File((String) path));
    }

    /** Defines classes and forces linking, so a VerifyError fails the test here, not in prod. */
    private static final class VerdictLoader extends ClassLoader {
        VerdictLoader() {
            super(LevelSchemePathGuardPatchTest.class.getClassLoader());
        }

        Class<?> define(byte[] bytes) {
            Class<?> c = defineClass(null, bytes, 0, bytes.length);
            resolveClass(c);
            return c;
        }
    }

    // ---- replica construction ------------------------------------------------

    /** {@code S48PacketResourcePackSend} reduced to the two getters vanilla's method calls. */
    private static byte[] packetStub() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, PACKET, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "url", "Ljava/lang/String;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "hash", "Ljava/lang/String;", null, null).visitEnd();

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(Ljava/lang/String;Ljava/lang/String;)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, PACKET, "url", "Ljava/lang/String;");
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 2);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, PACKET, "hash", "Ljava/lang/String;");
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(2, 3);
        ctor.visitEnd();

        getter(cw, "getURL", "url");
        getter(cw, "getHash", "hash");
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void getter(ClassWriter cw, String name, String field) {
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, name, "()Ljava/lang/String;", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitFieldInsn(Opcodes.GETFIELD, PACKET, field, "Ljava/lang/String;");
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(1, 1);
        m.visitEnd();
    }

    /**
     * Vanilla's {@code handleResourcePack} over the {@code level://} branch, instruction for
     * instruction with the {@code javap} listing of the shipped jar. See the class javadoc for the
     * two places the sinks are simplified and why neither is part of the transform's anchor.
     */
    private static byte[] replicaBytes() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, REPLICA, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "mcDataDir", "Ljava/io/File;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "lastVerdict", "Ljava/lang/String;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "lastPath", "Ljava/lang/String;", null, null).visitEnd();

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/io/File;)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, REPLICA, "mcDataDir", "Ljava/io/File;");
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(2, 2);
        ctor.visitEnd();

        MethodVisitor rec = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "record",
                "(Ljava/lang/String;Ljava/lang/String;)V", null, null);
        rec.visitCode();
        // PUTSTATIC pops exactly one value (the field owner is not pushed), so each store pushes
        // only its own argument. Pushing the owner too would leak one slot per store.
        rec.visitVarInsn(Opcodes.ALOAD, 0);
        rec.visitFieldInsn(Opcodes.PUTSTATIC, REPLICA, "lastVerdict", "Ljava/lang/String;");
        rec.visitVarInsn(Opcodes.ALOAD, 1);
        rec.visitFieldInsn(Opcodes.PUTSTATIC, REPLICA, "lastPath", "Ljava/lang/String;");
        rec.visitInsn(Opcodes.RETURN);
        rec.visitMaxs(1, 2);
        rec.visitEnd();

        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "handleResourcePack",
                "(L" + PACKET + ";)V", null, null);
        m.visitCode();
        Label http = new Label();
        Label refuse = new Label();
        Label end = new Label();

        // 0..9: final String s = getURL(); final String s1 = getHash();
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PACKET, "getURL", "()Ljava/lang/String;", false);
        m.visitVarInsn(Opcodes.ASTORE, 2);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PACKET, "getHash", "()Ljava/lang/String;", false);
        m.visitVarInsn(Opcodes.ASTORE, 3);

        // 10..17: if (s.startsWith("level://"))
        m.visitVarInsn(Opcodes.ALOAD, 2);
        m.visitLdcInsn("level://");
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STRING, "startsWith", "(Ljava/lang/String;)Z", false);
        m.visitJumpInsn(Opcodes.IFEQ, http);

        // 20..30: String s2 = s.substring("level://".length());
        m.visitVarInsn(Opcodes.ALOAD, 2);
        m.visitLdcInsn("level://");
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STRING, "length", "()I", false);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STRING, "substring", "(I)Ljava/lang/String;", false);
        m.visitVarInsn(Opcodes.ASTORE, 4);

        // 32..49: File file1 = new File(mcDataDir, "saves");
        m.visitTypeInsn(Opcodes.NEW, FILE);
        m.visitInsn(Opcodes.DUP);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitFieldInsn(Opcodes.GETFIELD, REPLICA, "mcDataDir", "Ljava/io/File;");
        m.visitLdcInsn("saves");
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, FILE, "<init>", FILE_PARENT_CTOR, false);
        m.visitVarInsn(Opcodes.ASTORE, 5);

        // 51..62: File file2 = new File(file1, s2);
        m.visitTypeInsn(Opcodes.NEW, FILE);
        m.visitInsn(Opcodes.DUP);
        m.visitVarInsn(Opcodes.ALOAD, 5);
        m.visitVarInsn(Opcodes.ALOAD, 4);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, FILE, "<init>", FILE_PARENT_CTOR, false);
        m.visitVarInsn(Opcodes.ASTORE, 6);

        // 64..69: if (file2.isFile())   <-- the patch injects its containment check just above this
        m.visitVarInsn(Opcodes.ALOAD, 6);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FILE, "isFile", "()Z", false);
        m.visitJumpInsn(Opcodes.IFEQ, refuse);
        recordVerdict(m, ACCEPTED);
        m.visitJumpInsn(Opcodes.GOTO, end);

        // 72..117 in vanilla: the ACCEPTED branch. Same branch, sink simplified.
        m.visitLabel(refuse);
        m.visitFrame(Opcodes.F_NEW, 7,
                new Object[] {REPLICA, PACKET, STRING, STRING, STRING, FILE, FILE}, 0, null);
        // 120..135 in vanilla: the else branch that sends FAILED_DOWNLOAD.
        recordVerdict(m, REFUSED);

        m.visitLabel(end);
        m.visitFrame(Opcodes.F_NEW, 7,
                new Object[] {REPLICA, PACKET, STRING, STRING, STRING, FILE, FILE}, 0, null);
        m.visitInsn(Opcodes.RETURN);

        // 141..: the HTTP branch, which this patch must not touch.
        m.visitLabel(http);
        m.visitFrame(Opcodes.F_NEW, 4, new Object[] {REPLICA, PACKET, STRING, STRING}, 0, null);
        m.visitLdcInsn(HTTP);
        m.visitLdcInsn("");
        m.visitMethodInsn(Opcodes.INVOKESTATIC, REPLICA, "record",
                "(Ljava/lang/String;Ljava/lang/String;)V", false);
        m.visitInsn(Opcodes.RETURN);

        m.visitMaxs(4, 7);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void recordVerdict(MethodVisitor m, String verdict) {
        m.visitLdcInsn(verdict);
        m.visitVarInsn(Opcodes.ALOAD, 6);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FILE, "getPath", "()Ljava/lang/String;", false);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, REPLICA, "record",
                "(Ljava/lang/String;Ljava/lang/String;)V", false);
    }

    /**
     * A class that has the method but not the {@code level://} branch &mdash; used to pin that the
     * transform declines a shape it does not recognise instead of half-rewriting it.
     */
    private static byte[] replicaWithoutTheLevelSchemeBranch() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, REPLICA, null, "java/lang/Object", null);
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "handleResourcePack",
                "(L" + PACKET + ";)V", null, null);
        m.visitCode();
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 2);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

}