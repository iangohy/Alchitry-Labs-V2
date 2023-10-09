package com.alchitry.labs.parsers.lucidv2.types

import com.alchitry.labs.parsers.ProjectContext
import com.alchitry.labs.parsers.errors.ErrorCollector
import com.alchitry.labs.parsers.lucidv2.context.LucidBlockContext
import com.alchitry.labs.parsers.lucidv2.signals.snapshot.SnapshotOrParent
import com.alchitry.labs.parsers.lucidv2.signals.snapshot.SnapshotParent
import com.alchitry.labs.parsers.lucidv2.values.Value

class ModuleInstance(
    override val name: String,
    project: ProjectContext,
    override val parent: ModuleInstance?,
    val module: Module,
    parameters: Map<String, Value>,
    val connections: Map<String, SignalOrSubSignal>,
    errorCollector: ErrorCollector
) : ModuleInstanceOrArray, ListOrModuleInstance, TestOrModuleInstance {
    override val context = LucidBlockContext(project, this, errorCollector = errorCollector)


    override fun takeSnapshot(): SnapshotParent {
        val snapshots = mutableListOf<SnapshotOrParent>()
        snapshots.addAll(context.types.dffs.values.map { it.takeSnapshot() })
        snapshots.addAll(context.types.sigs.values.map { it.takeSnapshot() })
        snapshots.addAll(context.types.moduleInstances.values.map { it.takeSnapshot() })
        snapshots.addAll(internal.values.map { it.takeSnapshot() })
        return SnapshotParent(name, snapshots)
    }

    suspend fun checkParameters(): Boolean = context.checkParameters()
    suspend fun initialWalk() = context.initialWalk(module.context)

    val ports = module.ports.mapValues { (_, port) ->
        port.instantiate(this, project)
    }

    override val internal: Map<String, Signal> = ports.mapValues { it.value.internal }
    override val external: Map<String, Signal> = ports
        .filter { !connections.contains(it.key) }
        .mapValues { it.value.external }

    init {
        connections.forEach { (name, sig) ->
            val port = ports[name]?.external ?: error("No matching port for given connection \"$name\"!")
            if (port.direction.canWrite)
                sig.connectTo(port, project)
            if (port.direction.canRead)
                port.connectTo(sig, project)
        }
    }

    // Use the provided parameters or the default value from the module is it is missing
    val parameters = module.parameters.mapValues { (name, param) ->
        Signal(
            name,
            SignalDirection.Read,
            this,
            parameters[name] ?: param.default ?: error("Missing module parameter!")
        )
    }

    /**
     * Generates a unique suffix to attach to this instance corresponding to the parameter set.
     */
    private fun generateSuffix(): String {
        if (parameters.isEmpty())
            return ""
        var hash = 0
        // sort the list to make it order independent
        parameters.asIterable().sortedBy { it.key }.forEach { (name, signal) ->
            hash += name.hashCode()
            hash *= 31
            hash += signal.initialValue.hashCode()
            hash *= 31
        }
        return "_${hash.toUInt().toString(16)}"
    }

    /**
     * A name for this instance that is unique for this set of parameters.
     */
    val parameterizedModuleName: String = "MI_${module.name}${generateSuffix()}"

    fun getInternalSignal(name: String) = internal[name] ?: parameters[name]
    override fun getSignal(name: String) = external[name]
}
