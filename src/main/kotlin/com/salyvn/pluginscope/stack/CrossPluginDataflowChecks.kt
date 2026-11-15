package com.salyvn.pluginscope.stack

import com.salyvn.pluginscope.core.FindingSink
import com.salyvn.pluginscope.core.JarInspection
import com.salyvn.pluginscope.core.Severity
import com.salyvn.pluginscope.data.ServerRuntime
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
 * Inter-plugin ASM operand-stack dataflow analyser for `plugins/` directories (`S070`).
 *
 * Single-jar lifecycle checks (`J070`-`J074`) can only inspect one jar in isolation. When an entire
 * `plugins/` set is audited together, we can resolve cross-jar call edges and static storage sinks:
 *
 * 1. Callback / registry sink pinning (`Plugin A -> Plugin B#register -> Plugin B#STATIC_FIELD`):
 *    Plugin B exposes a method whose formal parameter flows into a static field or static collection
 *    owned by Plugin B. Plugin A invokes that method passing an instance of A's own class or an
 *    `invokedynamic` lambda/method-reference in A, without invoking B's cleanup/unregister method in
 *    A's `onDisable()` or `PluginDisableEvent` call graph (and without B auto-cleaning on
 *    `PluginDisableEvent`). B's static GC root pins A's `PaperPluginClassLoader`.
 *
 * 2. Direct foreign instance retention (`Plugin A#STATIC_FIELD` holds `Plugin B` type):
 *    Plugin A stores an instance typed under Plugin B's own package into a static field or static
 *    collection in Plugin A without clearing/removing it in A's `onDisable()` or unload handlers.
 */
internal object CrossPluginDataflowChecks {

    internal data class PluginUnit(
        val path: Path,
        val jar: JarInspection,
        val name: String,
    )

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

        data class VarOp(val opcode: Int, val slot: Int, override val line: Int?) : FlowInsn
        data class TypeOp(val opcode: Int, val internalType: String, override val line: Int?) : FlowInsn
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

    private data class ParsedPlugin(
        val unit: PluginUnit,
        val models: Map<String, OwnClassModel>,
        val fieldDefs: Map<String, StaticFieldDef>,
        val disableReached: Set<MethodRef>,
        val unloadCleanupReached: Set<MethodRef>,
        val pluginDisableEventReached: Set<MethodRef>,
    )

    private data class StaticSinkMethod(
        val targetPlugin: PluginUnit,
        val owner: String,
        val methodKey: String,
        val methodName: String,
        val paramIndex: Int,
        val targetFieldKey: String,
        val inCollection: Boolean,
    )

    private sealed interface SinkVal {
        data class Param(val index: Int) : SinkVal
        data class StaticField(val owner: String, val name: String, val descriptor: String) : SinkVal {
            val fieldKey: String get() = "$owner#$name"
        }
        data object Unknown : SinkVal
    }

    private sealed interface CallerVal {
        data class OwnInstance(val internalType: String, val line: Int?) : CallerVal
        data class OwnLambda(val targetOwner: String, val targetMethod: String, val line: Int?) : CallerVal
        data class ForeignInstance(val plugin: PluginUnit, val internalType: String, val line: Int?) : CallerVal
        data class StaticField(val owner: String, val name: String, val descriptor: String) : CallerVal {
            val fieldKey: String get() = "$owner#$name"
        }
        data object NullConst : CallerVal
        data object Unknown : CallerVal
    }

    private data class DirectForeignWrite(
        val fieldKey: String,
        val fieldDesc: String,
        val foreignPlugin: PluginUnit,
        val foreignType: String,
        val inCollection: Boolean,
        val location: String,
    )

    private val COLLECTION_ADD_METHODS = setOf(
        "add", "addLast", "addFirst", "offer", "push", "put", "putIfAbsent",
    )

    private val COLLECTION_CLEAR_OR_REMOVE_METHODS = setOf(
        "clear", "remove", "removeIf", "removeAll", "poll", "evictAll", "invalidateAll",
    )

