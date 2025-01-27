package com.alchitry.labs2.project.builders

import com.alchitry.labs2.Log
import com.alchitry.labs2.project.Languages
import com.alchitry.labs2.project.Locations
import com.alchitry.labs2.project.Project
import com.alchitry.labs2.ui.theme.AlchitryColors
import kotlinx.coroutines.coroutineScope
import java.io.File

data class ClockConstraint(
    val port: String,
    val frequency: Double // in MHz
)

data object IceStormBuilder : ProjectBuilder() {
    /**
     * Parse the sdc file for clock constraints that can be passed to nextpnr
     */
    private fun getClockConstraints(sdcFile: File): List<ClockConstraint> {
        val periodRegex = Regex("-period ([0-9.]+)")
        val portRegex = Regex("\\[get_ports (.+?)]")
        return sdcFile.inputStream().bufferedReader().readLines().mapNotNull {
            val period = periodRegex.find(it)?.groupValues?.lastOrNull() ?: return@mapNotNull null
            val port = portRegex.find(it)?.groupValues?.lastOrNull() ?: return@mapNotNull null
            val frequency = 1000000000.0 / period.toDouble() / 1000000.0
            ClockConstraint(port, frequency)
        }
    }

    override suspend fun buildProject(
        project: Project,
        topModuleName: String,
        sourceFiles: List<File>,
        constraintFiles: List<File>
    ) = coroutineScope {
        val yosys = Locations.getToolNamed("yosys").absolutePath
        val nextpnr = Locations.getToolNamed("nextpnr-ice40").absolutePath
        val icepack = Locations.getToolNamed("icepack").absolutePath

        val jsonFile = project.buildDirectory.resolve("alchitry.json")

        val yosysCmd = mutableListOf(
            yosys,
            "-p",
            "synth_ice40 -json ${jsonFile.absolutePath} -top $topModuleName",
        )
        yosysCmd.addAll(sourceFiles.map { it.absolutePath })

        Log.println("Starting yosys...", AlchitryColors.current.Info)
        val yosysStatus = runProcess(yosysCmd, this)

        if (yosysStatus != 0) {
            Log.printlnError("Yosys exited with status: $yosysStatus")
            return@coroutineScope
        }

        val pcf = constraintFiles.firstOrNull { it.extension == Languages.PCF.extension }
            ?: error("Missing PCF constraint file!")

        val ascFile = project.buildDirectory.resolve("alchitry.asc")

        val sdcFile = constraintFiles.firstOrNull { it.extension == Languages.SDC.extension }
            ?: error("Missing sdc constraint file!")
        val clockConstraints = getClockConstraints(sdcFile)

        val pythonConstraintFile = project.buildDirectory.resolve("clocks.py")

        pythonConstraintFile.writeText(
            buildString {
                clockConstraints.forEach {
                    append("ctx.addClock(\"")
                    append(it.port)
                    append("\", ")
                    append(it.frequency.toString())
                    appendLine(")")
                }
            }
        )

        val nextpnrCmd = listOf(
            nextpnr,
            "--hx8k",
            "--package",
            "cb132",
            "--pre-pack",
            pythonConstraintFile.absolutePath,
            "--json",
            jsonFile.absolutePath,
            "--pcf",
            pcf.absolutePath,
            "--asc",
            ascFile.absolutePath
        )

        Log.println("Starting nextpnr...", AlchitryColors.current.Info)
        val nextpnrStatus = runProcess(nextpnrCmd, this, false)

        if (nextpnrStatus != 0) {
            Log.printlnError("Nextpnr exited with status: $nextpnrStatus")
            return@coroutineScope
        }

        val icepackCmd = listOf(
            icepack,
            ascFile.absolutePath,
            project.binFile.absolutePath
        )

        Log.println("Starting icepack...", AlchitryColors.current.Info)
        val icepackStatus = runProcess(icepackCmd, this)

        if (icepackStatus != 0) {
            Log.printlnError("Icepack exited with status: $icepackStatus")
            return@coroutineScope
        }

        Log.println("")

        if (project.binFile.exists()) {
            Log.println("Project built successfully.", AlchitryColors.current.Success)
        } else {
            Log.println(
                "Bin file (${project.binFile.absolutePath}) could not be found! The build likely failed.",
                AlchitryColors.current.Error
            )
        }
    }
}