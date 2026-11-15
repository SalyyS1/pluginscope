package com.salyvn.pluginscope.jar

import com.salyvn.pluginscope.core.FindingSink
import com.salyvn.pluginscope.core.JarInspection
import com.salyvn.pluginscope.core.Severity
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * ASM instruction-flow and call-graph checks for asynchronous scheduler leaks, unmanaged worker
 * threads, static entity/world retention, and JVM hooks that pin PaperPluginClassLoader across
 * plugin disable or reload.
 */
internal object LifecycleChecks {

    private data class MethodRef(val owner: String, val key: String)

    private data class StaticFieldDef(
        val owner: String,
        val name: String,
        val descriptor: String,
        val signature: String?,
        val access: Int,
    ) {
        val fieldKey: String get() = "$owner#$name"
    }

    private sealed interface FlowInsn {
        val line: Int?

        data class Call(
            val opcode: Int,
            val owner: String,
            val name: String,
            val descriptor: String,
            override val line: Int?,
        ) : FlowInsn

        data class DynamicTarget(
            val owner: String,
            val name: String,
            val descriptor: String,
            val callSiteDescriptor: String,
            override val line: Int?,
        ) : FlowInsn

        data class FieldOp(
            val opcode: Int,
            val owner: String,
            val name: String,
            val descriptor: String,
            override val line: Int?,
        ) : FlowInsn

        data class VarOp(
            val opcode: Int,
            val slot: Int,
            override val line: Int?,
        ) : FlowInsn

        data class TypeOp(
            val opcode: Int,
            val internalType: String,
            override val line: Int?,
        ) : FlowInsn

        data class ConstNull(override val line: Int?) : FlowInsn

        data class StackOp(val opcode: Int, override val line: Int?) : FlowInsn

        data class ReturnOp(val opcode: Int, override val line: Int?) : FlowInsn

        data class OtherOp(val opcode: Int, override val line: Int?) : FlowInsn
    }

    private data class OwnMethodModel(
        val owner: String,
        val name: String,
        val descriptor: String,
        val access: Int,
        val isEventHandler: Boolean,
        val insns: List<FlowInsn>,
    ) {
        val key: String get() = name + descriptor
    }

    private data class OwnClassModel(
        val internalName: String,
        val superName: String?,
        val interfaces: List<String>,
        val staticFields: List<StaticFieldDef>,
        val methods: Map<String, OwnMethodModel>,
    )

    private sealed interface TaintVal {
        data class TaskHandle(
            val periodic: Boolean,
            val apiCall: String,
            val line: Int?,
            var escapedOrDuplicated: Boolean = false,
        ) : TaintVal

        data class StaticRef(
            val owner: String,
            val fieldName: String,
            val descriptor: String,
        ) : TaintVal {
            val fieldKey: String get() = "$owner#$fieldName"
        }

        data class NewObject(val internalType: String, val line: Int?) : TaintVal

        data object NullConst : TaintVal
        data object Unknown : TaintVal
    }

    private data class StaticTaskWrite(
        val fieldKey: String,
        val inCollection: Boolean,
        val periodic: Boolean,
        val location: String,
        val apiCall: String,
    )

    private data class DiscardedPeriodicSite(
        val location: String,
        val apiCall: String,
    )

    private data class UnmanagedWorkerSite(
        val kind: WorkerKind,
        val location: String,
        val detail: String,
        val scopedClosedInSameMethod: Boolean,
    )

    private enum class WorkerKind { EXECUTOR, TIMER, THREAD }

    private val BUKKIT_SCHEDULERS = setOf(
        "org/bukkit/scheduler/BukkitScheduler",
        "org/bukkit/scheduler/BukkitRunnable",
    )

    private val PAPER_SCHEDULERS = setOf(
        "io/papermc/paper/threadedregions/scheduler/AsyncScheduler",
        "io/papermc/paper/threadedregions/scheduler/GlobalRegionScheduler",
        "io/papermc/paper/threadedregions/scheduler/RegionScheduler",
        "io/papermc/paper/threadedregions/scheduler/EntityScheduler",
    )

    private val PERIODIC_SCHEDULER_METHODS = setOf(
        "runTaskTimer",
        "runTaskTimerAsynchronously",
        "scheduleSyncRepeatingTask",
        "scheduleAsyncRepeatingTask",
        "runAtFixedRate",
    )

    private val ONESHOT_SCHEDULER_METHODS = setOf(
        "runTask",
        "runTaskAsynchronously",
        "runTaskLater",
        "runTaskLaterAsynchronously",
        "scheduleSyncDelayedTask",
        "scheduleAsyncDelayedTask",
        "run",
        "runDelayed",
    )

    private val COLLECTION_ADD_METHODS = setOf(
        "add", "addLast", "addFirst", "offer", "push", "put", "putIfAbsent",
    )

    private val COLLECTION_CLEAR_OR_REMOVE_METHODS = setOf(
        "clear", "remove", "removeIf", "removeAll", "poll", "evictAll", "invalidateAll",
    )

    private val EXECUTOR_TYPES = setOf(
        "java/util/concurrent/ExecutorService",
        "java/util/concurrent/ScheduledExecutorService",
        "java/util/concurrent/AbstractExecutorService",
        "java/util/concurrent/ThreadPoolExecutor",
        "java/util/concurrent/ScheduledThreadPoolExecutor",
        "java/util/concurrent/ForkJoinPool",
    )

    private val EXECUTOR_CONCRETE_TYPES = setOf(
        "java/util/concurrent/ThreadPoolExecutor",
        "java/util/concurrent/ScheduledThreadPoolExecutor",
        "java/util/concurrent/ForkJoinPool",
    )

    private val IGNORED_STATIC_FIELD_NAMES = setOf(
        "\$VALUES", "\$ENTRIES", "Companion", "INSTANCE", "\$assertionsDisabled", "serialVersionUID",
    )

