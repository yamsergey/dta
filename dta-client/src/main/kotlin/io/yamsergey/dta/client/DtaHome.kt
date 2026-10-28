package io.yamsergey.dta.client

import java.io.File

/** DTA's state on the developer's machine: `$DTA_HOME`, else `-Ddta.home`, else `~/.dta`. */
class DtaHome(val root: File) {
    val daemonDir: File get() = File(root, "daemon")

    companion object {
        fun resolve(
            env: Map<String, String> = System.getenv(),
            property: String? = System.getProperty("dta.home"),
            userHome: String = System.getProperty("user.home"),
        ): DtaHome = DtaHome(
            File(env["DTA_HOME"]?.takeIf { it.isNotBlank() } ?: property?.takeIf { it.isNotBlank() } ?: "$userHome/.dta"),
        )
    }
}
