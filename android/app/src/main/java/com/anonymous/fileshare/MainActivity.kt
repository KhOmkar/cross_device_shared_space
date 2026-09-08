package com.anonymous.fileshare

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.anonymous.fileshare.ui.MainScreen
import com.anonymous.fileshare.ui.TransferStateViewModel

// Hollow Knight inspired dark, luminous aesthetic
private val HollowKnightColorScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),        // Soul cyan glow
    onPrimary = Color(0xFF080B11),
    primaryContainer = Color(0xFF0284C7),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = Color(0xFFF6C453),      // Geo gold
    onSecondary = Color(0xFF080B11),
    secondaryContainer = Color(0xFF78590C),
    onSecondaryContainer = Color(0xFFFEF3C7),
    background = Color(0xFF080B11),     // Deep Obsidian void
    onBackground = Color(0xFFF0F6FC),
    surface = Color(0xFF0D121C),        // Elevated surface
    onSurface = Color(0xFFF0F6FC),
    surfaceVariant = Color(0xFF141B29), // Cards & Containers
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF64748B),
    outlineVariant = Color(0xFF232F44), // Subtle borders
    error = Color(0xFFEF4444),
    onError = Color(0xFFFFFFFF)
)

class MainActivity : ComponentActivity() {

    private val viewModel: TransferStateViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Permissions handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRequiredPermissions()

        setContent {
            MaterialTheme(
                colorScheme = HollowKnightColorScheme
            ) {
                MainScreen(viewModel = viewModel)
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val ungranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungranted.isNotEmpty()) {
            requestPermissionLauncher.launch(ungranted.toTypedArray())
        }
    }
}