    private val WEAK_CONTAINER_MARKERS = listOf(
        "WeakHashMap", "WeakReference", "SoftReference", "PhantomReference",
        "com/github/benmanes/caffeine/cache/", "com/google/common/cache/",
    )

    private val LIVE_SERVER_TYPES = listOf(
        "Lorg/bukkit/entity/Player;" to "Player",
        "Lorg/bukkit/entity/HumanEntity;" to "HumanEntity",
        "Lorg/bukkit/entity/LivingEntity;" to "LivingEntity",
        "Lorg/bukkit/entity/Entity;" to "Entity",
        "Lorg/bukkit/World;" to "World",
        "Lorg/bukkit/Chunk;" to "Chunk",
        "Lorg/bukkit/block/BlockState;" to "BlockState",
        "Lorg/bukkit/block/TileState;" to "TileState",
    )

    private val DISCONNECT_OR_UNLOAD_EVENTS = listOf(
        "Lorg/bukkit/event/player/PlayerQuitEvent;",
        "Lorg/bukkit/event/player/PlayerKickEvent;",
        "Lorg/bukkit/event/world/WorldUnloadEvent;",
        "Lorg/bukkit/event/world/ChunkUnloadEvent;",
        "Lorg/bukkit/event/server/PluginDisableEvent;",
    )

    fun run(path: Path, jar: JarInspection, sink: FindingSink) {
        val descriptor = jar.descriptor ?: return
        val ownNames = buildSet {
            jar.classes.filter { jar.isOwnCode(it.internalName) }.forEach { add(it.internalName) }
            descriptor.mainInternalName?.let { add(it) }
            descriptor.bootstrapper?.replace('.', '/')?.let { add(it) }
            descriptor.loader?.replace('.', '/')?.let { add(it) }
        }
        if (ownNames.isEmpty()) return

        val models = readOwnClasses(path, ownNames)
        if (models.isEmpty()) return

        val bukkitRunnableSubclasses = findSubclassesOf(models, "org/bukkit/scheduler/BukkitRunnable")
        val threadSubclasses = findSubclassesOf(models, "java/lang/Thread")
        val selfCancellingRunnables = findSelfCancellingRunnables(models, bukkitRunnableSubclasses)

        val disableReached = computeReachableMethods(
            models = models,
            seeds = findDisableSeeds(models, descriptor.mainInternalName),
        )
        val disconnectCleanupReached = computeReachableMethods(
            models = models,
            seeds = findDisconnectCleanupSeeds(models),
        )

        checkSchedulerTaskLeaks(
            models = models,
            bukkitRunnableSubclasses = bukkitRunnableSubclasses,
            selfCancellingRunnables = selfCancellingRunnables,
            disableReached = disableReached,
            sink = sink,
        )
        checkUnmanagedThreadsAndExecutors(
            models = models,
            threadSubclasses = threadSubclasses,
            disableReached = disableReached,
            sink = sink,
        )
        checkStaticEntityRetention(
            models = models,
            disableReached = disableReached,
            disconnectCleanupReached = disconnectCleanupReached,
            sink = sink,
        )
        checkClassloaderPinningHooks(
            models = models,
            disableReached = disableReached,
            sink = sink,
        )
    }

    private fun checkSchedulerTaskLeaks(
        models: Map<String, OwnClassModel>,
        bukkitRunnableSubclasses: Set<String>,
        selfCancellingRunnables: Set<String>,
        disableReached: Set<MethodRef>,
        sink: FindingSink,
    ) {
        val staticWrites = mutableListOf<StaticTaskWrite>()
        val discardedPeriodic = mutableListOf<DiscardedPeriodicSite>()
        val writtenStaticFields = mutableSetOf<String>()

        for (cls in models.values) {
            for (method in cls.methods.values) {
                val methodRef = MethodRef(cls.internalName, method.key)
                if (methodRef in disableReached) continue

                simulateMethodDataflow(
                    cls = cls,
                    method = method,
                    bukkitRunnableSubclasses = bukkitRunnableSubclasses,
                    selfCancellingRunnables = selfCancellingRunnables,
                    onStaticTaskWrite = { staticWrites += it },
                    onDiscardedPeriodic = { discardedPeriodic += it },
                    onStaticFieldMutated = { writtenStaticFields += it },
                )
            }
        }

        // Also catch static fields explicitly typed as BukkitTask / ScheduledTask (or collections thereof)
        // when written through helper methods whose return value was passed via parameter.
        for (cls in models.values) {
            for (field in cls.staticFields) {
                if (field.name in IGNORED_STATIC_FIELD_NAMES) continue
                val sigText = (field.signature ?: "") + field.descriptor
                val holdsTask = sigText.contains("Lorg/bukkit/scheduler/BukkitTask;") ||
                    sigText.contains("Lio/papermc/paper/threadedregions/scheduler/ScheduledTask;")
                if (holdsTask && field.fieldKey in writtenStaticFields && staticWrites.none { it.fieldKey == field.fieldKey }) {
                    val isColl = isCollectionOrMapDescriptor(field.descriptor)
                    staticWrites += StaticTaskWrite(
                        fieldKey = field.fieldKey,
                        inCollection = isColl,
                        periodic = true,
                        location = "${cls.internalName.replace('/', '.')}#${field.name}",
                        apiCall = field.descriptor,
                    )
                }
            }
        }

        val disableCancelsTasks = hasDisableTaskCancel(models, bukkitRunnableSubclasses, disableReached)
        val clearedStaticFieldsInDisable = findClearedStaticFields(models, disableReached)

        // 1. Report static task collections / fields not cleaned up in onDisable
        val groupedByField = staticWrites.groupBy { it.fieldKey }
        for ((fieldKey, writes) in groupedByField) {
            val isCleared = fieldKey in clearedStaticFieldsInDisable
            val inCollection = writes.any { it.inCollection }
            if (inCollection) {
                // Static collection accumulating task handles leaks memory unless cleared (and cancelled) in onDisable
                if (!isCleared || !disableCancelsTasks) {
                    val first = writes.first()
                    sink.add(
                        rule = JarRules.UNCANCELLED_SCHEDULER_TASK,
                        location = first.location,
                        detail = "Scheduled task handle from ${first.apiCall} is accumulated into static collection " +
                            "${fieldKey.replace('/', '.')} without ${if (!disableCancelsTasks && !isCleared) "cancelling tasks or clearing the collection" else if (!isCleared) "clearing the static collection" else "cancelling accumulated tasks"} in onDisable.",
                        evidence = writes.map { "${it.location} -> ${it.apiCall}" }.distinct().take(4),
                        severityOverride = Severity.CRITICAL,
                    )
                }
            } else {
                // Scalar static BukkitTask / ScheduledTask field
                if (!disableCancelsTasks && !isCleared) {
                    val first = writes.first()
                    sink.add(
                        rule = JarRules.UNCANCELLED_SCHEDULER_TASK,
                        location = first.location,
                        detail = "Scheduled task handle from ${first.apiCall} is stored in static field " +
                            "${fieldKey.replace('/', '.')} without task cancellation or null assignment in onDisable.",
                        evidence = writes.map { "${it.location} -> ${it.apiCall}" }.distinct().take(4),
                    )
                }
            }
        }

        // 2. Report discarded repeating task handles in dynamic methods (outside onEnable / constructors)
        if (!disableCancelsTasks && discardedPeriodic.isNotEmpty()) {
            for (site in discardedPeriodic.distinctBy { it.location }.take(5)) {
                sink.add(
                    rule = JarRules.UNCANCELLED_SCHEDULER_TASK,
                    location = site.location,
                    detail = "Repeating scheduler call ${site.apiCall} discards its returned task handle via POP " +
                        "inside a dynamic method, leaving each invocation unable to cancel the repeating timer.",
                    evidence = listOf("${site.location} discards return value of ${site.apiCall}"),
                )
            }
        }
    }

