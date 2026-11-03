package com.nanihadesuka.irisx.data.storage

import com.nanihadesuka.irisx.domain.model.TransferState
import com.nanihadesuka.irisx.domain.repository.SyncActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class InMemorySyncActivity : SyncActivity {
    private val _transfer = MutableStateFlow<TransferState>(TransferState.Idle)
    override val transfer: StateFlow<TransferState> = _transfer
    @Volatile override var changedAtMillis: Long = System.currentTimeMillis()
        private set
    override fun set(state: TransferState) {
        if (_transfer.value != state) changedAtMillis = System.currentTimeMillis()
        _transfer.value = state
    }
}
