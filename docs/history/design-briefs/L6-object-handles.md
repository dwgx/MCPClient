---
doc: brief-l6
title: L6 OBJECT-HANDLE enforcement layer — NT open→freeze-mask→subset-check capability
layer: archive
status: archived
updated: 2026-07-11
parent: ../README.md
next:
  - path: ../architecture/01-SECURITY-KERNEL.md
    when: 需要安全内核/权限层的当前权威状态
read_if: 历史设计 brief(未实现);仅当你要把 L6 对象句柄层从占位落地为完整层时才读(数字为撰写时快照)。
---
> **归档设计 brief**(未实现)— L6 对象句柄层的设计。当前 L6 仍是扩展 seam(evaluate() 中的占位),尚未落地为完整层。本文是未来实现的设计参考。当前系统状态见 `../architecture/`。("112 tests" 等数字是撰写时的快照,现为 178。)

AREA: L6 OBJECT-HANDLE enforcement layer (net.marcloud.mcp.core.security) — NT open→freeze-mask→subset-check capability, wired as an additive seam into the existing 7-layer reference monitor

DESIGN:
INTEGRATION PRINCIPLE (why it can't live inside the AND-chain the way L2-L5 do): evaluate() is also called with an EMPTY arg map — CapabilityRegistry.isAllowed() and the pre-handler gate both pass request.arguments() that at decision time may be Map.of(). A handle is a per-operation runtime token acquired INSIDE a session-establishing tool; it is not derivable from toolName+args at gate time. So L6 must be additive and orthogonal: evaluate() keeps L2-L5 exactly, and gains one guarded call that is a pure no-op unless (a) an ObjectManager is wired AND (b) the request literally carries a "handle" arg.

=== security/AccessRight.java (enum, bit flags) ===
public enum AccessRight {
    READ(1), WRITE(1<<1), REDEFINE(1<<2), EXECUTE(1<<3), DELETE(1<<4);
    private final int bit; AccessRight(int b){bit=b;}
    public int bit(){return bit;}
    public static int mask(AccessRight... rs){int m=0; for(var r:rs) m|=r.bit; return m;}
    public static int mask(java.util.Set<AccessRight> rs){int m=0; for(var r:rs) m|=r.bit; return m;}
    public static boolean subset(int have,int need){return (have&need)==need;}          // core TOCTOU check
    public boolean in(int mask){return (mask&bit)!=0;}
    public static java.util.EnumSet<AccessRight> decode(int mask){var s=java.util.EnumSet.noneOf(AccessRight.class); for(var r:values()) if(r.in(mask)) s.add(r); return s;}
    public static int parse(String csv){ // "READ,WRITE" or "READ|WRITE"; throws on unknown token
        if(csv==null||csv.isBlank()) return 0; int m=0;
        for(String t: csv.split("[,|\\s]+")){ if(t.isBlank())continue; m|=valueOf(t.trim().toUpperCase(java.util.Locale.ROOT)).bit;} return m;}
    public static String render(int mask){return decode(mask).toString();}
}

=== security/ResourceRef.java (record + scheme parser) ===
public record ResourceRef(Scheme scheme, String target) {
    public enum Scheme { CLASS, FIELD, METHOD, CHANNEL, THREAD, FRAME, MODULE }
    public ResourceRef { if(scheme==null) throw new IllegalArgumentException("null scheme");
        if(target==null||target.isBlank()) throw new IllegalArgumentException("blank target"); }
    /** "class:net.minecraft.X", "field:player#health", "method:owner#name", "channel:<id>",
     *  "thread:<name|id>", "frame:<thread>:<depth>", "module:java.base/jdk.internal.misc". */
    public static ResourceRef parse(String s){
        if(s==null) throw new IllegalArgumentException("null ref");
        int c=s.indexOf(':'); if(c<0) throw new IllegalArgumentException("missing scheme prefix: "+s);
        String p=s.substring(0,c).trim().toUpperCase(java.util.Locale.ROOT);
        Scheme sc; try{sc=Scheme.valueOf(p);}catch(IllegalArgumentException e){throw new IllegalArgumentException("unknown scheme '"+p+"' in "+s);}
        return new ResourceRef(sc, s.substring(c+1).trim());
    }
    public String prefix(){return scheme.name().toLowerCase(java.util.Locale.ROOT);}
    /** Rights a scheme can legally grant (open() rejects desiredMask outside this).
     *  CLASS→READ|REDEFINE; FIELD→READ|WRITE; METHOD→EXECUTE; CHANNEL→READ|WRITE|DELETE;
     *  THREAD→READ|WRITE|EXECUTE (locals-read / set-local+force-return / suspend-pop-step);
     *  FRAME→READ|WRITE (locals); MODULE→REDEFINE (open package). */
    public int allowableRights(){ return switch(scheme){
        case CLASS   -> AccessRight.mask(AccessRight.READ, AccessRight.REDEFINE);
        case FIELD   -> AccessRight.mask(AccessRight.READ, AccessRight.WRITE);
        case METHOD  -> AccessRight.EXECUTE.bit();
        case CHANNEL -> AccessRight.mask(AccessRight.READ, AccessRight.WRITE, AccessRight.DELETE);
        case THREAD  -> AccessRight.mask(AccessRight.READ, AccessRight.WRITE, AccessRight.EXECUTE);
        case FRAME   -> AccessRight.mask(AccessRight.READ, AccessRight.WRITE);
        case MODULE  -> AccessRight.REDEFINE.bit(); }; }
}

=== security/Handle.java (frozen capability) ===
public final class Handle implements AutoCloseable {
    private final long id; private final String owner; private final ResourceRef ref;
    private final int frozenMask;            // FROZEN at open — never widened
    private final Object target;             // resolved-once snapshot (jthread/Channel/Class/…)
    private final long openedAtNanos; private volatile long lastUsedNanos;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.function.Consumer<Handle> onClose;   // ObjectManager dereg + resource cleanup
    Handle(long id,String owner,ResourceRef ref,int mask,Object target,long now,java.util.function.Consumer<Handle> onClose){
        this.id=id; this.owner=owner; this.ref=ref; this.frozenMask=mask; this.target=target;
        this.openedAtNanos=now; this.lastUsedNanos=now; this.onClose=onClose; }
    public long id(){return id;} public String owner(){return owner;} public ResourceRef ref(){return ref;}
    public int mask(){return frozenMask;} public Object target(){return target;} public boolean isClosed(){return closed.get();}
    boolean permits(int need){return !closed.get() && AccessRight.subset(frozenMask,need);}
    void touch(long now){lastUsedNanos=now;} long lastUsedNanos(){return lastUsedNanos;}
    @Override public void close(){ if(closed.compareAndSet(false,true)) onClose.accept(this); }
}
Note: package-private ctor — only ObjectManager mints handles. target() is frozen at open so a resource swapped underneath (player replaced on respawn, Channel replaced on reconnect, jthread-id reuse) cannot silently redirect a live handle.

=== security/ObjectManager.java (open/close/require/reap) ===
public final class ObjectManager {
    @FunctionalInterface public interface TargetResolver { Object resolve(ResourceRef ref) throws Exception; }
    private final java.util.concurrent.ConcurrentHashMap<Long,Handle> handles = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String,Integer> perSubject = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong(1);
    private final TargetResolver resolver; private final int perSubjectCap; private final long idleTtlNanos;
    private final java.util.function.LongSupplier clock;   // System::nanoTime in prod; fake clock in tests
    // per-tool right needed for a handle op (only handle-using tools listed; absent ⇒ READ)
    private static final java.util.Map<String,Integer> HANDLE_OPS = java.util.Map.ofEntries(
        java.util.Map.entry("dbg_read_locals", AccessRight.READ.bit()),
        java.util.Map.entry("dbg_set_local",   AccessRight.WRITE.bit()),
        java.util.Map.entry("dbg_force_return", AccessRight.WRITE.bit()),
        java.util.Map.entry("dbg_suspend",     AccessRight.EXECUTE.bit()),
        java.util.Map.entry("dbg_resume",      AccessRight.EXECUTE.bit()),
        java.util.Map.entry("dbg_pop_frame",   AccessRight.EXECUTE.bit()),
        java.util.Map.entry("dbg_step",        AccessRight.EXECUTE.bit()));
    public ObjectManager(TargetResolver r,int cap,long idleTtlMillis){ this(r,cap,idleTtlMillis,System::nanoTime);}
    ObjectManager(TargetResolver r,int cap,long idleTtlMillis,java.util.function.LongSupplier clock){
        this.resolver=r; this.perSubjectCap=cap; this.idleTtlNanos=idleTtlMillis*1_000_000L; this.clock=clock;}
    public synchronized Handle open(SecurityContext s, ResourceRef ref, int desiredMask){
        reapIdle();
        if(!AccessRight.subset(ref.allowableRights(), desiredMask))
            throw new IllegalArgumentException("L6 open: mask "+AccessRight.render(desiredMask)+" exceeds "+ref.prefix()+" allowable "+AccessRight.render(ref.allowableRights()));
        String who=s.tokenId();
        if(perSubject.getOrDefault(who,0) >= perSubjectCap)
            throw new IllegalStateException("L6 open: subject '"+who+"' at handle cap "+perSubjectCap);
        Object t; try{ t=resolver.resolve(ref);}catch(Exception e){throw new RuntimeException("L6 open: cannot resolve "+ref,e);}
        long id=seq.getAndIncrement(); long now=clock.getAsLong();
        Handle h=new Handle(id,who,ref,desiredMask,t,now,this::deregister);
        handles.put(id,h); perSubject.merge(who,1,Integer::sum); return h; }
    private synchronized void deregister(Handle h){ handles.remove(h.id()); perSubject.computeIfPresent(h.owner(),(k,v)->v<=1?null:v-1); }
    public void close(long id, SecurityContext s){ Handle h=handles.get(id);
        if(h!=null && h.owner().equals(s.tokenId())) h.close(); }
    /** the subset check — the L6 verdict. */
    public AccessDecision require(long id, SecurityContext s, int needed){
        Handle h=handles.get(id);
        if(h==null||h.isClosed()) return AccessDecision.deny("L6 handle","no open handle #"+id+" (unknown, closed, or reaped)");
        if(!h.owner().equals(s.tokenId())) return AccessDecision.deny("L6 handle","handle #"+id+" owned by '"+h.owner()+"', not '"+s.tokenId()+"'");
        if(!h.permits(needed)) return AccessDecision.deny("L6 handle","handle #"+id+" frozen mask "+AccessRight.render(h.mask())+" does not grant "+AccessRight.render(needed));
        h.touch(clock.getAsLong()); return AccessDecision.allowed(); }
    /** the gate seam: no "handle" arg ⇒ allowed() (pure no-op for every non-handle tool). */
    public AccessDecision checkRequest(SecurityContext s, ToolRequest req){
        Object hv=req.arguments().get("handle"); if(hv==null) return AccessDecision.allowed();
        long id; try{ id=Long.parseLong(hv.toString().trim());}catch(NumberFormatException e){return AccessDecision.deny("L6 handle","malformed handle id '"+hv+"'");}
        return require(id, s, HANDLE_OPS.getOrDefault(req.toolName(), AccessRight.READ.bit())); }
    public synchronized int reapIdle(){ long now=clock.getAsLong(); int n=0;
        for(Handle h: new java.util.ArrayList<>(handles.values())) if(now-h.lastUsedNanos()>idleTtlNanos){h.close();n++;} return n;}
    public int openCount(String tokenId){return perSubject.getOrDefault(tokenId,0);} public int total(){return handles.size();}
}

=== security/InProcessPolicyEngine.java integration (ADDITIVE ONLY) ===
Add a nullable field + a 3-arg ctor; the existing 1-arg and 2-arg ctors delegate with objects=null so every current caller (McpCore.java:287/289, CapabilityRegistry:59, PSecureMain, all tests) is unchanged:
    private final ObjectManager objects;                          // nullable
    public InProcessPolicyEngine(PermissionPolicy p){ this(p, SecurityContext.wideOpen(), null); }
    public InProcessPolicyEngine(PermissionPolicy p, SecurityContext base){ this(p, base, null); }
    public InProcessPolicyEngine(PermissionPolicy p, SecurityContext base, ObjectManager objects){
        this.policy=p; this.baseSubject=base; this.objects=objects; }
Then in evaluate(), between the L5 block and the final `return AccessDecision.allowed();`, insert:
    // L6 — object-handle subset check. No-op unless an ObjectManager is wired AND the
    // request carries a "handle" arg; tools that use no handles are unaffected.
    if (objects != null) {
        AccessDecision h6 = objects.checkRequest(subject, request);
        if (!h6.allow()) return h6;
    }
Because objects==null in every existing construction path, evaluate() is byte-identical today → 112 tests stay green. checkRequest short-circuits to allowed() on the empty-arg calls from isAllowed()/the pre-handler gate.

=== WHICH OPS SHOULD ACQUIRE HANDLES (justified) ===
- C6 JVMTI debugger session — YES (the whole reason to build L6). A thread-control session opens once with a frozen mask ⊆ THREAD.allowableRights() (READ locals / WRITE set-local+force-return / EXECUTE suspend-pop-step) and then issues many ops. Freezing at open gives a downward-only, TOCTOU-safe capability: a session opened READ-only can never later escalate to force-early-return even if the subject re-enables SE_DEBUG_CONTROL mid-session; and a jthread frozen in target() defeats thread-id reuse. The idle reaper auto-resumes a leaked suspended thread (a hung game thread is a real failure mode). This mirrors the DepthARK token/integrity gate.
- Installed hooks (DynamicHookManager) — OPTIONAL / defer. A hook already has HookRecord + hookId + the ProtectedClasses denylist (which gates the dangerous direction: what may be hooked). A Handle would add only owner-scoping (only the installer may uninstall) + reaper auto-revert of leaked hooks. Real but modest; wire it only if/when multi-subject sessions exist. If done, open at install with REDEFINE|DELETE, uninstall_via_handle needs DELETE.
- Deep-access field writes / reads (write_field, read_field, invoke_method) — NO (ceremony). These are single-shot: resolve→act→done. open and use collapse into one call, so the frozen-mask TOCTOU property has nothing to bind. L3 (HIGH integrity) + L4 (SE_DEBUG_CLASS) + L5 (CAP_MEMORY_WRITE) already gate every call. Splitting into open_field_handle + write_via_handle adds a round-trip and state for zero new safety.
- open_module — NO. One-shot, already SYSTEM/L3 + SE_DEBUG_CLASS.

=== HONEST RECOMMENDATION ===
For the tools live today, L6 is gold-plating: nothing has a long-lived, multi-op resource that L3/L4/L5 don't already cover per-call. But the C6 debugger — the one operation that genuinely needs a TOCTOU-safe, downward-only capability — is coming (native-blocked only on the DLL, the Java side is unblocked). Building the pure-Java L6 core now (~4 small classes, no native dep, fully headless-testable) plus the inert no-op seam costs almost nothing and means the debugger is born handle-native instead of retrofitted under pressure. So: BUILD the core + tests + seam NOW; DO NOT wire any existing tool to require a handle. That is the honest middle path — you bank the architecture and the test scaffold without paying ceremony on tools that don't need it.

NEW FILES:
core/src/main/java/net/marcloud/mcp/core/security/AccessRight.java
core/src/main/java/net/marcloud/mcp/core/security/ResourceRef.java
core/src/main/java/net/marcloud/mcp/core/security/Handle.java
core/src/main/java/net/marcloud/mcp/core/security/ObjectManager.java
core/src/test/java/L6ObjectHandleTest.java

TOUCH:
core/src/main/java/net/marcloud/mcp/core/security/InProcessPolicyEngine.java

FALLBACK:
L6 has NO native dependency — AccessRight/ResourceRef/Handle/ObjectManager are pure Java and run identically whether or not the -agentpath JVMTI DLL (MSVC, not yet installed) is present. Degradation is layered: (1) if the DLL is absent, the C6 debugger tools that would open THREAD/FRAME handles simply are not registered — mirror DynamicHookManager.canInstall()/HookTools with a canControl() guard — so no handle is ever opened and L6 has nothing to enforce; (2) if the ObjectManager is not wired at all (today's state), the engine's objects field is null and evaluate() is byte-identical to the current 112-green build; (3) if it IS wired but a tool call carries no \"handle\" arg, checkRequest() returns allowed() — a pure no-op. Thus L6 collapses gracefully to \"absent\" in every configuration short of a live handle-using session, and never blocks an existing tool.

TESTS:
New headless JUnit4 class L6ObjectHandleTest.java (matches the src/test/java flat layout, junit 4 like SecurityKernelTest). Use an injected fake clock (the package-private 4-arg ObjectManager ctor) so the idle reaper is deterministic with no sleeps, and a TargetResolver lambda returning sentinel objects so nothing touches the live game.
Cases:
1. accessRightBitsAndSubset — mask()/subset()/decode()/parse("READ|WRITE")/render round-trip; subset(READ|WRITE, DELETE)==false.
2. resourceRefParsesAllSevenSchemes — class:/field:/method:/channel:/thread:/frame:/module: each maps to the right Scheme; missing prefix and unknown scheme throw IllegalArgumentException.
3. openFreezesMask — open(field:player#hp, READ|WRITE) → handle.mask()==READ|WRITE and is immutable (no setter exists; re-open yields a distinct id).
4. openRejectsMaskAboveSchemeAllowable — open(method:x, READ) throws (METHOD allows only EXECUTE); open(class:x, WRITE) throws.
5. subsetCheckAllows — require(h, READ) allowed when frozen==READ|WRITE.
6. denyOnEscalation — require(h, DELETE) denies with layer "L6 handle" and reason naming the frozen mask (the core TOCTOU assertion).
7. ownerMismatchDenied — subject B require()s subject A's handle → deny "L6 handle".
8. closedHandleDenied — close(); require() → deny (unknown/closed).
9. perSubjectCapEnforced — cap=2; third open() throws IllegalStateException; after close() a new open() succeeds and the per-subject count decrements.
10. idleReaperClosesStale — advance fake clock past ttl; reapIdle() returns 1, handle.isClosed(), total()==0.
11. frozenTargetTocTou — resolver returns v1 at open; even after the resolver would now return v2, handle.target()==v1 (freeze proven).
12. gateSeamNoOpWithoutHandleArg — checkRequest(subject, new ToolRequest("scan_surroundings", Map.of(), true)) == allowed(); with a bogus "handle" arg on a handle-tool → deny.
13. engineAdditivity — construct InProcessPolicyEngine(policy, wideOpen(), objectManager) and re-run two SecurityKernelTest scenarios (wide-open allows eval_java; dropped clearance denies at L2) to prove the wired seam changes nothing for non-handle tools; then a handle-tool request with a valid vs escalating handle flips allow/deny.
Regression: run the full module test suite (mvn -q test) and confirm the count stays 112 green — the 1-/2-arg engine ctors keep objects==null so evaluate() is unchanged.

RISKS:
evaluate() is also invoked with empty args by CapabilityRegistry.isAllowed() and the pre-handler gate — if checkRequest() did not short-circuit on a missing "handle" key it would break isAllowed(); the design guarantees allowed() when args lack "handle".
The "handle" arg is a magic key in the request map; a schema-declared "handle" property on unrelated tools could accidentally trigger L6. Mitigation: only tools in HANDLE_OPS resolve a non-READ right, and non-handle tools never declare the key; document the reserved arg name.
ObjectManager holds strong references to resolved targets (jthread/Channel/Class) — a leaked handle pins the target; the idle reaper bounds this but the reaper only runs on open()/explicit reapIdle(), so a burst-then-idle subject needs a periodic sweep (a scheduled reaper) if handles are long-lived.
Suspended-thread cleanup on reap must actually issue ResumeThread via the C6 layer; if the DLL is absent the reaper can only drop bookkeeping, not resume — but with no DLL no THREAD handle is ever opened, so this is vacuous until C6 lands.
Adding a 3-arg ctor + field to a ProtectedClasses-guarded security class is fine (whole security package is already protection-covered), but the change must stay purely additive to preserve the 112-test invariant.