    private fun checkUnmanagedThreadsAndExecutors(
        models: Map<String, OwnClassModel>,
        threadSubclasses: Set<String>,
        disableReached: Set<MethodRef>,
        sink: FindingSink,
    ) {
        val sites = mutableListOf<UnmanagedWorkerSite>()

        for (cls in models.values) {
            for (method in cls.methods.values) {
                val methodRef = MethodRef(cls.internalName, method.key)
                if (methodRef in disableReached) continue

                val localCalls = method.insns.filterIsInstance<FlowInsn.Call>()
                val localClosesExecutor = localCalls.any { isExecutorShutdownCall(it) }
                val localCancelsTimer = localCalls.any { it.owner == "java/util/Timer" && it.name == "cancel" }
                val localJoinsThread = localCalls.any {
                    (it.owner == "java/lang/Thread" || it.owner in threadSubclasses) &&
                        (it.name == "join" || it.name == "interrupt")
                }

                for (insn in method.insns) {
                    when (insn) {
                        is FlowInsn.Call -> {
                            if (insn.owner == "java/util/concurrent/Executors" &&
                                insn.name.startsWith("new") &&
                                (insn.descriptor.endsWith("ExecutorService;") || insn.descriptor.endsWith("ScheduledExecutorService;"))
                            ) {
                                sites += UnmanagedWorkerSite(
                                    kind = WorkerKind.EXECUTOR,
                                    location = formatLoc(cls.internalName, method.name, insn.line),
                                    detail = "Executors.${insn.name}() creates an unmanaged thread pool",
                                    scopedClosedInSameMethod = localClosesExecutor,
                                )
                            } else if ((insn.owner == "java/lang/Thread" || insn.owner in threadSubclasses) &&
                                insn.name == "start" && insn.descriptor == "()V"
                            ) {
                                sites += UnmanagedWorkerSite(
                                    kind = WorkerKind.THREAD,
                                    location = formatLoc(cls.internalName, method.name, insn.line),
                                    detail = "${insn.owner.substringAfterLast('/')}.start() spawns a raw JVM thread outside Bukkit/Paper schedulers",
                                    scopedClosedInSameMethod = localJoinsThread,
                                )
                            }
                        }

                        is FlowInsn.TypeOp -> {
                            if (insn.opcode == Opcodes.NEW) {
                                if (insn.internalType in EXECUTOR_CONCRETE_TYPES) {
                                    sites += UnmanagedWorkerSite(
                                        kind = WorkerKind.EXECUTOR,
                                        location = formatLoc(cls.internalName, method.name, insn.line),
                                        detail = "new ${insn.internalType.substringAfterLast('/')}() creates an unmanaged executor pool",
                                        scopedClosedInSameMethod = localClosesExecutor,
                                    )
                                } else if (insn.internalType == "java/util/Timer") {
                                    sites += UnmanagedWorkerSite(
                                        kind = WorkerKind.TIMER,
                                        location = formatLoc(cls.internalName, method.name, insn.line),
                                        detail = "new java.util.Timer() spawns a background timer thread pinning contextClassLoader",
                                        scopedClosedInSameMethod = localCancelsTimer,
                                    )
                                }
                            }
                        }

                        else -> Unit
                    }
                }
            }
        }

        if (sites.isEmpty()) return

        val disableCalls = disableCallSequence(models, disableReached)
        val disableShutsDownExecutor = disableCalls.any { isExecutorShutdownCall(it) }
        val disableCancelsTimer = disableCalls.any { it.owner == "java/util/Timer" && it.name == "cancel" }
        val disableStopsThread = disableCalls.any {
            (it.owner == "java/lang/Thread" || it.owner in threadSubclasses) &&
                (it.name == "interrupt" || it.name == "join")
        }

        for (site in sites.filterNot { it.scopedClosedInSameMethod }.distinctBy { it.location to it.kind }) {
            val cleanedInDisable = when (site.kind) {
                WorkerKind.EXECUTOR -> disableShutsDownExecutor
                WorkerKind.TIMER -> disableCancelsTimer
                WorkerKind.THREAD -> disableStopsThread
            }
            if (!cleanedInDisable) {
                val expectedCleanup = when (site.kind) {
                    WorkerKind.EXECUTOR -> "ExecutorService.shutdown() or shutdownNow()"
                    WorkerKind.TIMER -> "Timer.cancel()"
                    WorkerKind.THREAD -> "Thread.interrupt() or join()"
                }
                sink.add(
                    rule = JarRules.UNMANAGED_THREAD_OR_EXECUTOR_LEAK,
                    location = site.location,
                    detail = "${site.detail}, and no $expectedCleanup call is reachable from onDisable().",
                    evidence = listOf("${site.location}: ${site.detail}"),
                )
            }
        }
    }

