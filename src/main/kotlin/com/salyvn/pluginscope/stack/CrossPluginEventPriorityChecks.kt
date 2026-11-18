package com.salyvn.pluginscope.stack

import com.salyvn.pluginscope.core.DescriptorKind
import com.salyvn.pluginscope.core.FindingSink
import com.salyvn.pluginscope.core.JarInspection
import com.salyvn.pluginscope.core.Severity
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Multi-JAR `@EventHandler` priority dispatch and cancellation conflict detector (`S072`).
 *
 * Scans listener methods across all installed plugins to verify Bukkit/Paper `HandlerList` priority
 * contracts:
 * 1. `EventPriority.MONITOR` outcome mutation (`CRITICAL`): A plugin mutates `setCancelled` or event
 *    result inside a `MONITOR` handler while at least one other installed plugin observes the same
 *    event type.
 * 2. Uncoordinated cross-plugin cancel race or forced `setCancelled(false)` override (`WARNING`):
 *    Multiple plugins mutate cancellation at the exact same `EventPriority` with no load-order
 *    dependency between them, or a `HIGH`/`HIGHEST` handler with `ignoreCancelled = false` forces
 *    `setCancelled(false)` over another plugin's cancellation without explicit load-order coupling.
 */
internal object CrossPluginEventPriorityChecks {

    internal data class PluginCandidate(
        val path: Path,
        val jar: JarInspection,
        val name: String,
        val provides: List<String>,
    )

    private enum class EventPriorityLevel(val rank: Int) {
        LOWEST(0),
        LOW(1),
        NORMAL(2),
        HIGH(3),
        HIGHEST(4),
        MONITOR(5);

        companion object {
            fun fromName(raw: String?): EventPriorityLevel =
                entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: NORMAL
        }
    }

    private enum class MutationKind {
        NONE,
        CANCEL_TRUE,
        UNCANCEL_FALSE,
        CANCEL_DYNAMIC,
        RESULT_MUTATION;

        val mutatesOutcome: Boolean get() = this != NONE
        val cancelsEvent: Boolean get() = this == CANCEL_TRUE || this == CANCEL_DYNAMIC
    }

    private data class ListenerHandler(
        val plugin: PluginCandidate,
        val className: String,
        val methodName: String,
        val eventInternalName: String,
        val priority: EventPriorityLevel,
        val ignoreCancelled: Boolean,
        val mutation: MutationKind,
        val mutationCall: String?,
        val line: Int?,
    ) {
        val locationLabel: String
            get() {
                val suffix = line?.let { ":$it" } ?: ""
                return "${plugin.name} (${className.substringAfterLast('/')}#$methodName$suffix)"
            }
    }

    private val RESULT_MUTATION_METHODS = setOf(
        "setResult(Lorg/bukkit/event/Event\$Result;)V",
        "setUseInteractedBlock(Lorg/bukkit/event/Event\$Result;)V",
        "setUseItemInHand(Lorg/bukkit/event/Event\$Result;)V",
        "disallow(Lorg/bukkit/event/player/PlayerLoginEvent\$Result;Ljava/lang/String;)V",
        "disallow(Lorg/bukkit/event/player/PlayerLoginEvent\$Result;Lnet/kyori/adventure/text/Component;)V",
        "disallow(Lorg/bukkit/event/player/AsyncPlayerPreLoginEvent\$Result;Ljava/lang/String;)V",
        "disallow(Lorg/bukkit/event/player/AsyncPlayerPreLoginEvent\$Result;Lnet/kyori/adventure/text/Component;)V",
    )

