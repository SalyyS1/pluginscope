package com.salyvn.pluginscope.jar

import com.salyvn.pluginscope.core.FindingSink
import com.salyvn.pluginscope.core.JarInspection
import com.salyvn.pluginscope.core.Severity
import com.salyvn.pluginscope.data.McVersions
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Single-JAR ASM operand-stack and hierarchy checks for Paper 1.21.4+..26.3+ block-state / data
 * component codec hazards (`J077`) and shaded ASM `ClassWriter(COMPUTE_FRAMES)` stack-map-table
 * classloader hazards (`J078`).
 */
internal object BytecodeCodecAndAsmChecks {

    private const val GET_COMMON_SUPER_CLASS_SIG =
        "getCommonSuperClass(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"

    private const val COMPUTE_FRAMES_FLAG = 2

    private val FROZEN_REGISTRY_OWNERS = setOf(
        "net/minecraft/core/Registry",
        "net/minecraft/core/MappedRegistry",
        "net/minecraft/core/WritableRegistry",
    )

    private val FROZEN_REGISTRY_MUTATORS = setOf(
        "register",
        "registerMapping",
        "createIntrusiveHolder",
        "unfreeze",
    )

    private val LEGACY_RAW_BLOCK_OWNERS = setOf(
        "org/bukkit/block/BlockState",
        "org/bukkit/block/Block",
    )

    private val LEGACY_RAW_BLOCK_METHODS = setOf(
        "setRawData(B)V",
        "setData(B)V",
        "setData(BZ)V",
        "setData(Lorg/bukkit/material/MaterialData;)V",
        "getData()Lorg/bukkit/material/MaterialData;",
    )

    private val SNAPSHOT_SUBVIEW_METHODS = setOf(
        "getPersistentDataContainer()Lorg/bukkit/persistence/PersistentDataContainer;",
        "getSide(Lorg/bukkit/block/sign/Side;)Lorg/bukkit/block/sign/SignSide;",
        "getSnapshotInventory()Lorg/bukkit/inventory/Inventory;",
    )

    private val SNAPSHOT_DIRECT_MUTATORS = setOf(
        "setBlockData",
        "setType",
        "setOwningPlayer",
        "setPlayerProfile",
        "setOwnerProfile",
        "setSpawnedType",
        "setDelay",
        "setMinSpawnDelay",
        "setMaxSpawnDelay",
        "setSpawnCount",
        "setLock",
        "setCustomName",
        "customName",
        "setPrimaryEffect",
        "setSecondaryEffect",
        "setCookTime",
        "setBurnTime",
        "setTransferCooldown",
        "setLines",
        "setLine",
    )

    private val SUBVIEW_MUTATORS = mapOf(
        "org/bukkit/persistence/PersistentDataContainer" to setOf("set", "remove", "copyTo"),
        "org/bukkit/block/sign/SignSide" to setOf("line", "setLine", "setGlowingText", "setColor"),
        "org/bukkit/inventory/Inventory" to setOf("setItem", "addItem", "removeItem", "clear", "setContents", "setStorageContents"),
    )

    private class TrackedSnapshot(
        val originCall: String,
        val originLine: Int?,
        var mutatedBy: String? = null,
        var mutateLine: Int? = null,
        var committedOrEscaped: Boolean = false,
    )

    private sealed interface OpVal {
        data class IntConst(val value: Int) : OpVal
        data class DetachedState(val tracker: TrackedSnapshot) : OpVal
        data class StateSubview(val tracker: TrackedSnapshot, val subviewOwner: String) : OpVal
        data object Other : OpVal
    }

    private sealed interface MethodStep {
        val line: Int?

        data class PushInt(val value: Int, override val line: Int?) : MethodStep
        data class PushOther(override val line: Int?) : MethodStep
        data class IntBinOp(val opcode: Int, override val line: Int?) : MethodStep
        data class VarLoad(val opcode: Int, val slot: Int, override val line: Int?) : MethodStep
        data class VarStore(val opcode: Int, val slot: Int, override val line: Int?) : MethodStep
        data class StackManip(val opcode: Int, override val line: Int?) : MethodStep
        data class TypeInsn(val opcode: Int, val internalType: String, override val line: Int?) : MethodStep
        data class FieldInsn(
            val opcode: Int,
            val owner: String,
            val name: String,
            val descriptor: String,
            override val line: Int?,
        ) : MethodStep
        data class CallInsn(
            val opcode: Int,
            val owner: String,
            val name: String,
            val descriptor: String,
            override val line: Int?,
        ) : MethodStep
        data class InvokeDynInsn(val callSiteDescriptor: String, override val line: Int?) : MethodStep
        data class ReturnInsn(val opcode: Int, override val line: Int?) : MethodStep
    }

