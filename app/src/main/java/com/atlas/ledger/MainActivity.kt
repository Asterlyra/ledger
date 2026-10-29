package com.atlas.ledger

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import com.atlas.ledger.ui.LedgerApp
import com.atlas.ledger.ui.LedgerTheme

class MainActivity : ComponentActivity() {
    private var recurringRequest by mutableIntStateOf(0)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_recurring", false)) recurringRequest++
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("open_recurring", false)) recurringRequest++
        enableEdgeToEdge()
        setContent { LedgerTheme { LedgerApp(recurringRequest = recurringRequest) } }
    }
}
