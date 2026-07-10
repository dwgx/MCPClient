package net.marcloud.mcp.core.hotload;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.function.Consumer;

/**
 * Entry B of hot-load: watches a directory for {@code .java} files and deploys
 * them through the {@link HotLoadEngine} as they are created or modified. This
 * is the "正式开发工作流" — the AI writes a source file, saves it, and the
 * running game picks it up.
 *
 * <p>Runs its watch loop on a dedicated daemon thread. Each detected file is
 * read, its class name inferred from the path relative to the watch root, and
 * loaded as a NEW class (capability 1). Redefinition of existing classes is
 * driven explicitly via the engine/MCP tools, not by this watcher, to avoid
 * surprising structural redefines.
 *
 * <p>The {@code onDeploy} callback receives each {@link HotLoadEngine.LoadOutcome}
 * so callers can surface results to the AI / logs.
 */
public final class FileWatchDeployer {

    private final Path root;
    private final HotLoadEngine engine;
    private final Consumer<HotLoadEngine.LoadOutcome> onDeploy;
    private volatile Thread thread;
    private volatile boolean running;

    public FileWatchDeployer(Path root, HotLoadEngine engine,
                             Consumer<HotLoadEngine.LoadOutcome> onDeploy) {
        this.root = root;
        this.engine = engine;
        this.onDeploy = onDeploy;
    }

    /** Start watching on a daemon thread. Idempotent. */
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        Files.createDirectories(root);
        running = true;
        thread = new Thread(this::watchLoop, "mcp-hotload-watch");
        thread.setDaemon(true);
        thread.start();
    }

    /** Stop watching. */
    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void watchLoop() {
        try (WatchService ws = FileSystems.getDefault().newWatchService()) {
            root.register(ws,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            while (running) {
                WatchKey key;
                try {
                    key = ws.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                for (WatchEvent<?> event : key.pollEvents()) {
                    Object ctx = event.context();
                    if (ctx instanceof Path p && p.toString().endsWith(".java")) {
                        deploy(root.resolve(p));
                    }
                }
                if (!key.reset()) {
                    break;
                }
            }
        } catch (IOException e) {
            // Watch service died; nothing more we can do on this thread.
            onDeploy.accept(new HotLoadEngine.LoadOutcome(false,
                    "file-watch stopped: " + e, null));
        }
    }

    private void deploy(Path file) {
        try {
            String source = Files.readString(file);
            String className = inferClassName(source, file);
            HotLoadEngine.LoadOutcome outcome = engine.loadNew(className, source);
            onDeploy.accept(outcome);
        } catch (IOException e) {
            onDeploy.accept(new HotLoadEngine.LoadOutcome(false,
                    "could not read " + file + ": " + e, null));
        }
    }

    /**
     * Infer the fully-qualified class name from the source's {@code package}
     * declaration plus the file's base name. Falls back to the bare file name.
     */
    private static String inferClassName(String source, Path file) {
        String base = file.getFileName().toString();
        if (base.endsWith(".java")) {
            base = base.substring(0, base.length() - ".java".length());
        }
        for (String line : source.split("\n", 64)) {
            String t = line.trim();
            if (t.startsWith("package ") && t.endsWith(";")) {
                String pkg = t.substring("package ".length(), t.length() - 1).trim();
                return pkg + "." + base;
            }
        }
        return base;
    }
}