    private fun checkStaticEntityRetention(
        models: Map<String, OwnClassModel>,
        disableReached: Set<MethodRef>,
        disconnectCleanupReached: Set<MethodRef>,
        sink: FindingSink,
    ) {
        val writtenOrMutatedFields = findMutatedStaticFields(models)
        val cleanupMethods = disableReached + disconnectCleanupReached
        val cleanedFields = findClearedOrRemovedStaticFields(models, cleanupMethods)

        for (cls in models.values) {
            for (field in cls.staticFields) {
                if (field.name in IGNORED_STATIC_FIELD_NAMES) continue
                val combinedType = (field.signature ?: "") + " " + field.descriptor
                if (WEAK_CONTAINER_MARKERS.any { combinedType.contains(it) }) continue

                val matchedEntity = LIVE_SERVER_TYPES.firstOrNull { (descToken, _) ->
                    combinedType.contains(descToken)
                } ?: continue

                if (field.fieldKey !in writtenOrMutatedFields) continue
                if (field.fieldKey in cleanedFields) continue

                val location = "${cls.internalName.replace('/', '.')}#${field.name}"
                val containerKind = if (isCollectionOrMapDescriptor(field.descriptor)) "static collection" else "static field"
                sink.add(
                    rule = JarRules.STATIC_SERVER_ENTITY_RETENTION,
                    location = location,
                    detail = "Live ${matchedEntity.second} reference is retained in $containerKind $location " +
                        "without clearing in onDisable() or disconnect/unload event handlers.",
                    evidence = listOf("field ${field.name}: ${field.signature ?: field.descriptor}"),
                )
            }
        }
    }

