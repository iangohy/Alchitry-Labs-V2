package com.alchitry.labs2.parsers

import com.alchitry.labs2.Log
import com.alchitry.labs2.parsers.acf.types.Constraint
import com.alchitry.labs2.parsers.lucidv2.types.*
import com.alchitry.labs2.parsers.notations.NotationManager
import com.alchitry.labs2.project.QueueExhaustionException
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.*
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

class ProjectContext(val notationManager: NotationManager) : Closeable {
    var top: ModuleInstance? = null
    private val constraints = mutableListOf<Constraint>()
    private val globals = mutableMapOf<String, GlobalNamespace>()
    private val modules = mutableMapOf<String, Module>()
    private val testBenches = mutableMapOf<String, TestBench>()
    val scope = CoroutineScope(Dispatchers.Default)

    private val evaluationQueue = ConcurrentHashMap.newKeySet<Evaluable>()

    fun queue(evaluable: Evaluable) {
        evaluationQueue.add(evaluable)
    }

    var initializing: Boolean = false
        private set

    suspend fun initialize() {
        initializing = true
        processQueue()
        initializing = false
    }

    suspend fun processQueue() {
        repeat(1000) {
            if (evaluationQueue.isEmpty())
                return

            val items = evaluationQueue.toList()
            evaluationQueue.clear()

            // if the queue is small, it's faster to just process it here instead of launching jobs
            if (items.size < 4) {
                items.forEach { it.evaluate() }
            } else {
                coroutineScope {
                    items.forEach {
                        launch(Dispatchers.Default) {
                            it.evaluate()
                        }
                    }
                }
            }
        }
        throw QueueExhaustionException("Failed to resolve a stable state after 1000 iterations. There is likely a dependency loop.")
    }

    suspend fun convertToVerilog(): Map<String, String?> {
        val topInstance = top ?: error("Top level module instance missing!")
        val instances = mutableMapOf<String, ModuleInstance>()
        fun add(instance: ModuleInstance) {
            instances[instance.parameterizedModuleName] = instance
            instance.context.types.moduleInstances.values.forEach { moduleInstanceOrArray ->
                when (moduleInstanceOrArray) {
                    is ModuleInstance -> add(moduleInstanceOrArray)
                    is ModuleInstanceArray -> moduleInstanceOrArray.modules.forEach { add(it) }
                }
            }
        }
        add(topInstance)
        return instances.mapValues {
            try {
                it.value.context.convertToVerilog() ?: error("Missing verilog for ${it.key}")
            } catch (e: Exception) {
                Log.printlnError("Error while converting to Verilog! This is a bug!", e)
                null
            }
        }
    }

    override fun close() {
        scope.cancel("ProjectContext closed.")
    }

    fun getTestBenches(): List<TestBench> {
        return testBenches.values.toList()
    }

    /**
     * Overrides the global namespace with the given [GlobalNamespace].
     * This should only be used during simulations.
     * Use [addGlobal] to check for existing namespaces.
     *
     * @param globalNamespace the [GlobalNamespace] to override in the project.
     */
    fun overrideGlobal(globalNamespace: GlobalNamespace) {
        globals[globalNamespace.name] = globalNamespace
    }

    /**
     * Adds the provided [GlobalNamespace] to the global namespace map if it doesn't already exist.
     *
     * @param globalNamespace the [GlobalNamespace] to be added to the global namespace map.
     *
     * @return true if the [globalNamespace] is added successfully, otherwise false.
     */
    fun addGlobal(globalNamespace: GlobalNamespace): Boolean =
        globals.putIfAbsent(globalNamespace.name, globalNamespace) == null

    fun addModule(module: Module): Boolean =
        modules.putIfAbsent(module.name, module) == null

    fun addTestBench(testBench: TestBench): Boolean =
        testBenches.putIfAbsent(testBench.name, testBench) == null

    /**
     * Searches all GlobalNamespaces to resolve a signal name.
     */
    fun resolveSignal(name: String): SignalOrParent? = globals[name]

    fun resolveGlobal(name: String) = globals[name]

    fun resolveModuleType(name: String): Module? = modules[name]

    private fun areOverlapping(a: SignalOrSubSignal, b: SignalOrSubSignal): Boolean {
        if (a.getSignal().name != b.getSignal().name)
            return false

        val selectionA = (a as? SubSignal)?.selection ?: emptyList()
        val selectionB = (b as? SubSignal)?.selection ?: emptyList()
        return selectionA.overlaps(selectionB)
    }

    enum class AddConstraintResult {
        Success,
        PinTaken,
        PortTaken
    }

    fun addConstraint(constraint: Constraint): AddConstraintResult {
        if (constraints.any { it.pin == constraint.pin })
            return AddConstraintResult.PinTaken
        if (constraints.any { areOverlapping(it.port, constraint.port) })
            return AddConstraintResult.PortTaken
        constraints.add(constraint)
        return AddConstraintResult.Success
    }

    fun getConstraints(): List<Constraint> = constraints.toImmutableList()
}