    fun run(plugins: List<PluginCandidate>, sink: FindingSink): Int {
        if (plugins.size < 2) return 0

        val orderedPairs = collectOrderedPluginPairs(plugins)
        val handlersByEvent = LinkedHashMap<String, MutableList<ListenerHandler>>()

        for (plugin in plugins) {
            val handlers = scanPluginHandlers(plugin)
            for (h in handlers) {
                handlersByEvent.getOrPut(h.eventInternalName) { mutableListOf() } += h
            }
        }

        var conflictCount = 0

        for ((eventInternal, handlers) in handlersByEvent) {
            val distinctPluginNames = handlers.map { it.plugin.name.lowercase() }.distinct()
            if (distinctPluginNames.size < 2) continue

            val eventShort = eventInternal.substringAfterLast('/')
            val eventDotted = eventInternal.replace('/', '.')

            // 1. CRITICAL: EventPriority.MONITOR handler mutating cancellation or event result
            val monitorMutators = handlers.filter {
                it.priority == EventPriorityLevel.MONITOR && it.mutation.mutatesOutcome
            }
            for (offender in monitorMutators) {
                val peers = handlers.filterNot { it.plugin.name.equals(offender.plugin.name, ignoreCase = true) }
                if (peers.isEmpty()) continue
                val peerSummary = peers.map { "${it.plugin.name}@${it.priority.name}" }.distinct().take(4)
                conflictCount++
                sink.add(
                    rule = StackRules.EVENT_PRIORITY_CANCEL_RACE,
                    location = "$eventShort: ${offender.plugin.name} [MONITOR]",
                    detail = "${offender.plugin.name} mutates $eventDotted outcome (${offender.mutationCall ?: "setCancelled"}) " +
                        "inside @EventHandler(priority = MONITOR) while ${peers.map { it.plugin.name }.distinct().joinToString(", ")} " +
                        "also observe(s) $eventShort, violating Bukkit's read-only MONITOR contract.",
                    evidence = listOf(
                        "${offender.locationLabel} @MONITOR -> ${offender.mutationCall ?: "setCancelled"}",
                        "co-listening plugins: ${peerSummary.joinToString(", ")}",
                    ),
                    severityOverride = Severity.CRITICAL,
                )
            }

            // 2. WARNING: Cross-plugin un-cancel override (setCancelled(false) with ignoreCancelled=false)
            //    or uncoordinated same-priority setCancelled race without load-order edge
            val nonMonitorMutators = handlers.filter {
                it.priority != EventPriorityLevel.MONITOR && it.mutation != MutationKind.NONE
            }
            if (nonMonitorMutators.isEmpty()) continue

            val reportedPairs = HashSet<Set<String>>()

            // 2a. Forced setCancelled(false) overriding an earlier/equal plugin's cancellation
            val cancellers = nonMonitorMutators.filter { it.mutation.cancelsEvent }
            val uncancellers = nonMonitorMutators.filter {
                it.mutation == MutationKind.UNCANCEL_FALSE &&
                    !it.ignoreCancelled &&
                    it.priority.rank >= EventPriorityLevel.HIGH.rank
            }
            for (unc in uncancellers) {
                for (can in cancellers) {
                    if (unc.plugin.name.equals(can.plugin.name, ignoreCase = true)) continue
                    if (can.priority.rank > unc.priority.rank) continue
                    val pairKey = setOf(unc.plugin.name.lowercase(), can.plugin.name.lowercase())
                    if (pairKey in orderedPairs || !reportedPairs.add(pairKey)) continue

                    conflictCount++
                    sink.add(
                        rule = StackRules.EVENT_PRIORITY_CANCEL_RACE,
                        location = "$eventShort: ${can.plugin.name} vs ${unc.plugin.name}",
                        detail = "${unc.plugin.name} (@${unc.priority.name}, ignoreCancelled=false) calls setCancelled(false) " +
                            "on $eventDotted without an explicit load-order dependency, overriding cancellations from " +
                            "${can.plugin.name} (@${can.priority.name}).",
                        evidence = listOf(
                            "${can.locationLabel} @${can.priority.name} -> ${can.mutationCall ?: "setCancelled(true)"}",
                            "${unc.locationLabel} @${unc.priority.name} (ignoreCancelled=false) -> ${unc.mutationCall ?: "setCancelled(false)"}",
                        ),
                        severityOverride = Severity.WARNING,
                    )
                }
            }

            // 2b. Multiple plugins mutating setCancelled at the exact same EventPriority with no load-order edge
            val byPriority = nonMonitorMutators
                .filter {
                    it.mutation == MutationKind.CANCEL_TRUE ||
                        it.mutation == MutationKind.UNCANCEL_FALSE ||
                        it.mutation == MutationKind.CANCEL_DYNAMIC
                }
                .groupBy { it.priority }

            for ((prio, group) in byPriority) {
                val distinctInGroup = group.distinctBy { it.plugin.name.lowercase() }
                if (distinctInGroup.size < 2) continue
                for (i in distinctInGroup.indices) {
                    for (j in i + 1 until distinctInGroup.size) {
                        val a = distinctInGroup[i]
                        val b = distinctInGroup[j]
                        // If both set ignoreCancelled = true and both only cancel (setCancelled(true)), order does not change final cancelled=true state
                        if (a.ignoreCancelled && b.ignoreCancelled &&
                            a.mutation == MutationKind.CANCEL_TRUE && b.mutation == MutationKind.CANCEL_TRUE
                        ) {
                            continue
                        }
                        val pairKey = setOf(a.plugin.name.lowercase(), b.plugin.name.lowercase())
                        if (pairKey in orderedPairs || !reportedPairs.add(pairKey)) continue

                        conflictCount++
                        sink.add(
                            rule = StackRules.EVENT_PRIORITY_CANCEL_RACE,
                            location = "$eventShort @${prio.name}: ${a.plugin.name} vs ${b.plugin.name}",
                            detail = "${a.plugin.name} and ${b.plugin.name} both mutate cancellation on $eventDotted " +
                                "at EventPriority.${prio.name} with no declared load-order edge between them, making " +
                                "handler execution order depend on jar loading order.",
                            evidence = listOf(
                                "${a.locationLabel} @${prio.name} (ignoreCancelled=${a.ignoreCancelled}) -> ${a.mutationCall ?: "setCancelled"}",
                                "${b.locationLabel} @${prio.name} (ignoreCancelled=${b.ignoreCancelled}) -> ${b.mutationCall ?: "setCancelled"}",
                            ),
                            severityOverride = Severity.WARNING,
                        )
                    }
                }
            }
        }

        return conflictCount
    }

