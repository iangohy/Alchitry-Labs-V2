package com.alchitry.labs2.subcommands

import com.alchitry.labs2.project.Project
import com.alchitry.labs2.project.openXml
import com.alchitry.labs2.showHelp
import kotlinx.cli.ArgType
import kotlinx.cli.ExperimentalCli
import kotlinx.cli.Subcommand
import kotlinx.cli.default
import kotlinx.coroutines.runBlocking
import java.io.File

@OptIn(ExperimentalCli::class)
class BuildProject : Subcommand("build", "Build an Alchitry Project") {
    private val project by argument(ArgType.String, "project", "Alchitry project file")

    private val flash by option(ArgType.Boolean, "flash", "f", "Load project to FPGA's flash (persistent)").default(
        false
    )
    private val ram by option(ArgType.Boolean, "ram", "r", "Load project to FPGA's RAM (temporary)").default(false)
    private val board by option(ArgType.Int, "device", "d", "Index of device to load").default(0)

    override fun execute() {
        if (flash && ram) {
            showHelp("Commands flash and ram can't both be specified!")
            return
        }

        val project = try {
            Project.openXml(File(project))
        } catch (e: Exception) {
            System.err.println("Failed to open project:")
            System.err.println("     ${e.message}")
            return
        }

        if (!runBlocking { project.build() })
            return

        if (flash || ram) {
            showHelp("Loading isn't implemented yet!")
        }
    }
}