    private val CLEANUP_NAME_PREFIXES = listOf(
        "unregister", "deregister", "remove", "clear", "unhook", "detach", "close", "reset",
    )

    private val IGNORED_STATIC_FIELD_NAMES = setOf(
        "\$VALUES", "\$ENTRIES", "Companion", "INSTANCE", "\$assertionsDisabled", "serialVersionUID",
    )

    private val WEAK_CONTAINER_MARKERS = listOf(
        "WeakHashMap", "WeakReference", "SoftReference", "PhantomReference",
        "com/github/benmanes/caffeine/cache/", "com/google/common/cache/",
    )

    private val NON_LEAKING_VALUE_TYPES = setOf(
        "java/lang/String",
        "java/lang/Boolean",
        "java/lang/Integer",
        "java/lang/Long",
        "java/lang/Double",
        "java/lang/Float",
        "java/lang/Short",
        "java/lang/Byte",
        "java/lang/Character",
        "java/util/UUID",
    )

    private val DESCRIPTOR_CLASS_REGEX = Regex("L([a-zA-Z0-9_/\$]+);")

    fun run(units: List<PluginUnit>, sink: FindingSink): Int {
        if (units.size < 2) return 0

        val classClaims = HashMap<String, MutableList<PluginUnit>>()
        for (unit in units) {
            for (cls in unit.jar.classes) {
                if (unit.jar.isOwnCode(cls.internalName) && !ServerRuntime.isServerNamespace(cls.internalName)) {
                    classClaims.getOrPut(cls.internalName) { mutableListOf() } += unit
                }
            }
        }
        val uniqueOwnClasses: Map<String, PluginUnit> = classClaims
            .filterValues { it.size == 1 }
            .mapValues { it.value.single() }

        fun ownerOfType(internalType: String): PluginUnit? {
            if (ServerRuntime.isServerNamespace(internalType) || internalType in NON_LEAKING_VALUE_TYPES) return null
            uniqueOwnClasses[internalType]?.let { return it }
            val matching = units.filter { it.jar.isOwnCode(internalType) }
            return matching.singleOrNull()
        }

        val parsedPlugins = units.mapNotNull { unit ->
            val desc = unit.jar.descriptor ?: return@mapNotNull null
            val ownNames = buildSet {
                unit.jar.classes.filter { unit.jar.isOwnCode(it.internalName) }.forEach { add(it.internalName) }
                desc.mainInternalName?.let { add(it) }
                desc.bootstrapper?.replace('.', '/')?.let { add(it) }
                desc.loader?.replace('.', '/')?.let { add(it) }
            }
            if (ownNames.isEmpty()) return@mapNotNull null
            val models = readOwnClasses(unit.path, ownNames)
            if (models.isEmpty()) return@mapNotNull null

            val fieldDefs = HashMap<String, StaticFieldDef>()
            for (cls in models.values) {
                for (f in cls.staticFields) fieldDefs[f.fieldKey] = f
            }

            val disableSeeds = buildSet {
                for (cls in models.values) {
                    for (m in cls.methods.values) {
                        if (m.name == "onDisable" && m.descriptor == "()V") {
                            add(MethodRef(cls.internalName, m.key))
                        }
                    }
                }
                desc.mainInternalName?.let { main ->
                    if (models[main]?.methods?.containsKey("onDisable()V") == true) {
                        add(MethodRef(main, "onDisable()V"))
                    }
                }
            }
            val unloadEventSeeds = buildSet {
                for (cls in models.values) {
                    for (m in cls.methods.values) {
                        if (m.descriptor.contains("Lorg/bukkit/event/server/PluginDisableEvent;") ||
                            m.descriptor.contains("Lorg/bukkit/event/server/ServiceUnregisterEvent;")
                        ) {
                            add(MethodRef(cls.internalName, m.key))
                        }
                    }
                }
            }

            val disableReached = computeReachableMethods(models, disableSeeds)
            val pluginDisableEventReached = computeReachableMethods(models, unloadEventSeeds)
            ParsedPlugin(
                unit = unit,
                models = models,
                fieldDefs = fieldDefs,
                disableReached = disableReached,
                unloadCleanupReached = disableReached + pluginDisableEventReached,
                pluginDisableEventReached = pluginDisableEventReached,
            )
        }

        if (parsedPlugins.size < 2) return 0

        // Discover static sink methods and cleanup methods in every target plugin B.
        val sinksByMethod = HashMap<MethodRef, MutableList<StaticSinkMethod>>()
        val cleanupMethodsByField = HashMap<String, MutableSet<MethodRef>>()
        val autoCleanedFieldsOnPluginDisable = HashSet<String>()

        for (target in parsedPlugins) {
            autoCleanedFieldsOnPluginDisable += findClearedOrRemovedStaticFields(
                target.models,
                target.pluginDisableEventReached,
            )

            // Record all methods in target that clear/remove a static field, or follow cleanup naming on sink classes.
            for (cls in target.models.values) {
                for (m in cls.methods.values) {
                    val mRef = MethodRef(cls.internalName, m.key)
                    val cleanedHere = findClearedOrRemovedStaticFields(target.models, setOf(mRef))
                    for (fieldKey in cleanedHere) {
                        cleanupMethodsByField.getOrPut(fieldKey) { mutableSetOf() } += mRef
                    }
                }
            }

            for (cls in target.models.values) {
                for (m in cls.methods.values) {
                    if (m.name == "<clinit>" || m.name == "onDisable") continue
                    val discovered = discoverSinkParamsInMethod(target, cls, m)
                    for (sinkMethod in discovered) {
                        sinksByMethod.getOrPut(MethodRef(cls.internalName, m.key)) { mutableListOf() } += sinkMethod
                        // Also recognise explicit unregister*/remove*/clear* methods on the sink class or field owner
                        val fieldOwner = sinkMethod.targetFieldKey.substringBefore('#')
                        for (ownerCandidate in setOf(cls.internalName, fieldOwner)) {
                            val ownerModel = target.models[ownerCandidate] ?: continue
                            for (candidate in ownerModel.methods.values) {
                                val low = candidate.name.lowercase()
                                if (CLEANUP_NAME_PREFIXES.any { low.startsWith(it) }) {
                                    cleanupMethodsByField
                                        .getOrPut(sinkMethod.targetFieldKey) { mutableSetOf() } +=
                                        MethodRef(ownerCandidate, candidate.key)
                                }
                            }
                        }
                    }
                }
            }
        }

        var leakCount = 0

        for (caller in parsedPlugins) {
            val cleanupCallsInCaller = collectExternalCalls(caller.models, caller.unloadCleanupReached)
            val directWrites = mutableListOf<DirectForeignWrite>()
            val reportedSinkTriples = HashSet<String>()

            for (cls in caller.models.values) {
                for (method in cls.methods.values) {
                    val mRef = MethodRef(cls.internalName, method.key)
                    if (mRef in caller.unloadCleanupReached) continue

                    simulateCallerMethod(
                        caller = caller,
                        cls = cls,
                        method = method,
                        ownerOfType = ::ownerOfType,
                        sinksByMethod = sinksByMethod,
                        onSinkLeakCandidate = { sinkMethod, passedVal, callLine ->
                            if (sinkMethod.targetPlugin.name.equals(caller.unit.name, ignoreCase = true)) return@simulateCallerMethod
                            if (sinkMethod.targetFieldKey in autoCleanedFieldsOnPluginDisable) return@simulateCallerMethod

                            val validCleanups = cleanupMethodsByField[sinkMethod.targetFieldKey].orEmpty()
                            val callsCleanup = cleanupCallsInCaller.any { callRef ->
                                callRef in validCleanups ||
                                    (callRef.owner == sinkMethod.owner &&
                                        CLEANUP_NAME_PREFIXES.any { callRef.key.lowercase().startsWith(it) })
                            }
                            if (callsCleanup) return@simulateCallerMethod

                            val dedupKey = "${caller.unit.name}|${sinkMethod.owner}#${sinkMethod.methodKey}|${sinkMethod.targetFieldKey}"
                            if (!reportedSinkTriples.add(dedupKey)) return@simulateCallerMethod

                            val siteLoc = formatLoc(cls.internalName, method.name, callLine)
                            val payloadSummary = when (passedVal) {
                                is CallerVal.OwnInstance ->
                                    "instance ${passedVal.internalType.replace('/', '.')}"
                                is CallerVal.OwnLambda ->
                                    "lambda ${passedVal.targetOwner.replace('/', '.')}#${passedVal.targetMethod}"
                                else -> "callback instance"
                            }
                            val sinkDisplay = "${sinkMethod.owner.replace('/', '.')}#${sinkMethod.methodName}"
                            val fieldDisplay = sinkMethod.targetFieldKey.replace('/', '.')
                            leakCount++
                            sink.add(
                                rule = StackRules.CROSS_PLUGIN_DATAFLOW_LEAK,
                                location = "${caller.unit.name} -> ${sinkMethod.targetPlugin.name} ($siteLoc)",
                                detail = "${caller.unit.name} passes $payloadSummary into ${sinkMethod.targetPlugin.name}'s " +
                                    "static registry $sinkDisplay (stored in $fieldDisplay) without invoking an " +
                                    "unregister or cleanup method in onDisable().",
                                evidence = listOf("$siteLoc -> $sinkDisplay -> static $fieldDisplay ($payloadSummary)"),
                                severityOverride = Severity.CRITICAL,
                            )
                        },
                        onDirectForeignWrite = { directWrites += it },
                    )
                }
            }

            // Also inspect static field declarations in caller whose descriptor/signature directly names
            // an installed foreign plugin's class and is mutated anywhere in caller bytecode.
            val mutatedFields = findMutatedStaticFields(caller.models)
            for (cls in caller.models.values) {
                for (field in cls.staticFields) {
                    if (field.name in IGNORED_STATIC_FIELD_NAMES) continue
                    val combined = (field.signature ?: "") + " " + field.descriptor
                    if (WEAK_CONTAINER_MARKERS.any { combined.contains(it) }) continue
                    if (field.fieldKey !in mutatedFields) continue

                    for (match in DESCRIPTOR_CLASS_REGEX.findAll(combined)) {
                        val internalType = match.groupValues[1]
                        val ownerPlugin = ownerOfType(internalType) ?: continue
                        if (ownerPlugin.name.equals(caller.unit.name, ignoreCase = true)) continue
                        directWrites += DirectForeignWrite(
                            fieldKey = field.fieldKey,
                            fieldDesc = field.descriptor,
                            foreignPlugin = ownerPlugin,
                            foreignType = internalType,
                            inCollection = isCollectionOrMapDescriptor(field.descriptor),
                            location = "${cls.internalName.replace('/', '.')}#${field.name}",
                        )
                    }
                }
            }

            if (directWrites.isNotEmpty()) {
                val cleanedInCaller = findClearedOrRemovedStaticFields(caller.models, caller.unloadCleanupReached)
                for ((fieldKey, writes) in directWrites.groupBy { it.fieldKey }) {
                    if (fieldKey in cleanedInCaller) continue
                    val def = caller.fieldDefs[fieldKey]
                    if (def != null && def.name in IGNORED_STATIC_FIELD_NAMES) continue
                    val combined = ((def?.signature ?: "") + " " + (def?.descriptor ?: writes.first().fieldDesc))
                    if (WEAK_CONTAINER_MARKERS.any { combined.contains(it) }) continue

                    val first = writes.first()
                    val inColl = writes.any { it.inCollection } || isCollectionOrMapDescriptor(first.fieldDesc)
                    val fieldDisplay = fieldKey.replace('/', '.')
                    leakCount++
                    sink.add(
                        rule = StackRules.CROSS_PLUGIN_DATAFLOW_LEAK,
                        location = "${caller.unit.name} -> ${first.foreignPlugin.name} ($fieldDisplay)",
                        detail = "${caller.unit.name} retains ${first.foreignPlugin.name} instance " +
                            "(${first.foreignType.replace('/', '.')}) in static ${if (inColl) "collection" else "field"} " +
                            "$fieldDisplay without clearing in onDisable() or PluginDisableEvent.",
                        evidence = writes.map {
                            "${it.location} stores ${it.foreignType.replace('/', '.')} in $fieldDisplay"
                        }.distinct().take(4),
                        severityOverride = if (inColl) Severity.CRITICAL else Severity.WARNING,
                    )
                }
            }
        }

        return leakCount
    }

