package com.alchitry.labs.project

import com.alchitry.labs.Env
import com.alchitry.labs.PathUtil
import com.alchitry.labs.Settings
import javax.swing.filechooser.FileSystemView

object Locations {
    val library: String = "/library"
    val components: String = "$library/components"
    val project: String = "$library/projects"
    val lucidProjects = "$project/Lucid"
    val verilogProjects = "$project/Verilog"

    val workspace: String = Settings.workspace ?: PathUtil.assemblePath(
        FileSystemView.getFileSystemView().defaultDirectory.path,
        "alchitry"
    )

    val osDir: String
        get() =
            when (Env.os) {
                Env.OS.Windows -> "windows"
                Env.OS.Linux -> "linux"
                Env.OS.MacOS -> "macos"
                else -> throw IllegalStateException("OS type is unknown!")
            }

    object Project {
        const val ipCoresFolder = "cores"
        const val sourceFolder = "source"
        const val constraintFolder = "constraint"
        const val workFolder = "work"
    }
}