package com.nanihadesuka.irisx.data.storage

import com.nanihadesuka.irisx.domain.repository.DiagnosticsLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class InMemoryDiagnosticsLog : DiagnosticsLog {
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    override val entries: StateFlow<List<String>> = _entries
    private val format = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun log(message: String) {
        android.util.Log.i("IrisXDiag", message)
        val line = "${format.format(Date())}  $message"
        _entries.update { (listOf(line) + it).take(MAX_ENTRIES) }
    }

    private companion object {
        const val MAX_ENTRIES = 200
    }
}