    private fun discoverSinkParamsInMethod(
        target: ParsedPlugin,
        cls: OwnClassModel,
        method: OwnMethodModel,
    ): List<StaticSinkMethod> {
        val argTypes = Type.getArgumentTypes(method.descriptor)
        if (argTypes.isEmpty()) return emptyList()

        val locals = HashMap<Int, SinkVal>()
        var slotCursor = if (method.access and Opcodes.ACC_STATIC != 0) 0 else 1
        for (i in argTypes.indices) {
            val t = argTypes[i]
            if (t.sort == Type.OBJECT && t.internalName !in NON_LEAKING_VALUE_TYPES && t.internalName != "java/lang/Class") {
                locals[slotCursor] = SinkVal.Param(i)
            }
            slotCursor += t.size
        }
        if (locals.isEmpty()) return emptyList()

        val stack = ArrayDeque<SinkVal>()
        fun popSafe(): SinkVal = if (stack.isNotEmpty()) stack.removeLast() else SinkVal.Unknown
        fun peekSafe(): SinkVal = if (stack.isNotEmpty()) stack.last() else SinkVal.Unknown
        fun push(v: SinkVal) {
            stack.addLast(v)
        }

        val results = LinkedHashMap<Pair<Int, String>, StaticSinkMethod>()

        for (insn in method.insns) {
            when (insn) {
                is FlowInsn.ConstNull -> push(SinkVal.Unknown)
                is FlowInsn.TypeOp -> {
                    when (insn.opcode) {
                        Opcodes.NEW -> push(SinkVal.Unknown)
                        Opcodes.CHECKCAST -> Unit
                        else -> {
                            popSafe()
                            push(SinkVal.Unknown)
                        }
                    }
                }

                is FlowInsn.FieldOp -> {
                    when (insn.opcode) {
                        Opcodes.GETSTATIC -> push(SinkVal.StaticField(insn.owner, insn.name, insn.descriptor))
                        Opcodes.PUTSTATIC -> {
                            val v = popSafe()
                            if (v is SinkVal.Param && insn.owner in target.models) {
                                val fKey = "${insn.owner}#${insn.name}"
                                val def = target.fieldDefs[fKey]
                                val combined = (def?.signature ?: "") + " " + (def?.descriptor ?: insn.descriptor)
                                if (WEAK_CONTAINER_MARKERS.none { combined.contains(it) }) {
                                    results[v.index to fKey] = StaticSinkMethod(
                                        targetPlugin = target.unit,
                                        owner = cls.internalName,
                                        methodKey = method.key,
                                        methodName = method.name,
                                        paramIndex = v.index,
                                        targetFieldKey = fKey,
                                        inCollection = false,
                                    )
                                }
                            }
                        }

                        Opcodes.GETFIELD -> {
                            popSafe()
                            push(SinkVal.Unknown)
                        }

                        Opcodes.PUTFIELD -> {
                            popSafe()
                            popSafe()
                        }
                    }
                }

                is FlowInsn.VarOp -> {
                    when (insn.opcode) {
                        Opcodes.ASTORE, Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE -> {
                            locals[insn.slot] = popSafe()
                        }

                        Opcodes.ALOAD, Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD -> {
                            push(locals[insn.slot] ?: SinkVal.Unknown)
                        }
                    }
                }

                is FlowInsn.StackOp -> {
                    when (insn.opcode) {
                        Opcodes.DUP -> push(peekSafe())
                        Opcodes.POP, Opcodes.POP2 -> popSafe()
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

                    if (receiver is SinkVal.StaticField &&
                        receiver.owner in target.models &&
                        insn.name in COLLECTION_ADD_METHODS
                    ) {
                        val def = target.fieldDefs[receiver.fieldKey]
                        val combined = (def?.signature ?: "") + " " + (def?.descriptor ?: receiver.descriptor)
                        if (WEAK_CONTAINER_MARKERS.none { combined.contains(it) }) {
                            for (arg in args) {
                                if (arg is SinkVal.Param) {
                                    results[arg.index to receiver.fieldKey] = StaticSinkMethod(
                                        targetPlugin = target.unit,
                                        owner = cls.internalName,
                                        methodKey = method.key,
                                        methodName = method.name,
                                        paramIndex = arg.index,
                                        targetFieldKey = receiver.fieldKey,
                                        inCollection = true,
                                    )
                                }
                            }
                        }
                    }

                    if (Type.getReturnType(insn.descriptor).sort != Type.VOID) {
                        push(SinkVal.Unknown)
                    }
                }

                is FlowInsn.DynamicTarget -> {
                    repeat(Type.getArgumentTypes(insn.callSiteDescriptor).size) { popSafe() }
                    if (Type.getReturnType(insn.callSiteDescriptor).sort != Type.VOID) {
                        push(SinkVal.Unknown)
                    }
                }

                is FlowInsn.ReturnOp -> stack.clear()
                is FlowInsn.OtherOp -> {
                    if (insn.opcode in Opcodes.ICONST_M1..Opcodes.LDC) {
                        push(SinkVal.Unknown)
                    }
                }
            }
        }

        return results.values.toList()
    }

    private fun simulateCallerMethod(
        caller: ParsedPlugin,
        cls: OwnClassModel,
        method: OwnMethodModel,
        ownerOfType: (String) -> PluginUnit?,
        sinksByMethod: Map<MethodRef, List<StaticSinkMethod>>,
        onSinkLeakCandidate: (StaticSinkMethod, CallerVal, Int?) -> Unit,
        onDirectForeignWrite: (DirectForeignWrite) -> Unit,
    ) {
        val locals = HashMap<Int, CallerVal>()
        var slotCursor = 0
        if (method.access and Opcodes.ACC_STATIC == 0) {
            locals[0] = CallerVal.OwnInstance(cls.internalName, null)
            slotCursor = 1
        }
        for (argType in Type.getArgumentTypes(method.descriptor)) {
            if (argType.sort == Type.OBJECT) {
                val internal = argType.internalName
                if (caller.unit.jar.isOwnCode(internal)) {
                    locals[slotCursor] = CallerVal.OwnInstance(internal, null)
                } else {
                    val foreign = ownerOfType(internal)
                    if (foreign != null && !foreign.name.equals(caller.unit.name, ignoreCase = true)) {
                        locals[slotCursor] = CallerVal.ForeignInstance(foreign, internal, null)
                    }
                }
            }
            slotCursor += argType.size
        }

        val stack = ArrayDeque<CallerVal>()
        fun popSafe(): CallerVal = if (stack.isNotEmpty()) stack.removeLast() else CallerVal.Unknown
        fun peekSafe(): CallerVal = if (stack.isNotEmpty()) stack.last() else CallerVal.Unknown
        fun push(v: CallerVal) {
            stack.addLast(v)
        }

        fun classifyObjectType(internalType: String, line: Int?): CallerVal {
            if (caller.unit.jar.isOwnCode(internalType)) {
                return CallerVal.OwnInstance(internalType, line)
            }
            val foreign = ownerOfType(internalType)
            if (foreign != null && !foreign.name.equals(caller.unit.name, ignoreCase = true)) {
                return CallerVal.ForeignInstance(foreign, internalType, line)
            }
            return CallerVal.Unknown
        }

        for (insn in method.insns) {
            when (insn) {
                is FlowInsn.ConstNull -> push(CallerVal.NullConst)
                is FlowInsn.TypeOp -> {
                    when (insn.opcode) {
                        Opcodes.NEW -> push(classifyObjectType(insn.internalType, insn.line))
                        Opcodes.CHECKCAST -> {
                            val top = popSafe()
                            val casted = classifyObjectType(insn.internalType, insn.line)
                            push(if (casted !is CallerVal.Unknown) casted else top)
                        }
                        else -> {
                            popSafe()
                            push(CallerVal.Unknown)
                        }
                    }
                }

                is FlowInsn.FieldOp -> {
                    when (insn.opcode) {
                        Opcodes.GETSTATIC -> {
                            if (insn.owner in caller.models) {
                                push(CallerVal.StaticField(insn.owner, insn.name, insn.descriptor))
                            } else {
                                val fType = Type.getType(insn.descriptor)
                                if (fType.sort == Type.OBJECT) {
                                    push(classifyObjectType(fType.internalName, insn.line))
                                } else {
                                    push(CallerVal.Unknown)
                                }
                            }
                        }

                        Opcodes.PUTSTATIC -> {
                            val v = popSafe()
                            if (insn.owner in caller.models && v is CallerVal.ForeignInstance) {
                                val fKey = "${insn.owner}#${insn.name}"
                                onDirectForeignWrite(
                                    DirectForeignWrite(
                                        fieldKey = fKey,
                                        fieldDesc = insn.descriptor,
                                        foreignPlugin = v.plugin,
                                        foreignType = v.internalType,
                                        inCollection = false,
                                        location = formatLoc(cls.internalName, method.name, insn.line ?: v.line),
                                    )
                                )
                            }
                        }

                        Opcodes.GETFIELD -> {
                            popSafe()
                            val fType = Type.getType(insn.descriptor)
                            if (fType.sort == Type.OBJECT) {
                                push(classifyObjectType(fType.internalName, insn.line))
                            } else {
                                push(CallerVal.Unknown)
                            }
                        }

                        Opcodes.PUTFIELD -> {
                            popSafe()
                            popSafe()
                        }
                    }
                }

                is FlowInsn.VarOp -> {
                    when (insn.opcode) {
                        Opcodes.ASTORE, Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE -> {
                            locals[insn.slot] = popSafe()
                        }

                        Opcodes.ALOAD, Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD -> {
                            push(locals[insn.slot] ?: CallerVal.Unknown)
                        }
                    }
                }

                is FlowInsn.StackOp -> {
                    when (insn.opcode) {
                        Opcodes.DUP -> push(peekSafe())
                        Opcodes.POP, Opcodes.POP2 -> popSafe()
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

                    // 1. Check if calling a foreign plugin's static sink method with an own instance / lambda.
                    val matchingSinks = sinksByMethod[MethodRef(insn.owner, insn.name + insn.descriptor)]
                    if (matchingSinks != null) {
                        for (sinkMethod in matchingSinks) {
                            val arg = args.getOrNull(sinkMethod.paramIndex) ?: continue
                            val resolvedArg = when (arg) {
                                is CallerVal.OwnInstance, is CallerVal.OwnLambda -> arg
                                is CallerVal.StaticField ->
                                    if (caller.unit.jar.isOwnCode(arg.owner)) {
                                        val ft = Type.getType(arg.descriptor)
                                        if (ft.sort == Type.OBJECT && caller.unit.jar.isOwnCode(ft.internalName)) {
                                            CallerVal.OwnInstance(ft.internalName, insn.line)
                                        } else {
                                            null
                                        }
                                    } else {
                                        null
                                    }
                                else -> null
                            }
                            if (resolvedArg != null) {
                                onSinkLeakCandidate(sinkMethod, resolvedArg, insn.line)
                            }
                        }
                    }

                    // 2. Check if adding a foreign plugin instance into caller's static collection / map.
                    if (receiver is CallerVal.StaticField &&
                        receiver.owner in caller.models &&
                        insn.name in COLLECTION_ADD_METHODS
                    ) {
                        val foreignArg = args.filterIsInstance<CallerVal.ForeignInstance>().firstOrNull()
                        if (foreignArg != null) {
                            onDirectForeignWrite(
                                DirectForeignWrite(
                                    fieldKey = receiver.fieldKey,
                                    fieldDesc = receiver.descriptor,
                                    foreignPlugin = foreignArg.plugin,
                                    foreignType = foreignArg.internalType,
                                    inCollection = true,
                                    location = formatLoc(cls.internalName, method.name, insn.line ?: foreignArg.line),
                                )
                            )
                        }
                    }

                    val retType = Type.getReturnType(insn.descriptor)
                    if (retType.sort != Type.VOID) {
                        if (retType.sort == Type.OBJECT) {
                            val classified = classifyObjectType(retType.internalName, insn.line)
                            if (classified !is CallerVal.Unknown) {
                                push(classified)
                            } else {
                                val ownerPlugin = ownerOfType(insn.owner)
                                if (ownerPlugin != null &&
                                    !ownerPlugin.name.equals(caller.unit.name, ignoreCase = true) &&
                                    retType.internalName !in NON_LEAKING_VALUE_TYPES &&
                                    !ServerRuntime.isServerNamespace(retType.internalName) &&
                                    !retType.internalName.startsWith("java/")
                                ) {
                                    push(CallerVal.ForeignInstance(ownerPlugin, retType.internalName, insn.line))
                                } else {
                                    push(CallerVal.Unknown)
                                }
                            }
                        } else {
                            push(CallerVal.Unknown)
                        }
                    }
                }

                is FlowInsn.DynamicTarget -> {
                    repeat(Type.getArgumentTypes(insn.callSiteDescriptor).size) { popSafe() }
                    if (Type.getReturnType(insn.callSiteDescriptor).sort != Type.VOID) {
                        if (caller.unit.jar.isOwnCode(insn.owner)) {
                            push(CallerVal.OwnLambda(insn.owner, insn.name, insn.line))
                        } else {
                            push(CallerVal.Unknown)
                        }
                    }
                }

                is FlowInsn.ReturnOp -> stack.clear()
                is FlowInsn.OtherOp -> {
                    if (insn.opcode in Opcodes.ICONST_M1..Opcodes.LDC) {
                        push(CallerVal.Unknown)
                    }
                }
            }
        }
    }

    private fun collectExternalCalls(
        models: Map<String, OwnClassModel>,
        reached: Set<MethodRef>,
    ): Set<MethodRef> = buildSet {
        for (ref in reached) {
            val method = models[ref.owner]?.methods?.get(ref.key) ?: continue
            for (insn in method.insns) {
                when (insn) {
                    is FlowInsn.Call -> add(MethodRef(insn.owner, insn.name + insn.descriptor))
                    is FlowInsn.DynamicTarget -> add(MethodRef(insn.owner, insn.name + insn.descriptor))
                    else -> Unit
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

    private fun findClearedOrRemovedStaticFields(
        models: Map<String, OwnClassModel>,
        reached: Set<MethodRef>,
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
                        if (lastStaticRef != null && insn.name in COLLECTION_CLEAR_OR_REMOVE_METHODS) {
                            cleaned += lastStaticRef
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
                    // Corrupt classes are already reported by single-jar hygiene checks
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
                return object : MethodVisitor(Opcodes.ASM9) {
                    private var currentLine: Int? = null

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
                        val model = OwnMethodModel(internalName, name, descriptor, access, insns)
                        methods[model.key] = model
                    }
                }
            }
        }

        ClassReader(bytes).accept(visitor, ClassReader.SKIP_FRAMES)
        return OwnClassModel(internalName, superName, interfaces, staticFields, methods)
    }
}