    private fun checkClassloaderPinningHooks(
        models: Map<String, OwnClassModel>,
        disableReached: Set<MethodRef>,
        sink: FindingSink,
    ) {
        val disableCalls = disableCallSequence(models, disableReached)
        val removesShutdownHook = disableCalls.any {
            it.owner == "java/lang/Runtime" && it.name == "removeShutdownHook"
        }
        val deregistersDriver = disableCalls.any {
            it.owner == "java/sql/DriverManager" && it.name == "deregisterDriver"
        }
        val removesJulHandler = disableCalls.any {
            it.owner == "java/util/logging/Logger" && it.name == "removeHandler"
        }

        var anyThreadLocalRemove = false
        for (cls in models.values) {
            for (method in cls.methods.values) {
                for (insn in method.insns) {
                    if (insn is FlowInsn.Call) {
                        if ((insn.owner == "java/lang/ThreadLocal" || insn.owner == "java/lang/InheritableThreadLocal") &&
                            insn.name == "remove" && insn.descriptor == "()V"
                        ) {
                            anyThreadLocalRemove = true
                        }
                        if (MethodRef(cls.internalName, method.key) !in disableReached) {
                            val loc = formatLoc(cls.internalName, method.name, insn.line)
                            if (insn.owner == "java/lang/Runtime" && insn.name == "addShutdownHook" && !removesShutdownHook) {
                                sink.add(
                                    rule = JarRules.CLASSLOADER_PINNING_HOOK,
                                    location = loc,
                                    detail = "Runtime.addShutdownHook registers a JVM-wide hook Thread without " +
                                        "Runtime.removeShutdownHook in onDisable(), pinning PaperPluginClassLoader until JVM exit.",
                                    evidence = listOf("$loc calls java.lang.Runtime#addShutdownHook"),
                                )
                            } else if (insn.owner == "java/sql/DriverManager" && insn.name == "registerDriver" && !deregistersDriver) {
                                sink.add(
                                    rule = JarRules.CLASSLOADER_PINNING_HOOK,
                                    location = loc,
                                    detail = "DriverManager.registerDriver registers a JDBC driver loaded by the plugin " +
                                        "without DriverManager.deregisterDriver in onDisable().",
                                    evidence = listOf("$loc calls java.sql.DriverManager#registerDriver"),
                                )
                            } else if (insn.owner == "java/util/logging/Logger" && insn.name == "addHandler" && !removesJulHandler) {
                                sink.add(
                                    rule = JarRules.CLASSLOADER_PINNING_HOOK,
                                    location = loc,
                                    detail = "Logger.addHandler attaches a custom Handler to a JUL Logger without " +
                                        "Logger.removeHandler in onDisable().",
                                    evidence = listOf("$loc calls java.util.logging.Logger#addHandler"),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!anyThreadLocalRemove) {
            for (cls in models.values) {
                for (field in cls.staticFields) {
                    if (field.descriptor == "Ljava/lang/ThreadLocal;" ||
                        field.descriptor == "Ljava/lang/InheritableThreadLocal;"
                    ) {
                        val loc = "${cls.internalName.replace('/', '.')}#${field.name}"
                        sink.add(
                            rule = JarRules.CLASSLOADER_PINNING_HOOK,
                            location = loc,
                            detail = "Static ThreadLocal field $loc never invokes ThreadLocal.remove() anywhere in " +
                                "plugin bytecode, retaining values inside shared server and Netty ThreadLocalMaps.",
                            evidence = listOf("static field ${field.name}: ${field.descriptor}"),
                        )
                    }
                }
            }
        }
    }

    private fun simulateMethodDataflow(
        cls: OwnClassModel,
        method: OwnMethodModel,
        bukkitRunnableSubclasses: Set<String>,
        selfCancellingRunnables: Set<String>,
        onStaticTaskWrite: (StaticTaskWrite) -> Unit,
        onDiscardedPeriodic: (DiscardedPeriodicSite) -> Unit,
        onStaticFieldMutated: (String) -> Unit,
    ) {
        val stack = ArrayDeque<TaintVal>()
        val locals = HashMap<Int, TaintVal>()

        fun popSafe(): TaintVal = if (stack.isNotEmpty()) stack.removeLast() else TaintVal.Unknown
        fun peekSafe(): TaintVal = if (stack.isNotEmpty()) stack.last() else TaintVal.Unknown
        fun push(v: TaintVal) {
            stack.addLast(v)
        }

        val isStartupOrInit = method.name == "<init>" || method.name == "<clinit>" ||
            method.name == "onEnable" || method.name == "onLoad"

        for (insn in method.insns) {
            when (insn) {
                is FlowInsn.ConstNull -> push(TaintVal.NullConst)

                is FlowInsn.TypeOp -> {
                    when (insn.opcode) {
                        Opcodes.NEW -> push(TaintVal.NewObject(insn.internalType, insn.line))
                        Opcodes.CHECKCAST -> {
                            // Top of stack keeps its taint identity across casts
                        }
                        else -> {
                            popSafe()
                            push(TaintVal.Unknown)
                        }
                    }
                }

                is FlowInsn.FieldOp -> {
                    when (insn.opcode) {
                        Opcodes.GETSTATIC -> {
                            push(TaintVal.StaticRef(insn.owner, insn.name, insn.descriptor))
                        }

                        Opcodes.PUTSTATIC -> {
                            val value = popSafe()
                            val fKey = "${insn.owner}#${insn.name}"
                            if (value !is TaintVal.NullConst) {
                                onStaticFieldMutated(fKey)
                            }
                            if (value is TaintVal.TaskHandle) {
                                value.escapedOrDuplicated = true
                                onStaticTaskWrite(
                                    StaticTaskWrite(
                                        fieldKey = fKey,
                                        inCollection = false,
                                        periodic = value.periodic,
                                        location = formatLoc(cls.internalName, method.name, insn.line ?: value.line),
                                        apiCall = value.apiCall,
                                    )
                                )
                            }
                        }

                        Opcodes.GETFIELD -> {
                            popSafe()
                            push(TaintVal.Unknown)
                        }

                        Opcodes.PUTFIELD -> {
                            val value = popSafe()
                            popSafe() // receiver
                            if (value is TaintVal.TaskHandle) {
                                value.escapedOrDuplicated = true
                            }
                        }
                    }
                }

                is FlowInsn.VarOp -> {
                    when (insn.opcode) {
                        Opcodes.ASTORE, Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE -> {
                            val v = popSafe()
                            if (v is TaintVal.TaskHandle) {
                                v.escapedOrDuplicated = true
                            }
                            locals[insn.slot] = v
                        }

                        Opcodes.ALOAD, Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD -> {
                            push(locals[insn.slot] ?: TaintVal.Unknown)
                        }
                    }
                }

                is FlowInsn.StackOp -> {
                    when (insn.opcode) {
                        Opcodes.DUP -> {
                            val top = peekSafe()
                            if (top is TaintVal.TaskHandle) {
                                top.escapedOrDuplicated = true
                            }
                            push(top)
                        }

                        Opcodes.POP, Opcodes.POP2 -> {
                            val popped = popSafe()
                            if (popped is TaintVal.TaskHandle &&
                                popped.periodic &&
                                !popped.escapedOrDuplicated &&
                                !isStartupOrInit
                            ) {
                                onDiscardedPeriodic(
                                    DiscardedPeriodicSite(
                                        location = formatLoc(cls.internalName, method.name, insn.line ?: popped.line),
                                        apiCall = popped.apiCall,
                                    )
                                )
                            }
                        }

                        Opcodes.SWAP -> {
                            if (stack.size >= 2) {
                                val a = stack.removeLast()
                                val b = stack.removeLast()
                                stack.addLast(a)
                                stack.addLast(b)
                            }
                        }
                    }
                }

                is FlowInsn.Call -> {
                    val argCount = Type.getArgumentTypes(insn.descriptor).size
                    val args = MutableList(argCount) { popSafe() }.asReversed()
                    val receiver = if (insn.opcode == Opcodes.INVOKESTATIC) null else popSafe()

                    // Detect static collection / map mutation: staticCollection.add(taskHandle) or staticMap.put(k, taskHandle)
                    if (receiver is TaintVal.StaticRef && insn.name in COLLECTION_ADD_METHODS) {
                        onStaticFieldMutated(receiver.fieldKey)
                        val taskArg = args.filterIsInstance<TaintVal.TaskHandle>().firstOrNull()
                        if (taskArg != null) {
                            taskArg.escapedOrDuplicated = true
                            onStaticTaskWrite(
                                StaticTaskWrite(
                                    fieldKey = receiver.fieldKey,
                                    inCollection = true,
                                    periodic = taskArg.periodic,
                                    location = formatLoc(cls.internalName, method.name, insn.line ?: taskArg.line),
                                    apiCall = taskArg.apiCall,
                                )
                            )
                        }
                    } else {
                        args.filterIsInstance<TaintVal.TaskHandle>().forEach { it.escapedOrDuplicated = true }
                    }

                    val returnSort = Type.getReturnType(insn.descriptor).sort
                    if (returnSort != Type.VOID) {
                        val scheduled = classifySchedulerCall(
                            insn = insn,
                            receiver = receiver,
                            args = args,
                            bukkitRunnableSubclasses = bukkitRunnableSubclasses,
                            selfCancellingRunnables = selfCancellingRunnables,
                        )
                        push(scheduled ?: TaintVal.Unknown)
                    }
                }

                is FlowInsn.DynamicTarget -> {
                    val argCount = Type.getArgumentTypes(insn.callSiteDescriptor).size
                    repeat(argCount) {
                        val v = popSafe()
                        if (v is TaintVal.TaskHandle) v.escapedOrDuplicated = true
                    }
                    if (Type.getReturnType(insn.callSiteDescriptor).sort != Type.VOID) {
                        push(TaintVal.Unknown)
                    }
                }

                is FlowInsn.ReturnOp -> {
                    if (insn.opcode != Opcodes.RETURN) {
                        val ret = popSafe()
                        if (ret is TaintVal.TaskHandle) ret.escapedOrDuplicated = true
                    }
                    stack.clear()
                }

                is FlowInsn.OtherOp -> {
                    // Conservative push for constant/ldc opcodes so stack stays aligned across expressions
                    if (insn.opcode in Opcodes.ICONST_M1..Opcodes.LDC) {
                        push(TaintVal.Unknown)
                    }
                }
            }
        }
    }

    private fun classifySchedulerCall(
        insn: FlowInsn.Call,
        receiver: TaintVal?,
        args: List<TaintVal>,
        bukkitRunnableSubclasses: Set<String>,
        selfCancellingRunnables: Set<String>,
    ): TaintVal.TaskHandle? {
        val isSchedulerOwner = insn.owner in BUKKIT_SCHEDULERS ||
            insn.owner in PAPER_SCHEDULERS ||
            insn.owner in bukkitRunnableSubclasses
        if (!isSchedulerOwner) return null

        val isPeriodic = insn.name in PERIODIC_SCHEDULER_METHODS
        val isOneshot = insn.name in ONESHOT_SCHEDULER_METHODS
        if (!isPeriodic && !isOneshot) return null

        val retDesc = Type.getReturnType(insn.descriptor).descriptor
        val returnsTaskOrId = retDesc == "Lorg/bukkit/scheduler/BukkitTask;" ||
            retDesc == "Lio/papermc/paper/threadedregions/scheduler/ScheduledTask;" ||
            (isPeriodic && retDesc == "I")
        if (!returnsTaskOrId) return null

        // If a BukkitRunnable subclass calls cancel() inside its own run() method, it is self-terminating!
        if (insn.owner in selfCancellingRunnables) return null
        if (receiver is TaintVal.NewObject && receiver.internalType in selfCancellingRunnables) return null
        if (args.any { it is TaintVal.NewObject && it.internalType in selfCancellingRunnables }) return null

        val shortOwner = insn.owner.substringAfterLast('/')
        return TaintVal.TaskHandle(
            periodic = isPeriodic,
            apiCall = "$shortOwner#${insn.name}",
            line = insn.line,
        )
    }

    private fun hasDisableTaskCancel(
        models: Map<String, OwnClassModel>,
        bukkitRunnableSubclasses: Set<String>,
        disableReached: Set<MethodRef>,
    ): Boolean = disableCallSequence(models, disableReached).any { call ->
        ((call.owner == "org/bukkit/scheduler/BukkitTask" ||
            call.owner == "io/papermc/paper/threadedregions/scheduler/ScheduledTask" ||
            call.owner == "org/bukkit/scheduler/BukkitRunnable" ||
            call.owner in bukkitRunnableSubclasses) && call.name == "cancel") ||
            (call.owner == "org/bukkit/scheduler/BukkitScheduler" && (call.name == "cancelTask" || call.name == "cancelTasks")) ||
            (call.owner in PAPER_SCHEDULERS && call.name == "cancelTasks")
    }

    private fun findClearedStaticFields(
        models: Map<String, OwnClassModel>,
        reached: Set<MethodRef>,
    ): Set<String> = findClearedOrRemovedStaticFields(models, reached, allowSingleKeyRemove = false)

    private fun findClearedOrRemovedStaticFields(
        models: Map<String, OwnClassModel>,
        reached: Set<MethodRef>,
        allowSingleKeyRemove: Boolean = true,
    ): Set<String> {
        val cleaned = HashSet<String>()
        for (ref in reached) {
            val method = models[ref.owner]?.methods?.get(ref.key) ?: continue
            var prevWasNull = false
            var lastStaticRef: String? = null
            val localRefs = HashMap<Int, String>()
            for (insn in method.insns) {
                when (insn) {
                    is FlowInsn.ConstNull -> prevWasNull = true
                    is FlowInsn.FieldOp -> {
                        if (insn.opcode == Opcodes.PUTSTATIC && prevWasNull) {
                            cleaned += "${insn.owner}#${insn.name}"
                        } else if (insn.opcode == Opcodes.GETSTATIC) {
                            lastStaticRef = "${insn.owner}#${insn.name}"
                        }
                        prevWasNull = false
                    }

                    is FlowInsn.VarOp -> {
                        if (insn.opcode == Opcodes.ASTORE && lastStaticRef != null) {
                            localRefs[insn.slot] = lastStaticRef
                        } else if (insn.opcode == Opcodes.ALOAD) {
                            localRefs[insn.slot]?.let { lastStaticRef = it }
                        }
                        prevWasNull = false
                    }

                    is FlowInsn.Call -> {
                        if (lastStaticRef != null) {
                            if (insn.name == "clear" ||
                                (allowSingleKeyRemove && insn.name in COLLECTION_CLEAR_OR_REMOVE_METHODS)
                            ) {
                                cleaned += lastStaticRef
                            }
                        }
                        prevWasNull = false
                    }

                    else -> prevWasNull = false
                }
            }
        }
        return cleaned
    }

    private fun findMutatedStaticFields(models: Map<String, OwnClassModel>): Set<String> {
        val fieldDefs = HashMap<String, StaticFieldDef>()
        for (cls in models.values) {
            for (f in cls.staticFields) fieldDefs[f.fieldKey] = f
        }
        val mutated = HashSet<String>()
        for (cls in models.values) {
            for (method in cls.methods.values) {
                var lastStaticRef: String? = null
                var prevNull = false
                val localRefs = HashMap<Int, String>()
                for (insn in method.insns) {
                    when (insn) {
                        is FlowInsn.ConstNull -> prevNull = true
                        is FlowInsn.FieldOp -> {
                            val key = "${insn.owner}#${insn.name}"
                            if (insn.opcode == Opcodes.PUTSTATIC && !prevNull) {
                                val def = fieldDefs[key]
                                val isColl = def != null && isCollectionOrMapDescriptor(def.descriptor)
                                if (method.name != "<clinit>" || !isColl) {
                                    mutated += key
                                }
                            }
                            if (insn.opcode == Opcodes.GETSTATIC) {
                                lastStaticRef = key
                            }
                            prevNull = false
                        }

                        is FlowInsn.VarOp -> {
                            if (insn.opcode == Opcodes.ASTORE && lastStaticRef != null) {
                                localRefs[insn.slot] = lastStaticRef
                            } else if (insn.opcode == Opcodes.ALOAD) {
                                localRefs[insn.slot]?.let { lastStaticRef = it }
                            }
                            prevNull = false
                        }

                        is FlowInsn.Call -> {
                            if (lastStaticRef != null && insn.name in COLLECTION_ADD_METHODS) {
                                mutated += lastStaticRef
                            }
                            prevNull = false
                        }

                        else -> prevNull = false
                    }
                }
            }
        }
        return mutated
    }

    private fun isExecutorShutdownCall(call: FlowInsn.Call): Boolean =
        (call.owner in EXECUTOR_TYPES || call.owner == "java/lang/AutoCloseable") &&
            (call.name == "shutdown" || call.name == "shutdownNow" || call.name == "close")

    private fun disableCallSequence(
        models: Map<String, OwnClassModel>,
        disableReached: Set<MethodRef>,
    ): Sequence<FlowInsn.Call> = sequence {
        for (ref in disableReached) {
            val method = models[ref.owner]?.methods?.get(ref.key) ?: continue
            for (insn in method.insns) {
                if (insn is FlowInsn.Call) yield(insn)
                if (insn is FlowInsn.DynamicTarget) {
                    yield(FlowInsn.Call(Opcodes.INVOKEINTERFACE, insn.owner, insn.name, insn.descriptor, insn.line))
                }
            }
        }
    }

    private fun findDisableSeeds(models: Map<String, OwnClassModel>, mainInternal: String?): Set<MethodRef> = buildSet {
        for (cls in models.values) {
            for (method in cls.methods.values) {
                if (method.name == "onDisable" && method.descriptor == "()V") {
                    add(MethodRef(cls.internalName, method.key))
                }
            }
        }
        if (mainInternal != null && models[mainInternal]?.methods?.containsKey("onDisable()V") == true) {
            add(MethodRef(mainInternal, "onDisable()V"))
        }
    }

    private fun findDisconnectCleanupSeeds(models: Map<String, OwnClassModel>): Set<MethodRef> = buildSet {
        for (cls in models.values) {
            for (method in cls.methods.values) {
                if (DISCONNECT_OR_UNLOAD_EVENTS.any { method.descriptor.contains(it) }) {
                    add(MethodRef(cls.internalName, method.key))
                }
            }
        }
    }

    private fun computeReachableMethods(
        models: Map<String, OwnClassModel>,
        seeds: Set<MethodRef>,
    ): Set<MethodRef> {
        if (seeds.isEmpty()) return emptySet()
        val subtypesBySuper = HashMap<String, MutableSet<String>>()
        for (cls in models.values) {
            cls.superName?.let { subtypesBySuper.getOrPut(it) { mutableSetOf() } += cls.internalName }
            for (iface in cls.interfaces) {
                subtypesBySuper.getOrPut(iface) { mutableSetOf() } += cls.internalName
            }
        }

        val reached = HashSet<MethodRef>()
        val queue = ArrayDeque(seeds)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (!reached.add(cur)) continue
            val method = models[cur.owner]?.methods?.get(cur.key) ?: continue
            for (insn in method.insns) {
                when (insn) {
                    is FlowInsn.Call -> {
                        val targetKey = insn.name + insn.descriptor
                        if (models[insn.owner]?.methods?.containsKey(targetKey) == true) {
                            queue.addLast(MethodRef(insn.owner, targetKey))
                        }
                        subtypesBySuper[insn.owner]?.forEach { sub ->
                            if (models[sub]?.methods?.containsKey(targetKey) == true) {
                                queue.addLast(MethodRef(sub, targetKey))
                            }
                        }
                    }

                    is FlowInsn.DynamicTarget -> {
                        val targetKey = insn.name + insn.descriptor
                        if (models[insn.owner]?.methods?.containsKey(targetKey) == true) {
                            queue.addLast(MethodRef(insn.owner, targetKey))
                        }
                    }

                    else -> Unit
                }
            }
        }
        return reached
    }

    private fun findSubclassesOf(models: Map<String, OwnClassModel>, rootInternal: String): Set<String> {
        val result = HashSet<String>()
        var changed = true
        while (changed) {
            changed = false
            for (cls in models.values) {
                if (cls.internalName !in result &&
                    (cls.superName == rootInternal || cls.superName in result ||
                        cls.interfaces.any { it == rootInternal || it in result })
                ) {
                    result += cls.internalName
                    changed = true
                }
            }
        }
        return result
    }

    private fun findSelfCancellingRunnables(
        models: Map<String, OwnClassModel>,
        bukkitRunnableSubclasses: Set<String>,
    ): Set<String> = bukkitRunnableSubclasses.filterTo(HashSet()) { sub ->
        val runMethod = models[sub]?.methods?.get("run()V") ?: return@filterTo false
        runMethod.insns.any { insn ->
            insn is FlowInsn.Call &&
                (insn.owner == sub || insn.owner == "org/bukkit/scheduler/BukkitRunnable") &&
                insn.name == "cancel" && insn.descriptor == "()V"
        }
    }

    private fun isCollectionOrMapDescriptor(desc: String): Boolean =
        desc.startsWith("Ljava/util/Collection") ||
            desc.startsWith("Ljava/util/List") ||
            desc.startsWith("Ljava/util/ArrayList") ||
            desc.startsWith("Ljava/util/LinkedList") ||
            desc.startsWith("Ljava/util/Set") ||
            desc.startsWith("Ljava/util/HashSet") ||
            desc.startsWith("Ljava/util/Queue") ||
            desc.startsWith("Ljava/util/Deque") ||
            desc.startsWith("Ljava/util/Map") ||
            desc.startsWith("Ljava/util/HashMap") ||
            desc.startsWith("Ljava/util/concurrent/ConcurrentHashMap") ||
            desc.startsWith("Ljava/util/concurrent/ConcurrentMap") ||
            desc.startsWith("Ljava/util/concurrent/CopyOnWriteArray")

    private fun formatLoc(internalClass: String, methodName: String, line: Int?): String {
        val base = "${internalClass.replace('/', '.')}#$methodName"
        return if (line != null && line >= 1) "$base:$line" else base
    }

    private fun readOwnClasses(path: Path, ownNames: Set<String>): Map<String, OwnClassModel> {
        val out = LinkedHashMap<String, OwnClassModel>()
        ZipFile(path.toFile()).use { zip ->
            val entries = zip.entries().toList()
            for (entry in entries) {
                if (entry.isDirectory || !entry.name.endsWith(".class")) continue
                val candidateName = entry.name.removeSuffix(".class")
                if (candidateName !in ownNames) continue
                try {
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val model = parseOwnClass(bytes)
                    if (model.internalName in ownNames) {
                        out[model.internalName] = model
                    }
                } catch (_: Exception) {
                    // Unreadable classes are already reported by HygieneChecks
                }
            }
        }
        return out
    }

    private fun parseOwnClass(bytes: ByteArray): OwnClassModel {
        var internalName = ""
        var superName: String? = null
        var interfaces: List<String> = emptyList()
        val staticFields = mutableListOf<StaticFieldDef>()
        val methods = LinkedHashMap<String, OwnMethodModel>()

        val visitor = object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(
                version: Int,
                access: Int,
                name: String,
                signature: String?,
                superCls: String?,
                ifaces: Array<out String>?,
            ) {
                internalName = name
                superName = superCls
                interfaces = ifaces?.toList() ?: emptyList()
            }

            override fun visitField(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                value: Any?,
            ): FieldVisitor? {
                if (access and Opcodes.ACC_STATIC != 0) {
                    staticFields += StaticFieldDef(internalName, name, descriptor, signature, access)
                }
                return null
            }

            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                val insns = mutableListOf<FlowInsn>()
                var isEventHandler = false
                return object : MethodVisitor(Opcodes.ASM9) {
                    private var currentLine: Int? = null

                    override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor? {
                        if (desc == "Lorg/bukkit/event/EventHandler;") {
                            isEventHandler = true
                        }
                        return null
                    }

                    override fun visitLineNumber(line: Int, start: Label?) {
                        currentLine = line
                    }

                    override fun visitInsn(opcode: Int) {
                        when (opcode) {
                            Opcodes.ACONST_NULL -> insns += FlowInsn.ConstNull(currentLine)
                            Opcodes.DUP, Opcodes.POP, Opcodes.POP2, Opcodes.SWAP ->
                                insns += FlowInsn.StackOp(opcode, currentLine)
                            in Opcodes.IRETURN..Opcodes.RETURN ->
                                insns += FlowInsn.ReturnOp(opcode, currentLine)
                            else -> insns += FlowInsn.OtherOp(opcode, currentLine)
                        }
                    }

                    override fun visitIntInsn(opcode: Int, operand: Int) {
                        insns += FlowInsn.OtherOp(opcode, currentLine)
                    }

                    override fun visitLdcInsn(value: Any?) {
                        insns += FlowInsn.OtherOp(Opcodes.LDC, currentLine)
                    }

                    override fun visitVarInsn(opcode: Int, varIndex: Int) {
                        insns += FlowInsn.VarOp(opcode, varIndex, currentLine)
                    }

                    override fun visitTypeInsn(opcode: Int, type: String) {
                        insns += FlowInsn.TypeOp(opcode, type, currentLine)
                    }

                    override fun visitFieldInsn(opcode: Int, owner: String, fName: String, fDesc: String) {
                        insns += FlowInsn.FieldOp(opcode, owner, fName, fDesc, currentLine)
                    }

                    override fun visitMethodInsn(
                        opcode: Int,
                        owner: String,
                        mName: String,
                        mDesc: String,
                        isInterface: Boolean,
                    ) {
                        insns += FlowInsn.Call(opcode, owner, mName, mDesc, currentLine)
                    }

                    override fun visitInvokeDynamicInsn(
                        dynName: String,
                        dynDesc: String,
                        bootstrapMethodHandle: Handle?,
                        vararg bootstrapMethodArguments: Any?,
                    ) {
                        val handle = bootstrapMethodArguments.filterIsInstance<Handle>().firstOrNull()
                        if (handle != null) {
                            insns += FlowInsn.DynamicTarget(handle.owner, handle.name, handle.desc, dynDesc, currentLine)
                        } else {
                            insns += FlowInsn.OtherOp(Opcodes.INVOKEDYNAMIC, currentLine)
                        }
                    }

                    override fun visitEnd() {
                        val model = OwnMethodModel(internalName, name, descriptor, access, isEventHandler, insns)
                        methods[model.key] = model
                    }
                }
            }
        }

        ClassReader(bytes).accept(visitor, ClassReader.SKIP_FRAMES)
        return OwnClassModel(internalName, superName, interfaces, staticFields, methods)
    }
}
