package com.mbmlife.companion.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DiagnosticLogger(private val dao: TrackingDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun info(tag: String, message: String, detailsJson: String? = null) =
        write("INFO", tag, message, detailsJson)

    fun warn(tag: String, message: String, detailsJson: String? = null) =
        write("WARN", tag, message, detailsJson)

    fun error(tag: String, message: String, detailsJson: String? = null) =
        write("ERROR", tag, message, detailsJson)

    private fun write(level: String, tag: String, message: String, detailsJson: String?) {
        when (level) {
            "ERROR" -> Log.e("MBM/$tag", "$message ${detailsJson.orEmpty()}")
            "WARN" -> Log.w("MBM/$tag", "$message ${detailsJson.orEmpty()}")
            else -> Log.i("MBM/$tag", "$message ${detailsJson.orEmpty()}")
        }
        scope.launch {
            dao.insertLog(
                DiagnosticLogEntity(
                    timestampMs = System.currentTimeMillis(),
                    level = level,
                    tag = tag,
                    message = message,
                    detailsJson = detailsJson
                )
            )
            dao.trimLogs()
        }
    }
}
