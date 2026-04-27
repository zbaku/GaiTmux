package com.aishell.engine

import com.aishell.domain.tool.Tool
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolRegistry @Inject constructor(
    private val tools: Set<@JvmSuppressWildcards Tool>
) {
    @PublishedApi
    internal val toolMap = ConcurrentHashMap<String, Tool>(tools.associateBy { it.name })

    fun get(name: String): Tool? = toolMap[name]

    fun getAll(): List<Tool> = toolMap.values.toList()

    fun getByCategory(category: String): List<Tool> =
        toolMap.values.filter { it.name.startsWith("$category.") || it.name == category }

    fun register(tool: Tool) {
        toolMap[tool.name] = tool
    }

    fun unregister(name: String) {
        toolMap.remove(name)
    }

    inline fun <reified T : Tool> getAllOfType(): List<T> =
        toolMap.values.filterIsInstance<T>()

    fun size(): Int = toolMap.size
}