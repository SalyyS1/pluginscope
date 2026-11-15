package com.salyvn.pluginscope

import com.salyvn.pluginscope.core.Severity
import com.salyvn.pluginscope.jar.JarAuditor
import com.salyvn.pluginscope.report.SarifReporter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.nio.file.Path

class LifecycleAuditTest {

    private fun audit(path: Path) = JarAuditor().audit(path)

    private fun buildJarWithClasses(
        name: String,
        main: String = "com.acme.leak.LeakPlugin",
        classes: Map<String, ByteArray>,
    ): Path = FixtureJars.pluginJar(
        jarName = name,
        descriptor = FixtureJars.descriptor(name, main, "26.3"),
        descriptorEntry = "paper-plugin.yml",
        extraEntries = classes.mapKeys { "${it.key}.class" },
    )

    @Test
    fun `static collection accumulating BukkitTask without onDisable cleanup reports J070 critical`() {
        val cw = ClassWriter(0)
        val cls = "com/acme/leak/LeakPlugin"
        cw.visit(65, Opcodes.ACC_PUBLIC, cls, null, "java/lang/Object", null)
        cw.visitField(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "TASKS",
            "Ljava/util/List;",
            "Ljava/util/List<Lorg/bukkit/scheduler/BukkitTask;>;",
            null,
        ).visitEnd()

        // <init>
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }

        // spawnWorker(BukkitScheduler, Plugin, Runnable) -> TASKS.add(scheduler.runTaskTimerAsynchronously(...))
        cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "spawnWorker",
            "(Lorg/bukkit/scheduler/BukkitScheduler;Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;)V",
            null,
            null,
        ).apply {
            visitCode()
            val lineLabel = Label()
            visitLabel(lineLabel)
            visitLineNumber(48, lineLabel)
            visitFieldInsn(Opcodes.GETSTATIC, cls, "TASKS", "Ljava/util/List;")
            visitVarInsn(Opcodes.ALOAD, 1)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitVarInsn(Opcodes.ALOAD, 3)
            visitLdcInsn(20L)
            visitLdcInsn(20L)
            visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "org/bukkit/scheduler/BukkitScheduler",
                "runTaskTimerAsynchronously",
                "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;JJ)Lorg/bukkit/scheduler/BukkitTask;",
                true,
            )
            visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true)
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(8, 4)
            visitEnd()
        }
        cw.visitEnd()

        val jar = buildJarWithClasses("StaticTaskLeak", classes = mapOf(cls to cw.toByteArray()))
        val report = audit(jar)
        val hit = report.findings.firstOrNull { it.ruleId == "J070" }
        assertTrue(hit != null) { "Expected J070 in ${report.findings}" }
        assertEquals(Severity.CRITICAL, hit!!.severity)
        assertTrue(hit.location.endsWith("#spawnWorker:48")) { "Unexpected location: ${hit.location}" }

        // Verify SARIF preserves line 48
        val sarif = Json.parseToJsonElement(SarifReporter.render(report)).jsonObject
        val results = sarif["runs"]!!.jsonArray.single().jsonObject["results"]!!.jsonArray
        val j070Result = results.first { it.jsonObject["ruleId"]!!.jsonPrimitive.content == "J070" }.jsonObject
        val startLine = j070Result["locations"]!!.jsonArray.single().jsonObject["physicalLocation"]!!
            .jsonObject["region"]!!.jsonObject["startLine"]!!.jsonPrimitive.int
        assertEquals(48, startLine)
    }

    @Test
    fun `static task collection cancelled via helper and lambda in onDisable stays quiet`() {
        val mainCls = "com/acme/leak/CleanTasksPlugin"
        val helperCls = "com/acme/leak/TaskRegistry"

        // Helper class holding static TASKS, add, and shutdownAll using invokedynamic method reference BukkitTask::cancel + clear()
        val hw = ClassWriter(0)
        hw.visit(65, Opcodes.ACC_PUBLIC, helperCls, null, "java/lang/Object", null)
        hw.visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "TASKS",
            "Ljava/util/List;",
            "Ljava/util/List<Lorg/bukkit/scheduler/BukkitTask;>;",
            null,
        ).visitEnd()
        hw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "shutdownAll", "()V", null, null).apply {
            visitCode()
            visitFieldInsn(Opcodes.GETSTATIC, helperCls, "TASKS", "Ljava/util/List;")
            visitVarInsn(Opcodes.ASTORE, 0)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitInvokeDynamicInsn(
                "accept",
                "()Ljava/util/function/Consumer;",
                Handle(
                    Opcodes.H_INVOKESTATIC,
                    "java/lang/invoke/LambdaMetafactory",
                    "metafactory",
                    "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                        "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" +
                        "Ljava/lang/invoke/CallSite;",
                    false,
                ),
                org.objectweb.asm.Type.getType("(Ljava/lang/Object;)V"),
                Handle(Opcodes.H_INVOKEINTERFACE, "org/bukkit/scheduler/BukkitTask", "cancel", "()V", true),
                org.objectweb.asm.Type.getType("(Lorg/bukkit/scheduler/BukkitTask;)V"),
            )
            visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "forEach", "(Ljava/util/function/Consumer;)V", true)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "clear", "()V", true)
            visitInsn(Opcodes.RETURN)
            visitMaxs(3, 1)
            visitEnd()
        }
        hw.visitEnd()

        val mw = ClassWriter(0)
        mw.visit(65, Opcodes.ACC_PUBLIC, mainCls, null, "java/lang/Object", null)
        mw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        mw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "onEnable",
            "(Lorg/bukkit/scheduler/BukkitScheduler;Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;)V",
            null,
            null,
        ).apply {
            visitCode()
            visitFieldInsn(Opcodes.GETSTATIC, helperCls, "TASKS", "Ljava/util/List;")
            visitVarInsn(Opcodes.ALOAD, 1)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitVarInsn(Opcodes.ALOAD, 3)
            visitLdcInsn(20L)
            visitLdcInsn(20L)
            visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "org/bukkit/scheduler/BukkitScheduler",
                "runTaskTimerAsynchronously",
                "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;JJ)Lorg/bukkit/scheduler/BukkitTask;",
                true,
            )
            visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true)
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(8, 4)
            visitEnd()
        }
        mw.visitMethod(Opcodes.ACC_PUBLIC, "onDisable", "()V", null, null).apply {
            visitCode()
            visitMethodInsn(Opcodes.INVOKESTATIC, helperCls, "shutdownAll", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        mw.visitEnd()

        val jar = buildJarWithClasses(
            name = "CleanTasksPlugin",
            main = mainCls.replace('/', '.'),
            classes = mapOf(mainCls to mw.toByteArray(), helperCls to hw.toByteArray()),
        )
        val report = audit(jar)
        assertEquals(emptyList<String>(), report.findings.map { "${it.ruleId}: ${it.detail}" })
    }

    @Test
    fun `discarded periodic Folia AsyncScheduler handle in event handler reports J070 warning while self-cancelling BukkitRunnable stays quiet`() {
        val mainCls = "com/acme/leak/EventTimerPlugin"
        val selfCancelCls = "com/acme/leak/CountdownTask"

        // Self-cancelling BukkitRunnable subclass
        val rw = ClassWriter(0)
        rw.visit(65, Opcodes.ACC_PUBLIC, selfCancelCls, null, "org/bukkit/scheduler/BukkitRunnable", null)
        rw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "org/bukkit/scheduler/BukkitRunnable", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        rw.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, selfCancelCls, "cancel", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        rw.visitEnd()

        val mw = ClassWriter(0)
        mw.visit(65, Opcodes.ACC_PUBLIC, mainCls, null, "java/lang/Object", null)
        mw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        // Safe: self-cancelling runnable discarded via POP
        mw.visitMethod(Opcodes.ACC_PUBLIC, "startCountdown", "(Lorg/bukkit/plugin/Plugin;)V", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, selfCancelCls)
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, selfCancelCls, "<init>", "()V", false)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitLdcInsn(0L)
            visitLdcInsn(20L)
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                selfCancelCls,
                "runTaskTimer",
                "(Lorg/bukkit/plugin/Plugin;JJ)Lorg/bukkit/scheduler/BukkitTask;",
                false,
            )
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(6, 2)
            visitEnd()
        }
        // Bug: AsyncScheduler#runAtFixedRate discarded via POP in dynamic handler
        mw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "onJoin",
            "(Lio/papermc/paper/threadedregions/scheduler/AsyncScheduler;Lorg/bukkit/plugin/Plugin;Ljava/util/function/Consumer;Ljava/util/concurrent/TimeUnit;)V",
            null,
            null,
        ).apply {
            visitCode()
            val lbl = Label()
            visitLabel(lbl)
            visitLineNumber(77, lbl)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitVarInsn(Opcodes.ALOAD, 3)
            visitLdcInsn(1L)
            visitLdcInsn(5L)
            visitVarInsn(Opcodes.ALOAD, 4)
            visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "io/papermc/paper/threadedregions/scheduler/AsyncScheduler",
                "runAtFixedRate",
                "(Lorg/bukkit/plugin/Plugin;Ljava/util/function/Consumer;JJLjava/util/concurrent/TimeUnit;)Lio/papermc/paper/threadedregions/scheduler/ScheduledTask;",
                true,
            )
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(8, 5)
            visitEnd()
        }
        mw.visitEnd()

        val jar = buildJarWithClasses(
            name = "EventTimerPlugin",
            main = mainCls.replace('/', '.'),
            classes = mapOf(mainCls to mw.toByteArray(), selfCancelCls to rw.toByteArray()),
        )
        val report = audit(jar)
        val j070 = report.findings.filter { it.ruleId == "J070" }
        assertEquals(1, j070.size) { "Expected only onJoin J070, got: $j070" }
        assertEquals(Severity.WARNING, j070.single().severity)
        assertTrue(j070.single().location.endsWith("#onJoin:77"))
    }

    @Test
    fun `unmanaged ExecutorService and Timer report J071 unless shut down in onDisable or scoped locally`() {
        val leakyCls = "com/acme/leak/PoolLeakPlugin"
        val lw = ClassWriter(0)
        lw.visit(65, Opcodes.ACC_PUBLIC, leakyCls, null, "java/lang/Object", null)
        lw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        lw.visitMethod(Opcodes.ACC_PUBLIC, "onEnable", "()V", null, null).apply {
            visitCode()
            visitInsn(Opcodes.ICONST_2)
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/util/concurrent/Executors",
                "newScheduledThreadPool",
                "(I)Ljava/util/concurrent/ScheduledExecutorService;",
                false,
            )
            visitInsn(Opcodes.POP)
            visitTypeInsn(Opcodes.NEW, "java/util/Timer")
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/Timer", "<init>", "()V", false)
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(3, 1)
            visitEnd()
        }
        // Scoped executor closed within the same method (e.g. try-with-resources) must NOT trigger J071
        lw.visitMethod(Opcodes.ACC_PUBLIC, "runScopedBatch", "()V", null, null).apply {
            visitCode()
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/util/concurrent/Executors",
                "newVirtualThreadPerTaskExecutor",
                "()Ljava/util/concurrent/ExecutorService;",
                false,
            )
            visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/concurrent/ExecutorService", "close", "()V", true)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        lw.visitEnd()

        val leakyReport = audit(buildJarWithClasses("PoolLeak", leakyCls.replace('/', '.'), mapOf(leakyCls to lw.toByteArray())))
        val j071 = leakyReport.findings.filter { it.ruleId == "J071" }
        assertEquals(2, j071.size) { "Expected 2 J071 findings (Executor + Timer), got: $j071" }
        assertTrue(j071.all { it.severity == Severity.CRITICAL })
        assertTrue(j071.none { it.location.contains("runScopedBatch") })
    }

    @Test
    fun `static Player map reports J072 unless WeakHashMap or cleaned on PlayerQuitEvent`() {
        val leakyCls = "com/acme/leak/PlayerCachePlugin"
        val cw = ClassWriter(0)
        cw.visit(65, Opcodes.ACC_PUBLIC, leakyCls, null, "java/lang/Object", null)
        cw.visitField(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "SCORES",
            "Ljava/util/Map;",
            "Ljava/util/Map<Lorg/bukkit/entity/Player;Ljava/lang/Integer;>;",
            null,
        ).visitEnd()
        cw.visitField(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "WEAK_CACHE",
            "Ljava/util/WeakHashMap;",
            "Ljava/util/WeakHashMap<Lorg/bukkit/entity/Player;Ljava/lang/Integer;>;",
            null,
        ).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "record", "(Lorg/bukkit/entity/Player;Ljava/lang/Integer;)V", null, null).apply {
            visitCode()
            visitFieldInsn(Opcodes.GETSTATIC, leakyCls, "SCORES", "Ljava/util/Map;")
            visitVarInsn(Opcodes.ALOAD, 1)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true)
            visitInsn(Opcodes.POP)

            visitFieldInsn(Opcodes.GETSTATIC, leakyCls, "WEAK_CACHE", "Ljava/util/WeakHashMap;")
            visitVarInsn(Opcodes.ALOAD, 1)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/WeakHashMap", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false)
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(4, 3)
            visitEnd()
        }
        cw.visitEnd()

        val leakyReport = audit(buildJarWithClasses("PlayerCache", leakyCls.replace('/', '.'), mapOf(leakyCls to cw.toByteArray())))
        val j072 = leakyReport.findings.filter { it.ruleId == "J072" }
        assertEquals(1, j072.size) { "Expected J072 only on SCORES (not WEAK_CACHE): $j072" }
        assertTrue(j072.single().location.endsWith("#SCORES"))

        // Now add a PlayerQuitEvent listener removing from SCORES -> J072 disappears
        val cleanCw = ClassWriter(0)
        org.objectweb.asm.ClassReader(cw.toByteArray()).accept(cleanCw, 0)
        cleanCw.visitMethod(Opcodes.ACC_PUBLIC, "onQuit", "(Lorg/bukkit/event/player/PlayerQuitEvent;)V", null, null).apply {
            visitCode()
            visitFieldInsn(Opcodes.GETSTATIC, leakyCls, "SCORES", "Ljava/util/Map;")
            visitVarInsn(Opcodes.ALOAD, 1)
            visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "remove", "(Ljava/lang/Object;)Ljava/lang/Object;", true)
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(3, 2)
            visitEnd()
        }
        cleanCw.visitEnd()

        val cleanReport = audit(buildJarWithClasses("PlayerCacheClean", leakyCls.replace('/', '.'), mapOf(leakyCls to cleanCw.toByteArray())))
        assertTrue(cleanReport.findings.none { it.ruleId == "J072" }) { "${cleanReport.findings}" }
    }

    @Test
    fun `unremoved Runtime shutdown hook and static ThreadLocal report J073 critical`() {
        val cls = "com/acme/leak/PinningPlugin"
        val cw = ClassWriter(0)
        cw.visit(65, Opcodes.ACC_PUBLIC, cls, null, "java/lang/Object", null)
        cw.visitField(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "CTX",
            "Ljava/lang/ThreadLocal;",
            null,
            null,
        ).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "onEnable", "(Ljava/lang/Thread;)V", null, null).apply {
            visitCode()
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Runtime", "getRuntime", "()Ljava/lang/Runtime;", false)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Runtime", "addShutdownHook", "(Ljava/lang/Thread;)V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 2)
            visitEnd()
        }
        cw.visitEnd()

        val report = audit(buildJarWithClasses("PinningPlugin", cls.replace('/', '.'), mapOf(cls to cw.toByteArray())))
        val j073 = report.findings.filter { it.ruleId == "J073" }
        assertEquals(2, j073.size) { "Expected 2 J073 findings (shutdown hook + ThreadLocal): $j073" }
        assertTrue(j073.all { it.severity == Severity.CRITICAL })
    }

    @Test
    fun `PlaceholderExpansion registered without unregister in onDisable reports J074 critical`() {
        val cw = ClassWriter(0)
        val cls = "com/acme/leak/PapiLeakPlugin"
        cw.visit(65, Opcodes.ACC_PUBLIC, cls, null, "java/lang/Object", null)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "onEnable", "()V", null, null).apply {
            visitCode()
            val l0 = Label()
            visitLabel(l0)
            visitLineNumber(25, l0)
            visitTypeInsn(Opcodes.NEW, "me/clip/placeholderapi/expansion/PlaceholderExpansion")
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "me/clip/placeholderapi/expansion/PlaceholderExpansion", "<init>", "()V", false)
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "me/clip/placeholderapi/expansion/PlaceholderExpansion",
                "register",
                "()Z",
                false,
            )
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 1)
            visitEnd()
        }
        cw.visitEnd()

        val jar = buildJarWithClasses("PapiLeak", cls.replace('/', '.'), mapOf(cls to cw.toByteArray()))
        val report = audit(jar)
        val hit = report.findings.firstOrNull { it.ruleId == "J074" }
        assertTrue(hit != null) { "Expected J074 in ${report.findings}" }
        assertEquals(Severity.CRITICAL, hit!!.severity)
        assertTrue(hit.detail.contains("PlaceholderExpansion#register()")) { hit.detail }
    }

    @Test
    fun `Netty ChannelPipeline injection without remove in onDisable reports J074 critical`() {
        val cw = ClassWriter(0)
        val cls = "com/acme/leak/NettyLeakPlugin"
        cw.visit(65, Opcodes.ACC_PUBLIC, cls, null, "java/lang/Object", null)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "injectPipeline", "(Lio/netty/channel/ChannelPipeline;)V", null, null).apply {
            visitCode()
            val l0 = Label()
            visitLabel(l0)
            visitLineNumber(33, l0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitLdcInsn("decoder")
            visitLdcInsn("my_handler")
            visitInsn(Opcodes.ACONST_NULL)
            visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "io/netty/channel/ChannelPipeline",
                "addBefore",
                "(Ljava/lang/String;Ljava/lang/String;Lio/netty/channel/ChannelHandler;)Lio/netty/channel/ChannelPipeline;",
                true,
            )
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(4, 2)
            visitEnd()
        }
        cw.visitEnd()

        val jar = buildJarWithClasses("NettyLeak", cls.replace('/', '.'), mapOf(cls to cw.toByteArray()))
        val report = audit(jar)
        val hit = report.findings.firstOrNull { it.ruleId == "J074" }
        assertTrue(hit != null) { "Expected J074 in ${report.findings}" }
        assertEquals(Severity.CRITICAL, hit!!.severity)
        assertTrue(hit.detail.contains("Netty ChannelPipeline handler")) { hit.detail }
    }

    @Test
    fun `foreign ServicesManager provider retained in static collection without cleanup reports J074`() {
        val cw = ClassWriter(0)
        val cls = "com/acme/leak/ServicesLeakPlugin"
        cw.visit(65, Opcodes.ACC_PUBLIC, cls, null, "java/lang/Object", null)
        cw.visitField(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "PROVIDERS",
            "Ljava/util/List;",
            null,
            null,
        ).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "cacheProvider", "(Lorg/bukkit/plugin/ServicesManager;)V", null, null).apply {
            visitCode()
            val l0 = Label()
            visitLabel(l0)
            visitLineNumber(40, l0)
            visitFieldInsn(Opcodes.GETSTATIC, cls, "PROVIDERS", "Ljava/util/List;")
            visitVarInsn(Opcodes.ALOAD, 1)
            visitLdcInsn(Type.getType("Ljava/lang/Object;"))
            visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "org/bukkit/plugin/ServicesManager",
                "load",
                "(Ljava/lang/Class;)Ljava/lang/Object;",
                true,
            )
            visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true)
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(3, 2)
            visitEnd()
        }
        cw.visitEnd()

        val jar = buildJarWithClasses("ServicesLeak", cls.replace('/', '.'), mapOf(cls to cw.toByteArray()))
        val report = audit(jar)
        val hit = report.findings.firstOrNull { it.ruleId == "J074" }
        assertTrue(hit != null) { "Expected J074 in ${report.findings}" }
        assertEquals(Severity.CRITICAL, hit!!.severity)
        assertTrue(hit.detail.contains("ServicesManager")) { hit.detail }
    }
}
