package com.alchitry.labs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * This takes multiple StateFlows and merges their updates into one call.
 * @param dependencies a list of StateFlows to merge
 * @param onChange a callback to trigger on any update to dependencies. This is guaranteed to not be called concurrently.
 */
suspend fun onAnyChange(dependencies: List<StateFlow<*>>, onChange: ()->Unit): Nothing = coroutineScope {
    val changeFlow = MutableStateFlow(0)
    dependencies.forEach {
        launch {
            it.collect{
                changeFlow.getAndUpdate { it + 1 }
            }
        }
    }
    changeFlow.collect {
        onChange()
    }
}