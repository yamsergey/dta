package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.protocol.network.NetworkEntry
import io.yamsergey.dta.protocol.network.NetworkQuery

/**
 * Feature 071 (FR-001, FR-003; D-079): whether a live entry is in [NetworkQuery]'s scope. One rule for every
 * live path (a plugin's view, a CLI watch, a plain subscription): without the archive switch only the app's
 * current run; an entry of an earlier run that reaches this computer later (a late update, a fresh daemon
 * catching up the device journal) is not. The switch, or an explicit archived scope, takes every run.
 * [currentRun] null: the current run is not known (the app keeps no journal here), so nothing is held back.
 */
object LiveScope {
    fun includes(query: NetworkQuery, entry: NetworkEntry, currentRun: String?): Boolean =
        query.archive || query.archiveScope != null || currentRun == null || entry.runId == currentRun
}
