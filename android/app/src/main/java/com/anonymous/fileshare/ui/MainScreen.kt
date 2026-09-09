package com.anonymous.fileshare.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anonymous.fileshare.service.FileShareForegroundService
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: TransferStateViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    var selectedPeer by remember { mutableStateOf<FileShareForegroundService.DiscoveredPeer?>(null) }
    var showManualFallback by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "RadarSweep")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "SweepAngle"
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    // Incoming Connection Request Modal / Alert Dialog
    uiState.incomingConnectionRequest?.let { req ->
        AlertDialog(
            onDismissRequest = { viewModel.rejectConnection() },
            icon = {
                Icon(
                    Icons.Default.WifiTethering,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text("Connection Request", fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "'${req.alias}' (${req.platform}) wants to connect for encrypted file sharing. Allow this session?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.acceptConnection() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Allow")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { viewModel.rejectConnection() }
                ) {
                    Text("Deny", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }

    // Selected Peer Info Modal
    selectedPeer?.let { peer ->
        AlertDialog(
            onDismissRequest = { selectedPeer = null },
            icon = {
                Icon(
                    when (peer.icon) {
                        "phone" -> Icons.Default.Smartphone
                        "laptop" -> Icons.Default.Laptop
                        else -> Icons.Default.Computer
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(peer.alias, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Platform: ${peer.platform}", style = MaterialTheme.typography.bodyMedium)
                    Text("Status: Discovered on Local Network", style = MaterialTheme.typography.bodySmall, color = Color(0xFF10B981))
                    Text("Tap 'Connect' to request pairing with this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.invitePeer(peer.peerId)
                        selectedPeer = null
                    }
                ) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedPeer = null }) {
                    Text("Close")
                }
            }
        )
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                val (name, size) = queryFileMetadata(context, uri)
                viewModel.sendFile(uri, name, size)
            }
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {}
            viewModel.setCustomSaveDirectory(uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚡ Anonymous File Share", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                "V1 Local",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Hotspot / Server Status Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    if (uiState.isServerRunning) "Local Server Active" else "Local Server Inactive",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    uiState.statusMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (uiState.isGuestConnected) MaterialTheme.colorScheme.primary
                                        else if (uiState.isServerRunning) MaterialTheme.colorScheme.secondary
                                        else MaterialTheme.colorScheme.error
                                    )
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!uiState.isServerRunning) {
                                Button(
                                    onClick = { viewModel.startServer() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Start Server")
                                }
                            } else {
                                Button(
                                    onClick = { viewModel.stopServer() },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Stop Session")
                                }
                            }

                            OutlinedButton(
                                onClick = { viewModel.openHotspotSettings() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.WifiTethering, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Hotspot")
                            }
                        }
                    }
                }
            }

            // Connected Device Info Card
            if (uiState.isGuestConnected) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Devices,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        "Connected Peer",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    Text(
                                        uiState.connectedPeerAlias ?: "Guest PC / Browser",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF10B981).copy(alpha = 0.15f)
                            ) {
                                Text(
                                    "Encrypted",
                                    color = Color(0xFF10B981),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Save Folder Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    "Save Received Files To:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                Text(
                                    uiState.saveFolderDisplayName,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        TextButton(onClick = { folderPickerLauncher.launch(null) }) {
                            Text("Change", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            // Radar Interface & Connection Box (Visible when server is running and not paired)
            if (uiState.isServerRunning && !uiState.isGuestConnected) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "Device Radar",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary)
                                        )
                                    }
                                    Text(
                                        "Scanning hotspot for nearby devices",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                ) {
                                    Text(
                                        "${uiState.discoveredPeers.size} Detected",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Radar Canvas Container
                            val primaryColor = MaterialTheme.colorScheme.primary
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(240.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0xFF090E17)),
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val center = Offset(size.width / 2f, size.height / 2f)
                                    val maxRadius = (minOf(size.width, size.height) / 2f) * 0.85f

                                    // Concentric rings
                                    drawCircle(
                                        color = primaryColor.copy(alpha = 0.12f),
                                        radius = maxRadius * 0.35f,
                                        center = center,
                                        style = Stroke(width = 1.5f)
                                    )
                                    drawCircle(
                                        color = primaryColor.copy(alpha = 0.18f),
                                        radius = maxRadius * 0.68f,
                                        center = center,
                                        style = Stroke(width = 1.5f)
                                    )
                                    drawCircle(
                                        color = primaryColor.copy(alpha = 0.28f),
                                        radius = maxRadius,
                                        center = center,
                                        style = Stroke(width = 2f)
                                    )

                                    // Crosshairs
                                    drawLine(
                                        color = primaryColor.copy(alpha = 0.15f),
                                        start = Offset(center.x - maxRadius, center.y),
                                        end = Offset(center.x + maxRadius, center.y),
                                        strokeWidth = 1f
                                    )
                                    drawLine(
                                        color = primaryColor.copy(alpha = 0.15f),
                                        start = Offset(center.x, center.y - maxRadius),
                                        end = Offset(center.x, center.y + maxRadius),
                                        strokeWidth = 1f
                                    )

                                    // Rotating radar sweep
                                    rotate(degrees = sweepAngle, pivot = center) {
                                        drawArc(
                                            brush = Brush.sweepGradient(
                                                0.0f to Color.Transparent,
                                                0.75f to Color.Transparent,
                                                1.0f to primaryColor.copy(alpha = 0.35f),
                                                center = center
                                            ),
                                            startAngle = 0f,
                                            sweepAngle = 90f,
                                            useCenter = true,
                                            topLeft = Offset(center.x - maxRadius, center.y - maxRadius),
                                            size = androidx.compose.ui.geometry.Size(maxRadius * 2, maxRadius * 2)
                                        )
                                    }
                                }

                                // Center Host Node (Phone)
                                Surface(
                                    shape = CircleShape,
                                    color = primaryColor.copy(alpha = 0.2f),
                                    modifier = Modifier.size(44.dp * pulseScale)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Surface(
                                            shape = CircleShape,
                                            color = primaryColor,
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Smartphone,
                                                contentDescription = "Host Phone",
                                                tint = Color.Black,
                                                modifier = Modifier.padding(4.dp)
                                            )
                                        }
                                    }
                                }

                                // Render Discovered Peer Nodes on Radar Orbit
                                uiState.discoveredPeers.forEachIndexed { index, peer ->
                                    val angleDeg = (index * 90.0) + 45.0
                                    val angleRad = Math.toRadians(angleDeg)
                                    val orbitDistance = 65.dp

                                    val xOffset = (cos(angleRad) * orbitDistance.value).dp
                                    val yOffset = (sin(angleRad) * orbitDistance.value).dp

                                    Box(
                                        modifier = Modifier
                                            .offset(x = xOffset, y = yOffset)
                                            .clickable { selectedPeer = peer },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Surface(
                                                shape = CircleShape,
                                                color = Color(0xFF10B981).copy(alpha = 0.25f),
                                                modifier = Modifier.size(40.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Surface(
                                                        shape = CircleShape,
                                                        color = Color(0xFF10B981),
                                                        modifier = Modifier.size(26.dp)
                                                    ) {
                                                        Icon(
                                                            when (peer.icon) {
                                                                "phone" -> Icons.Default.Smartphone
                                                                "laptop" -> Icons.Default.Laptop
                                                                else -> Icons.Default.Computer
                                                            },
                                                            contentDescription = peer.alias,
                                                            tint = Color.Black,
                                                            modifier = Modifier.padding(4.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = Color(0xFF0F172A).copy(alpha = 0.9f)
                                            ) {
                                                Text(
                                                    peer.alias.take(16),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color.White,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                if (uiState.discoveredPeers.isEmpty())
                                    "Open http://share.local:8080 on PC/phone to appear on radar"
                                else
                                    "Tap any detected device node above to connect",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            // Expandable Manual Fallback Accordion
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { showManualFallback = !showManualFallback },
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.Pin,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                "Manual Pairing Fallback",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        Icon(
                                            if (showManualFallback) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    AnimatedVisibility(visible = showManualFallback) {
                                        Column(modifier = Modifier.padding(top = 10.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column {
                                                    Text(
                                                        "PAIRING CODE",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                    Text(
                                                        uiState.pairingCode,
                                                        fontSize = 24.sp,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        letterSpacing = 3.sp
                                                    )
                                                }
                                                IconButton(
                                                    onClick = {
                                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(uiState.pairingCode))
                                                    }
                                                ) {
                                                    Icon(
                                                        Icons.Default.ContentCopy,
                                                        contentDescription = "Copy Pairing Code",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(8.dp))
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                            Spacer(modifier = Modifier.height(8.dp))

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        "Short URL: ${uiState.shortUrl}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Text(
                                                        "Direct IP: ${uiState.ipUrl}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = MaterialTheme.colorScheme.outline
                                                    )
                                                }
                                                IconButton(
                                                    onClick = {
                                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(uiState.shortUrl))
                                                    },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.ContentCopy,
                                                        contentDescription = "Copy Short URL",
                                                        modifier = Modifier.size(16.dp),
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // File Send Action
                item {
                    Button(
                        onClick = { filePickerLauncher.launch("*/*") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        enabled = uiState.isGuestConnected
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (uiState.isGuestConnected) "Send File to Guest" else "Waiting for Guest to Connect...",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Active Transfers List
            if (uiState.activeTransfers.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Active & Recent Transfers",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = { viewModel.clearTransfers() }) {
                            Text("Clear")
                        }
                    }
                }

                items(uiState.activeTransfers, key = { it.transferId }) { transfer ->
                    TransferItemRow(
                        transfer = transfer,
                        onCancel = { txId -> viewModel.cancelTransfer(txId) }
                    )
                }
            }

            // Live System Logs Section
            item {
                var showLogs by remember { mutableStateOf(false) }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Live System Logs (${logs.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Row {
                                if (logs.isNotEmpty()) {
                                    TextButton(onClick = {
                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(logs.joinToString("\n")))
                                    }) {
                                        Text("Copy")
                                    }
                                    TextButton(onClick = { viewModel.clearLogs() }) {
                                        Text("Clear")
                                    }
                                }
                                TextButton(onClick = { showLogs = !showLogs }) {
                                    Text(if (showLogs) "Hide" else "Show")
                                }
                            }
                        }

                        if (showLogs) {
                            Spacer(modifier = Modifier.height(8.dp))
                            if (logs.isEmpty()) {
                                Text(
                                    "No logs recorded yet.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF080B11), RoundedCornerShape(8.dp))
                                        .padding(8.dp)
                                        .heightIn(max = 240.dp)
                                ) {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        reverseLayout = true
                                    ) {
                                        items(logs.reversed()) { logLine ->
                                            Text(
                                                logLine,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = when {
                                                    logLine.contains("ERROR") -> Color(0xFFEF4444)
                                                    logLine.contains("WARN") -> Color(0xFFF59E0B)
                                                    logLine.contains("Saved") || logLine.contains("Verified") || logLine.contains("Success") -> Color(0xFF10B981)
                                                    else -> Color(0xFF94A3B8)
                                                },
                                                modifier = Modifier.padding(vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TransferItemRow(
    transfer: TransferItemUiState,
    onCancel: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    (if (transfer.isUpload) "↑ " else "↓ ") + transfer.filename,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${transfer.progressPct}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (transfer.status == TransferStatus.TRANSFERRING) {
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = { onCancel(transfer.transferId) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Cancel Transfer",
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { transfer.progressPct / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                color = when (transfer.status) {
                    TransferStatus.COMPLETED -> Color(0xFF10B981)
                    TransferStatus.FAILED -> Color(0xFFEF4444)
                    TransferStatus.CANCELLED -> Color(0xFFEF4444)
                    TransferStatus.TRANSFERRING -> MaterialTheme.colorScheme.primary
                }
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    when (transfer.status) {
                        TransferStatus.COMPLETED -> "Verified SHA-256"
                        TransferStatus.FAILED -> "Failed: ${transfer.errorMessage ?: "Error"}"
                        TransferStatus.CANCELLED -> "Cancelled"
                        TransferStatus.TRANSFERRING -> "AES-256 Encrypted Transfer"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (transfer.status) {
                        TransferStatus.COMPLETED -> Color(0xFF10B981)
                        TransferStatus.FAILED -> Color(0xFFEF4444)
                        TransferStatus.CANCELLED -> Color(0xFFEF4444)
                        TransferStatus.TRANSFERRING -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (transfer.status == TransferStatus.TRANSFERRING && transfer.speedBytesPerSec > 0) {
                    Text(
                        "${transfer.speedBytesPerSec / (1024 * 1024)} MB/s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun queryFileMetadata(context: android.content.Context, uri: Uri): Pair<String, Long> {
    var name = "file_${System.currentTimeMillis()}"
    var size = 0L

    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (nameIndex != -1) {
                    val n = cursor.getString(nameIndex)
                    if (!n.isNullOrEmpty()) name = n
                }
                if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
    } catch (_: Exception) {}

    if (size <= 0L) {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                size = pfd.statSize
            }
        } catch (_: Exception) {}
    }

    if (size <= 0L) {
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                size = stream.available().toLong()
            }
        } catch (_: Exception) {}
    }

    return Pair(name, if (size > 0L) size else 1024L)
}