    private fun collectOrderedPluginPairs(plugins: List<PluginCandidate>): Set<Set<String>> {
        val canonical = HashMap<String, String>()
        for (p in plugins) {
            canonical[p.name.lowercase()] = p.name.lowercase()
            for (prov in p.provides) {
                canonical.putIfAbsent(prov.lowercase(), p.name.lowercase())
            }
        }
        val ordered = HashSet<Set<String>>()
        for (p in plugins) {
            val desc = p.jar.descriptor ?: continue
            val self = p.name.lowercase()
            for (dep in desc.dependencies) {
                val target = canonical[dep.name.lowercase()] ?: continue
                if (target == self) continue
                if (desc.kind == DescriptorKind.PAPER) {
                    if ((dep.phase == null || dep.phase == "server") && (dep.load == "BEFORE" || dep.load == "AFTER")) {
                        ordered += setOf(self, target)
                    }
                } else {
                    ordered += setOf(self, target)
                }
            }
            for (lb in desc.loadBefore) {
                val target = canonical[lb.lowercase()] ?: continue
                if (target != self) ordered += setOf(self, target)
            }
        }
        return ordered
    }

    private fun scanPluginHandlers(plugin: PluginCandidate): List<ListenerHandler> {
        val ownClasses = plugin.jar.classes.filter {
            plugin.jar.isOwnCode(it.internalName) || it.internalName == plugin.jar.descriptor?.mainInternalName
        }
        if (ownClasses.isEmpty()) return emptyList()

        val found = ArrayList<ListenerHandler>()
        ZipFile(plugin.path.toFile()).use { zip ->
            for (entry in ownClasses) {
                val ze = zip.getEntry(entry.entryName) ?: continue
                val bytes = runCatching { zip.getInputStream(ze).use { it.readBytes() } }.getOrNull() ?: continue
                found += scanClassForHandlers(plugin, bytes)
            }
        }
        return found
    }

    private data class RawMethodCapture(
        val name: String,
        val descriptor: String,
        var isEventHandler: Boolean = false,
        var priority: EventPriorityLevel = EventPriorityLevel.NORMAL,
        var ignoreCancelled: Boolean = false,
        var mutation: MutationKind = MutationKind.NONE,
        var mutationCall: String? = null,
        var mutationLine: Int? = null,
        val sameClassHelperCalls: MutableList<String> = mutableListOf(),
    ) {
        val key: String get() = name + descriptor
    }