    fun run(path: Path, jar: JarInspection, targetJava: Int?, sink: FindingSink) {
        val descriptor = jar.descriptor ?: return
        if (jar.classes.isEmpty()) return

        val apiVersion = descriptor.apiVersion
        val minJavaFromApi = apiVersion?.let { McVersions.minimumJavaFor(it) }
        val isModernPaper = (apiVersion != null && McVersions.isModernPaper(apiVersion)) ||
            (minJavaFromApi != null && minJavaFromApi >= 21) ||
            (targetJava != null && targetJava >= 21)

        val (safeCwSubclasses, unsafeCwSubclasses) = classifyClassWriterHierarchy(jar)

        val frozenRegistryHits = mutableListOf<String>()
        val legacyCodecHits = mutableListOf<String>()
        val detachedSnapshotHits = mutableListOf<Pair<String, String>>()
        val asmComputeFramesHits = mutableListOf<Pair<String, String>>()

        ZipFile(path.toFile()).use { zip ->
            for (entry in jar.classes) {
                if (isAsmLibraryClass(entry.internalName)) continue
                val zipEntry = zip.getEntry(entry.entryName) ?: continue
                val bytes = runCatching { zip.getInputStream(zipEntry).use { it.readBytes() } }.getOrNull() ?: continue
                val inspectOwn = jar.isOwnCode(entry.internalName) ||
                    entry.internalName == descriptor.mainInternalName

                analyzeClass(
                    bytes = bytes,
                    className = entry.internalName,
                    isOwnClass = inspectOwn,
                    isModernPaper = isModernPaper,
                    safeCwSubclasses = safeCwSubclasses,
                    unsafeCwSubclasses = unsafeCwSubclasses,
                    frozenRegistryHits = frozenRegistryHits,
                    legacyCodecHits = legacyCodecHits,
                    detachedSnapshotHits = detachedSnapshotHits,
                    asmComputeFramesHits = asmComputeFramesHits,
                )
            }
        }

        if (frozenRegistryHits.isNotEmpty()) {
            sink.add(
                rule = JarRules.PAPER_26_BLOCK_STATE_CODEC_HAZARD,
                location = jar.fileName,
                detail = "Plugin mutates or registers into Mojang MappedRegistry / BuiltInRegistries at runtime. " +
                    "Paper 1.21.4+..26.3+ freezes registries before plugin load, causing IllegalStateException or " +
                    "corrupting clientbound RegistrySynchronization codecs.",
                evidence = frozenRegistryHits.distinct().take(4),
                severityOverride = Severity.CRITICAL,
            )
        }

        if (isModernPaper && legacyCodecHits.isNotEmpty()) {
            sink.add(
                rule = JarRules.PAPER_26_BLOCK_STATE_CODEC_HAZARD,
                location = jar.fileName,
                detail = "Plugin invokes removed legacy numeric block-state byte or MaterialData APIs while targeting " +
                    "modern Paper (${apiVersion ?: "Java $targetJava"}). Block states on 1.20.5+..26.3+ require " +
                    "typed BlockData and DataComponentTypes codecs.",
                evidence = legacyCodecHits.distinct().take(4),
                severityOverride = Severity.CRITICAL,
            )
        }

        if (detachedSnapshotHits.isNotEmpty()) {
            for ((loc, msg) in detachedSnapshotHits.distinctBy { it.first }.take(4)) {
                sink.add(
                    rule = JarRules.PAPER_26_BLOCK_STATE_CODEC_HAZARD,
                    location = loc,
                    detail = "Detached Block#getState() snapshot is mutated ($msg) without calling BlockState#update(...) " +
                        "or returning the snapshot, silently discarding tile-entity and component changes.",
                    evidence = listOf("$loc: Block#getState() -> $msg (no BlockState#update)"),
                    severityOverride = Severity.WARNING,
                )
            }
        }

        if (asmComputeFramesHits.isNotEmpty()) {
            for ((loc, evidenceLine) in asmComputeFramesHits.distinctBy { it.first }.take(5)) {
                sink.add(
                    rule = JarRules.ASM_CLASSWRITER_COMPUTE_FRAMES_HAZARD,
                    location = loc,
                    detail = "ASM ClassWriter is constructed with COMPUTE_FRAMES (flag bit 0x2) without overriding " +
                        "getCommonSuperClass(String, String). Default ClassWriter calls Class.forName on the " +
                        "defining classloader, failing across isolated PaperPluginClassLoader boundaries.",
                    evidence = listOf(evidenceLine),
                    severityOverride = Severity.CRITICAL,
                )
            }
        }
    }

