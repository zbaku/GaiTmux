package com.aishell.engine

import com.aishell.domain.entity.ConversationContext
import com.aishell.domain.entity.ShellCapabilities
import com.aishell.domain.tool.Tool
import com.aishell.domain.entity.RiskLevel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemPromptBuilder @Inject constructor() {

    /**
     * Build a system prompt with tool descriptions and environment context.
     *
     * @param tools Available tools
     * @param context Conversation context with device info and capabilities
     */
    fun build(tools: List<Tool>, context: ConversationContext? = null): String {
        val toolDescriptions = tools.joinToString("\n") { tool ->
            "- ${tool.name}: ${tool.description} (风险: ${getRiskLabel(tool.riskLevel)})"
        }

        val envSection = buildEnvironmentSection(context)
        val riskSection = buildRiskSection()

        return """
            |你是 AIShell，一个运行在 Android 设备上的 AI 终端助手。用户通过自然语言描述任务，你解析意图并执行终端命令。
            |
            |可用工具：
            |$toolDescriptions
            |
            |$envSection
            |
            |$riskSection
            |
            |执行规则：
            |1. 解析用户意图，选择合适的工具和执行环境
            |2. 根据风险级别决定是否需要用户确认
            |3. 每次只执行一条命令，等待结果后再决定下一步
            |4. 命令失败时分析原因并尝试修正
            |5. 危险操作必须先告知风险
            |6. 用中文简洁回复
            |
            |工作流程：
            |1. 理解用户输入的意图
            |2. 判断需要的工具、参数和执行环境
            |3. 根据风险级别决定是否需要确认
            |4. 执行后返回结果给用户
            |5. 根据结果决定是否需要继续操作
        """.trimMargin()
    }

    /**
     * Build environment-aware section based on device capabilities.
     */
    private fun buildEnvironmentSection(context: ConversationContext?): String {
        if (context == null) {
            return """
                |执行环境：
                |- Android Shell: 默认执行环境
            """.trimMargin()
        }

        val caps = context.shellCapabilities
        val device = context.deviceInfo

        val envLines = mutableListOf<String>()
        envLines.add("- Android Shell: 默认执行环境 (可用 pm, am, input, settings 等)")

        if (caps.supportsProot) {
            envLines.add("- Ubuntu (proot): 完整 Linux 环境 (可用 apt, python3, git, node, go, curl, ssh 等)")
        }
        if (caps.supportsShizuku || device.isRooted) {
            val accessType = if (device.isRooted) "Root 权限" else "Shizuku 权限"
            envLines.add("- ${accessType}: 系统级权限，可完全访问文件系统")
        }
        if (caps.supportsAdb) {
            envLines.add("- ADB: 可操作连接的 Android 设备")
        }
        if (caps.supportsFastboot) {
            envLines.add("- Fastboot: 可刷入连接设备的分区")
        }

        val statusLines = mutableListOf<String>()
        if (device.isRooted) statusLines.add("- 设备已 Root")
        if (device.hasShizuku) statusLines.add("- Shizuku 已授权")
        if (device.hasProot) statusLines.add("- Ubuntu 环境已就绪")

        val statusSection = if (statusLines.isNotEmpty()) {
            "\n当前状态:\n" + statusLines.joinToString("\n")
        } else ""

        return "执行环境:\n" + envLines.joinToString("\n") + statusSection
    }

    /**
     * Build risk level explanation section (5-level system).
     */
    private fun buildRiskSection(): String {
        return """
            |风险级别说明：
            |- READ_ONLY: 只读操作，无风险，自动执行 (如 ls, cat, pwd, echo)
            |- LOW: 低风险读取，可能访问敏感路径 (如 cat /proc, find /data)
            |- MEDIUM: 修改文件/安装软件，需确认 (如 mkdir, cp, mv, apt install)
            |- HIGH: 高风险系统操作 (如 rm, mount, iptables)
            |- CRITICAL: 不可逆的危险操作 (如 rm -rf /, dd, mkfs, fastboot flash)
        """.trimMargin()
    }

    private fun getRiskLabel(riskLevel: RiskLevel): String = when (riskLevel) {
        RiskLevel.READ_ONLY -> "只读"
        RiskLevel.LOW -> "低风险"
        RiskLevel.MEDIUM -> "需确认"
        RiskLevel.HIGH -> "高风险"
        RiskLevel.CRITICAL -> "危险"
    }
}