    private fun scanClassForHandlers(plugin: PluginCandidate, bytes: ByteArray): List<ListenerHandler> {
        var className = ""
        val capturedMethods = LinkedHashMap<String, RawMethodCapture>()

        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    className = name
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val cap = RawMethodCapture(name = name, descriptor = descriptor)
                    capturedMethods[cap.key] = cap

                    return object : MethodVisitor(Opcodes.ASM9) {
                        private var line: Int? = null
                        private var lastIntConst: Int? = null

                        override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor? {
                            if (desc != "Lorg/bukkit/event/EventHandler;") return null
                            cap.isEventHandler = true
                            return object : AnnotationVisitor(Opcodes.ASM9) {
                                override fun visit(attrName: String?, value: Any?) {
                                    if (attrName == "ignoreCancelled" && value is Boolean) {
                                        cap.ignoreCancelled = value
                                    }
                                }

                                override fun visitEnum(attrName: String?, enumDesc: String?, value: String?) {
                                    if (attrName == "priority") {
                                        cap.priority = EventPriorityLevel.fromName(value)
                                    }
                                }
                            }
                        }

                        override fun visitLineNumber(newLine: Int, start: Label?) {
                            line = newLine
                        }

                        override fun visitInsn(opcode: Int) {
                            lastIntConst = when (opcode) {
                                Opcodes.ICONST_0 -> 0
                                Opcodes.ICONST_1 -> 1
                                else -> null
                            }
                        }

                        override fun visitIntInsn(opcode: Int, operand: Int) {
                            lastIntConst = if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) operand else null
                        }

                        override fun visitLdcInsn(value: Any?) {
                            lastIntConst = value as? Int
                        }

                        override fun visitVarInsn(opcode: Int, varIndex: Int) {
                            lastIntConst = null
                        }

                        override fun visitFieldInsn(opcode: Int, owner: String, fieldName: String, fieldDesc: String) {
                            lastIntConst = null
                        }

                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            mName: String,
                            mDesc: String,
                            isInterface: Boolean,
                        ) {
                            val mKey = mName + mDesc
                            if (mKey == "setCancelled(Z)V") {
                                val detected = when (lastIntConst) {
                                    1 -> MutationKind.CANCEL_TRUE to "${owner.substringAfterLast('/')}#setCancelled(true)"
                                    0 -> MutationKind.UNCANCEL_FALSE to "${owner.substringAfterLast('/')}#setCancelled(false)"
                                    else -> MutationKind.CANCEL_DYNAMIC to "${owner.substringAfterLast('/')}#setCancelled(boolean)"
                                }
                                recordMutation(cap, detected.first, detected.second, line)
                            } else if (mKey in RESULT_MUTATION_METHODS) {
                                recordMutation(cap, MutationKind.RESULT_MUTATION, "${owner.substringAfterLast('/')}#$mName", line)
                            } else if (owner == className) {
                                cap.sameClassHelperCalls += mKey
                            }
                            lastIntConst = null
                        }
                    }
                }
            },
            ClassReader.SKIP_FRAMES,
        )

        if (capturedMethods.values.none { it.isEventHandler }) return emptyList()

        return capturedMethods.values.mapNotNull { cap ->
            if (!cap.isEventHandler) return@mapNotNull null
            val argTypes = runCatching { Type.getArgumentTypes(cap.descriptor) }.getOrNull() ?: return@mapNotNull null
            if (argTypes.size != 1 || argTypes[0].sort != Type.OBJECT) return@mapNotNull null
            val eventInternal = argTypes[0].internalName
            if (eventInternal == "java/lang/Object") return@mapNotNull null

            var effectiveMutation = cap.mutation
            var effectiveCall = cap.mutationCall
            var effectiveLine = cap.mutationLine

            // Follow 1-hop helper calls inside the same listener class
            if (effectiveMutation == MutationKind.NONE) {
                for (helperKey in cap.sameClassHelperCalls) {
                    val helper = capturedMethods[helperKey] ?: continue
                    if (helper.mutation != MutationKind.NONE) {
                        effectiveMutation = helper.mutation
                        effectiveCall = helper.mutationCall
                        effectiveLine = helper.mutationLine ?: cap.mutationLine
                        break
                    }
                }
            }

            ListenerHandler(
                plugin = plugin,
                className = className,
                methodName = cap.name,
                eventInternalName = eventInternal,
                priority = cap.priority,
                ignoreCancelled = cap.ignoreCancelled,
                mutation = effectiveMutation,
                mutationCall = effectiveCall,
                line = effectiveLine,
            )
        }
    }

    private fun recordMutation(
        cap: RawMethodCapture,
        kind: MutationKind,
        callSite: String,
        line: Int?,
    ) {
        // Prefer preserving UNCANCEL_FALSE or CANCEL_TRUE if seen; if both appear in one method, treat as CANCEL_DYNAMIC
        if (cap.mutation == MutationKind.NONE) {
            cap.mutation = kind
            cap.mutationCall = callSite
            cap.mutationLine = line
        } else if (cap.mutation != kind &&
            ((cap.mutation == MutationKind.CANCEL_TRUE && kind == MutationKind.UNCANCEL_FALSE) ||
                (cap.mutation == MutationKind.UNCANCEL_FALSE && kind == MutationKind.CANCEL_TRUE))
        ) {
            cap.mutation = MutationKind.CANCEL_DYNAMIC
            cap.mutationCall = callSite.substringBefore('(') + "(boolean)"
        }
    }
}