    private fun classifyClassWriterHierarchy(jar: JarInspection): Pair<Set<String>, Set<String>> {
        val byName = jar.classes.associateBy { it.internalName }
        val safe = HashSet<String>()
        val unsafe = HashSet<String>()

        fun extendsAsmClassWriter(start: String): Boolean {
            var cur = byName[start]?.superName
            val visited = HashSet<String>()
            while (cur != null && visited.add(cur)) {
                if (isAsmClassWriterInternalName(cur)) return true
                cur = byName[cur]?.superName
            }
            return false
        }

        fun overridesCommonSuperClass(start: String): Boolean {
            var cur: String? = start
            val visited = HashSet<String>()
            while (cur != null && visited.add(cur)) {
                val entry = byName[cur] ?: break
                if (GET_COMMON_SUPER_CLASS_SIG in entry.methods) return true
                cur = entry.superName
            }
            return false
        }

        for (entry in jar.classes) {
            if (isAsmClassWriterInternalName(entry.internalName)) continue
            if (extendsAsmClassWriter(entry.internalName)) {
                if (overridesCommonSuperClass(entry.internalName)) {
                    safe += entry.internalName
                } else {
                    unsafe += entry.internalName
                }
            }
        }
        return safe to unsafe
    }

    private fun isAsmClassWriterInternalName(internalName: String): Boolean =
        internalName == "org/objectweb/asm/ClassWriter" || internalName.endsWith("/asm/ClassWriter")

    private fun isAsmLibraryClass(internalName: String): Boolean =
        internalName.startsWith("org/objectweb/asm/") || internalName.contains("/asm/ClassReader") ||
            internalName.endsWith("/asm/ClassWriter") || internalName.contains("/asm/SymbolTable") ||
            internalName.contains("/asm/MethodWriter") || internalName.contains("/asm/Frame")

