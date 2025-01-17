package eu.darken.sdmse.systemcleaner.core.tasks

import eu.darken.sdmse.R
import eu.darken.sdmse.common.ca.CaString
import eu.darken.sdmse.common.ca.toCaString
import eu.darken.sdmse.common.pkgs.Pkg
import eu.darken.sdmse.main.core.SDMTool
import kotlinx.parcelize.Parcelize

@Parcelize
data class SystemCleanerScanTask(
    val pkgIdFilter: Set<Pkg.Id> = emptySet(),
    val isWatcherTask: Boolean = false,
) : SystemCleanerTask {

    sealed interface Result : SystemCleanerTask.Result {
        override val type: SDMTool.Type get() = SDMTool.Type.SYSTEMCLEANER
    }

    @Parcelize
    data class Success(
        private val itemCount: Int,
        private val recoverableSpace: Long
    ) : Result {
        override val primaryInfo: CaString
            get() = R.string.general_result_success_message.toCaString()
    }
}