    private fun analyzeClass(
        bytes: ByteArray,
        className: String,
        isOwnClass: Boolean,
        isModernPaper: Boolean,
        safeCwSubclasses: Set<String>,
        unsafeCwSubclasses: Set<String>,
        frozenRegistryHits: MutableList<String>,
        legacyCodecHits: MutableList<String>,
        detachedSnapshotHits: MutableList<Pair<String, String>>,
        asmComputeFramesHits: MutableList<Pair<String, String>>,
    ) {
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    methodName: String,
                    methodDescriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val steps = ArrayList<MethodStep>()
                    return object : MethodVisitor(Opcodes.ASM9) {
                        private var line: Int? = null

                        override fun visitLineNumber(newLine: Int, start: Label?) {
                            line = newLine
                        }

                        override fun visitInsn(opcode: Int) {
                            when (opcode) {
                                in Opcodes.ICONST_M1..Opcodes.ICONST_5 ->
                                    steps += MethodStep.PushInt(opcode - Opcodes.ICONST_0, line)
                                Opcodes.ACONST_NULL,
                                Opcodes.LCONST_0, Opcodes.LCONST_1,
                                Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2,
                                Opcodes.DCONST_0, Opcodes.DCONST_1 ->
                                    steps += MethodStep.PushOther(line)
                                Opcodes.IOR, Opcodes.IADD, Opcodes.IXOR, Opcodes.IAND ->
                                    steps += MethodStep.IntBinOp(opcode, line)
                                Opcodes.DUP, Opcodes.POP, Opcodes.POP2, Opcodes.SWAP, Opcodes.AASTORE ->
                                    steps += MethodStep.StackManip(opcode, line)
                                in Opcodes.IRETURN..Opcodes.RETURN ->
                                    steps += MethodStep.ReturnInsn(opcode, line)
                            }
                        }

                        override fun visitIntInsn(opcode: Int, operand: Int) {
                            if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
                                steps += MethodStep.PushInt(operand, line)
                            } else if (opcode == Opcodes.NEWARRAY) {
                                steps += MethodStep.StackManip(opcode, line)
                            }
                        }

                        override fun visitLdcInsn(value: Any?) {
                            if (value is Int) {
                                steps += MethodStep.PushInt(value, line)
                            } else {
                                steps += MethodStep.PushOther(line)
                            }
                        }

                        override fun visitVarInsn(opcode: Int, varIndex: Int) {
                            when (opcode) {
                                Opcodes.ILOAD, Opcodes.ALOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD ->
                                    steps += MethodStep.VarLoad(opcode, varIndex, line)
                                Opcodes.ISTORE, Opcodes.ASTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE ->
                                    steps += MethodStep.VarStore(opcode, varIndex, line)
                            }
                        }

                        override fun visitTypeInsn(opcode: Int, type: String) {
                            steps += MethodStep.TypeInsn(opcode, type, line)
                            if (isOwnClass && isModernPaper && type == "org/bukkit/material/MaterialData") {
                                legacyCodecHits += "${formatLoc(className, methodName, line)} -> org.bukkit.material.MaterialData"
                            }
                        }

                        override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                            steps += MethodStep.FieldInsn(opcode, owner, name, descriptor, line)
                            if (isOwnClass && owner == "net/minecraft/core/MappedRegistry" &&
                                (name == "frozen" || name == "unregisteredIntrusiveHolders") &&
                                (opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC)
                            ) {
                                frozenRegistryHits += "${formatLoc(className, methodName, line)} mutates MappedRegistry#$name"
                            }
                        }

                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                            isInterface: Boolean,
                        ) {
                            steps += MethodStep.CallInsn(opcode, owner, name, descriptor, line)
                            if (isOwnClass) {
                                if (owner in FROZEN_REGISTRY_OWNERS && name in FROZEN_REGISTRY_MUTATORS) {
                                    frozenRegistryHits += "${formatLoc(className, methodName, line)} -> ${owner.replace('/', '.')}#$name$descriptor"
                                }
                                val mKey = name + descriptor
                                if (isModernPaper &&
                                    ((owner in LEGACY_RAW_BLOCK_OWNERS && mKey in LEGACY_RAW_BLOCK_METHODS) ||
                                        owner.startsWith("org/bukkit/material/MaterialData"))
                                ) {
                                    legacyCodecHits += "${formatLoc(className, methodName, line)} -> ${owner.replace('/', '.')}#$name$descriptor"
                                }
                            }
                        }

                        override fun visitInvokeDynamicInsn(
                            name: String,
                            descriptor: String,
                            bootstrapMethodHandle: Handle?,
                            vararg bootstrapMethodArguments: Any?,
                        ) {
                            steps += MethodStep.InvokeDynInsn(descriptor, line)
                        }

                        override fun visitEnd() {
                            simulateSteps(
                                className = className,
                                methodName = methodName,
                                isOwnClass = isOwnClass,
                                safeCwSubclasses = safeCwSubclasses,
                                unsafeCwSubclasses = unsafeCwSubclasses,
                                steps = steps,
                                detachedSnapshotHits = detachedSnapshotHits,
                                asmComputeFramesHits = asmComputeFramesHits,
                            )
                        }
                    }
                }
            },
            ClassReader.SKIP_FRAMES,
        )
    }

    private fun simulateSteps(
        className: String,
        methodName: String,
        isOwnClass: Boolean,
        safeCwSubclasses: Set<String>,
        unsafeCwSubclasses: Set<String>,
        steps: List<MethodStep>,
        detachedSnapshotHits: MutableList<Pair<String, String>>,
        asmComputeFramesHits: MutableList<Pair<String, String>>,
    ) {
        val stack = ArrayDeque<OpVal>()
        val locals = HashMap<Int, OpVal>()
        val snapshots = ArrayList<TrackedSnapshot>()

        fun pop(): OpVal = if (stack.isNotEmpty()) stack.removeLast() else OpVal.Other
        fun peek(): OpVal = if (stack.isNotEmpty()) stack.last() else OpVal.Other
        fun push(v: OpVal) {
            stack.addLast(v)
        }

        fun markEscaped(v: OpVal) {
            when (v) {
                is OpVal.DetachedState -> v.tracker.committedOrEscaped = true
                is OpVal.StateSubview -> v.tracker.committedOrEscaped = true
                else -> Unit
            }
        }

        for (step in steps) {
            when (step) {
                is MethodStep.PushInt -> push(OpVal.IntConst(step.value))
                is MethodStep.PushOther -> push(OpVal.Other)
                is MethodStep.IntBinOp -> {
                    val b = pop()
                    val a = pop()
                    if (a is OpVal.IntConst && b is OpVal.IntConst) {
                        val res = when (step.opcode) {
                            Opcodes.IOR -> a.value or b.value
                            Opcodes.IADD -> a.value + b.value
                            Opcodes.IXOR -> a.value xor b.value
                            Opcodes.IAND -> a.value and b.value
                            else -> 0
                        }
                        push(OpVal.IntConst(res))
                    } else {
                        push(OpVal.Other)
                    }
                }

                is MethodStep.VarLoad -> push(locals[step.slot] ?: OpVal.Other)
                is MethodStep.VarStore -> locals[step.slot] = pop()

                is MethodStep.StackManip -> {
                    when (step.opcode) {
                        Opcodes.DUP -> push(peek())
                        Opcodes.POP, Opcodes.POP2 -> pop()
                        Opcodes.SWAP -> {
                            if (stack.size >= 2) {
                                val top = stack.removeLast()
                                val second = stack.removeLast()
                                stack.addLast(top)
                                stack.addLast(second)
                            }
                        }
                        Opcodes.AASTORE -> {
                            val elem = pop()
                            markEscaped(elem)
                            pop()
                            pop()
                        }
                        Opcodes.NEWARRAY -> {
                            pop()
                            push(OpVal.Other)
                        }
                    }
                }

                is MethodStep.TypeInsn -> {
                    when (step.opcode) {
                        Opcodes.NEW -> push(OpVal.Other)
                        Opcodes.CHECKCAST -> Unit // Preserve DetachedState across (TileState) / (Sign) casts
                        Opcodes.ANEWARRAY, Opcodes.INSTANCEOF -> {
                            pop()
                            push(OpVal.Other)
                        }
                    }
                }

                is MethodStep.FieldInsn -> {
                    when (step.opcode) {
                        Opcodes.GETSTATIC -> push(OpVal.Other)
                        Opcodes.PUTSTATIC -> markEscaped(pop())
                        Opcodes.GETFIELD -> {
                            pop()
                            push(OpVal.Other)
                        }
                        Opcodes.PUTFIELD -> {
                            markEscaped(pop())
                            pop()
                        }
                    }
                }

                is MethodStep.CallInsn -> {
                    val argTypes = Type.getArgumentTypes(step.descriptor)
                    val args = MutableList(argTypes.size) { pop() }.asReversed()
                    val receiver = if (step.opcode == Opcodes.INVOKESTATIC) null else pop()

                    // 1. Check J078: ClassWriter.<init>(..., flags) with COMPUTE_FRAMES (bit 0x2)
                    if (step.opcode == Opcodes.INVOKESPECIAL && step.name == "<init>") {
                        val isTargetCw = isAsmClassWriterInternalName(step.owner) || step.owner in unsafeCwSubclasses
                        val isSuperInsideSafeSubclass =
                            methodName == "<init>" && className in safeCwSubclasses && isAsmClassWriterInternalName(step.owner)
                        if (isTargetCw && !isSuperInsideSafeSubclass) {
                            val flagsArg = when {
                                step.descriptor == "(I)V" -> args.getOrNull(0)
                                argTypes.size == 2 && argTypes[1].sort == Type.INT &&
                                    argTypes[0].internalName.endsWith("/ClassReader") -> args.getOrNull(1)
                                else -> null
                            }
                            if (flagsArg is OpVal.IntConst && (flagsArg.value and COMPUTE_FRAMES_FLAG) != 0) {
                                val loc = formatLoc(className, methodName, step.line)
                                val ev = "$loc -> new ${step.owner.replace('/', '.')}(flags=${flagsArg.value} [COMPUTE_FRAMES])"
                                asmComputeFramesHits += loc to ev
                            }
                        }
                    }

                    // 2. Check J077: Detached Block#getState() snapshot tracking inside own code
                    var pushedReturn = false
                    if (isOwnClass) {
                        // Passing a snapshot as an argument to another method counts as escaping
                        for (arg in args) markEscaped(arg)

                        val mKey = step.name + step.descriptor
                        if (step.owner == "org/bukkit/block/Block" && mKey == "getState()Lorg/bukkit/block/BlockState;") {
                            val tracker = TrackedSnapshot("Block#getState()", step.line)
                            snapshots += tracker
                            push(OpVal.DetachedState(tracker))
                            pushedReturn = true
                        } else if (step.owner == "org/bukkit/block/Block" && mKey == "getState(Z)Lorg/bukkit/block/BlockState;") {
                            // Block#getState(true) returns a detached snapshot; getState(false) is live
                            val useSnapshotArg = args.getOrNull(0)
                            if (useSnapshotArg is OpVal.IntConst && useSnapshotArg.value == 1) {
                                val tracker = TrackedSnapshot("Block#getState(true)", step.line)
                                snapshots += tracker
                                push(OpVal.DetachedState(tracker))
                                pushedReturn = true
                            }
                        } else if (receiver is OpVal.DetachedState) {
                            if (step.name == "update" && step.descriptor.endsWith(")Z")) {
                                receiver.tracker.committedOrEscaped = true
                            } else if (mKey in SNAPSHOT_SUBVIEW_METHODS) {
                                val retInternal = Type.getReturnType(step.descriptor).internalName
                                push(OpVal.StateSubview(receiver.tracker, retInternal))
                                pushedReturn = true
                            } else if (step.name in SNAPSHOT_DIRECT_MUTATORS) {
                                if (receiver.tracker.mutatedBy == null) {
                                    receiver.tracker.mutatedBy = "${step.owner.substringAfterLast('/')}#${step.name}"
                                    receiver.tracker.mutateLine = step.line
                                }
                            }
                        } else if (receiver is OpVal.StateSubview) {
                            val subMutators = SUBVIEW_MUTATORS[receiver.subviewOwner]
                            if (subMutators != null && step.name in subMutators) {
                                if (receiver.tracker.mutatedBy == null) {
                                    receiver.tracker.mutatedBy = "${receiver.subviewOwner.substringAfterLast('/')}#${step.name}"
                                    receiver.tracker.mutateLine = step.line
                                }
                            }
                        }
                    }

                    if (!pushedReturn && Type.getReturnType(step.descriptor).sort != Type.VOID) {
                        push(OpVal.Other)
                    }
                }

                is MethodStep.InvokeDynInsn -> {
                    val argCount = Type.getArgumentTypes(step.callSiteDescriptor).size
                    repeat(argCount) { markEscaped(pop()) }
                    if (Type.getReturnType(step.callSiteDescriptor).sort != Type.VOID) {
                        push(OpVal.Other)
                    }
                }

                is MethodStep.ReturnInsn -> {
                    if (step.opcode == Opcodes.ARETURN) {
                        markEscaped(pop())
                    }
                    stack.clear()
                }
            }
        }

        if (isOwnClass) {
            for (snap in snapshots) {
                val mut = snap.mutatedBy
                if (mut != null && !snap.committedOrEscaped) {
                    val loc = formatLoc(className, methodName, snap.mutateLine ?: snap.originLine)
                    detachedSnapshotHits += loc to mut
                }
            }
        }
    }

    private fun formatLoc(internalClass: String, methodName: String, line: Int?): String {
        val dotted = internalClass.replace('/', '.')
        return if (line != null) "$dotted#$methodName:$line" else "$dotted#$methodName"
    }
}
