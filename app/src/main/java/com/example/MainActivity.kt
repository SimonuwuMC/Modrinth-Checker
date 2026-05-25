package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.example.data.ProjectEntity
import com.example.data.UpdateNotificationEntity
import com.example.ui.theme.*
import com.example.ui.ModrinthUiState
import com.example.ui.ModrinthViewModel
import com.example.ui.MarkdownRenderer
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.absoluteValue

class MainActivity : ComponentActivity() {
    private val viewModel: ModrinthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                ModrinthAppScreen(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModrinthAppScreen(viewModel: ModrinthViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var showChangelogDialogFor by remember { mutableStateOf<UpdateNotificationEntity?>(null) }
    var notificationPermissionGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        notificationPermissionGranted = isGranted
        if (isGranted) {
            Toast.makeText(context, "¡Notificaciones del sistema activadas!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Las notificaciones están desactivadas. No se enviarán avisos en segundo plano.", Toast.LENGTH_LONG).show()
        }
    }

    // Trigger initial refresh if there are already projects tracked on start
    LaunchedEffect(key1 = uiState.projects.isNotEmpty()) {
        if (uiState.projects.isNotEmpty() && uiState.projects.any { it.lastChecked == 0L }) {
            viewModel.refreshAll()
        }
    }

    // Displays Snackbars or Alerts based on ViewModel updates
    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.dismissSuccessMessage()
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.dismissErrorMessage()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("app_scaffold"),
        containerColor = VibrantBg,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // App Header
            AppHeader(
                username = uiState.username,
                onUpdateUsername = { viewModel.updateUsername(it) },
                onRefreshAll = { viewModel.refreshAll() },
                isRefreshing = uiState.isRefreshing,
                onAddProjectClick = { showAddDialog = true }
            )

            // Permission Warning Banner
            if (!notificationPermissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionWarningBanner {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            // Central feed area containing tabs or single scroll feed
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 24.dp, top = 8.dp)
            ) {
                // Section 1: Watchlist Header
                item {
                    Text(
                        text = "PROYECTOS EN SEGUIMIENTO",
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = VibrantTextSecondary,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        ),
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }

                // Tracked projects flow
                if (uiState.projects.isEmpty()) {
                    item {
                        EmptyTrackedState()
                    }
                } else {
                    items(uiState.projects, key = { it.slug }) { project ->
                        ProjectItemCard(
                            project = project,
                            onRefresh = { viewModel.refreshAll() },
                            onDelete = { viewModel.deleteProject(project.slug) },
                            onSimulate = { viewModel.simulateVersionUpdate(project.slug) }
                        )
                    }
                }

                // Section 2: Releases Notification Logs Header
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "HISTORIAL DE VERSIONES",
                            style = MaterialTheme.typography.titleSmall.copy(
                                color = VibrantTextSecondary,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp
                            )
                        )
                        if (uiState.notifications.isNotEmpty()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { viewModel.markAllAsRead() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Leídos", color = VibrantBlueText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                                TextButton(
                                    onClick = { viewModel.clearHistory() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Vaciar log", color = Color(0xFFC2410C), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                // Releases notification history items
                if (uiState.notifications.isEmpty()) {
                    item {
                        EmptyNotificationsState()
                    }
                } else {
                    items(uiState.notifications, key = { it.id }) { notification ->
                        val correspondingProject = uiState.projects.firstOrNull { it.slug == notification.projectSlug }
                        val iconUrl = correspondingProject?.iconUrl
                        NotificationHistoryCard(
                            notification = notification,
                            iconUrl = iconUrl,
                            onClick = {
                                viewModel.markAsRead(notification.id)
                                showChangelogDialogFor = notification
                            }
                        )
                    }
                }
            }
        }
    }

    // Dialog for adding a custom project
    if (showAddDialog) {
        AddProjectDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { slug ->
                viewModel.addProject(slug)
                showAddDialog = false
            }
        )
    }

    // Dialog for viewing full version release changelog and particulars
    showChangelogDialogFor?.let { notification ->
        VersionChangelogDialog(
            notification = notification,
            onDismiss = { showChangelogDialogFor = null }
        )
    }
}

@Composable
fun AppHeader(
    username: String,
    onUpdateUsername: (String) -> Unit,
    onRefreshAll: () -> Unit,
    isRefreshing: Boolean,
    onAddProjectClick: () -> Unit
) {
    val rotationAngle = remember { Animatable(0f) }
    var showEditNameDialog by remember { mutableStateOf(false) }

    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            rotationAngle.animateTo(
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                )
            )
        } else {
            rotationAngle.snapTo(0f)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, VibrantGrayBorder),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Minecraft-Style Emerald Block Visual logo in canvas
                    Canvas(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(6.dp))
                    ) {
                        drawRect(color = Color(0xFF047857)) // Dark emerald border
                        val blockPixel = size.width / 4
                        for (i in 0 until 4) {
                            for (j in 0 until 4) {
                                if ((i + j) % 2 == 0) {
                                    drawRect(
                                        color = Color(0xFF10B981), // Emerald body
                                        topLeft = androidx.compose.ui.geometry.Offset(i * blockPixel, j * blockPixel),
                                        size = androidx.compose.ui.geometry.Size(blockPixel, blockPixel)
                                    )
                                } else {
                                    drawRect(
                                        color = Color(0xFF34D399), // Lite emerald accent
                                        topLeft = androidx.compose.ui.geometry.Offset(i * blockPixel, j * blockPixel),
                                        size = androidx.compose.ui.geometry.Size(blockPixel, blockPixel)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Simonuwu Modrinth",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = VibrantTextPrimary
                        )
                        Text(
                            text = "Checker & Notificador",
                            fontSize = 12.sp,
                            color = VibrantTextSecondary
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = onRefreshAll,
                        modifier = Modifier
                            .background(VibrantBlueContainer, CircleShape)
                            .size(36.dp)
                            .testTag("refresh_all_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Sincronizar",
                            tint = VibrantBlueText,
                            modifier = Modifier
                                .size(20.dp)
                                .rotate(rotationAngle.value)
                        )
                    }
                    IconButton(
                        onClick = onAddProjectClick,
                        modifier = Modifier
                            .background(VibrantBlueText, CircleShape)
                            .size(36.dp)
                            .testTag("add_custom_project_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Agregar proyecto",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = VibrantGrayBorder, thickness = 1.dp)
            Spacer(modifier = Modifier.height(8.dp))

            // Metadata Row: UTC Clock and Account
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showEditNameDialog = true }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .testTag("edit_username_trigger")
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Editar nombre de usuario",
                        tint = VibrantBlueText,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = username,
                        fontSize = 11.sp,
                        color = VibrantBlueText,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }

    if (showEditNameDialog) {
        var tempName by remember { mutableStateOf(username) }
        AlertDialog(
            onDismissRequest = { showEditNameDialog = false },
            title = {
                Text(
                    text = "Editar Nombre de Usuario",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = VibrantTextPrimary
                )
            },
            text = {
                OutlinedTextField(
                    value = tempName,
                    onValueChange = { tempName = it },
                    label = { Text("Nombre de usuario") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("username_input_field"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VibrantBlueText,
                        focusedLabelColor = VibrantBlueText,
                        cursorColor = VibrantBlueText
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tempName.trim().isNotEmpty()) {
                            onUpdateUsername(tempName.trim())
                            showEditNameDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VibrantBlueText),
                    modifier = Modifier.testTag("save_username_button")
                ) {
                    Text("Guardar", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEditNameDialog = false },
                    modifier = Modifier.testTag("cancel_username_button")
                ) {
                    Text("Cancelar", color = VibrantTextSecondary)
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
fun PermissionWarningBanner(onRequestPermissions: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = VibrantCoralAlert),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFDBA74).copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = VibrantCoralText,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Permiso de Notificación Ausente",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = VibrantCoralText
                    )
                    Text(
                        text = "Activa para recibir alertas instantáneas.",
                        fontSize = 11.sp,
                        color = VibrantCoralText.copy(alpha = 0.8f)
                    )
                }
            }
            Button(
                onClick = onRequestPermissions,
                colors = ButtonDefaults.buttonColors(containerColor = VibrantCoralText),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("Activar", fontSize = 12.sp, color = Color.White)
            }
        }
    }
}

@Composable
fun ProjectItemCard(
    project: ProjectEntity,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onSimulate: () -> Unit
) {
    val isCoreProject = project.slug.contains("simonuwu")
    val cardBg = if (isCoreProject) VibrantBlueContainer else Color.White
    val titleColor = if (isCoreProject) VibrantBlueText else VibrantTextPrimary
    val idColor = if (isCoreProject) VibrantBlueText.copy(alpha = 0.6f) else VibrantTextSecondary.copy(alpha = 0.7f)
    val descColor = if (isCoreProject) VibrantBlueText.copy(alpha = 0.8f) else VibrantTextSecondary
    val dividerColor = if (isCoreProject) VibrantBlueBorder.copy(alpha = 0.4f) else VibrantGrayBorder
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("project_card_${project.slug}"),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        shape = RoundedCornerShape(28.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isCoreProject) VibrantBlueBorder.copy(alpha = 0.4f) else VibrantGrayBorder
        ),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // Top Section: Title & Icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                ProjectLogo(iconUrl = project.iconUrl, projectSlug = project.slug, modifier = Modifier.size(44.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = project.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = titleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "id: ${project.slug}",
                        fontSize = 11.sp,
                        color = idColor,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Delete custom tracked item but lock deletes for target projects (always keep them on top!)
                val isUrgentCore = project.slug == "simonuwu-fabric-project" || project.slug == "simonuwu-fabric-project-for-pojav"
                if (!isUrgentCore) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Dejar de seguir",
                            tint = Color(0xFFC2410C),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = project.description,
                fontSize = 12.sp,
                color = descColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = dividerColor, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(10.dp))

            // Version Information & Control Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "ÚLTIMA VERSIÓN",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isCoreProject) VibrantBlueText else Color(0xFF10B981)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Text(
                            text = project.lastVersionNumber ?: "No detectada",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = titleColor
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        
                        // Badge type
                        val versionType = project.lastVersionType ?: "release"
                        val badgeBg = when (versionType) {
                            "release" -> if (isCoreProject) VibrantBlueText else Color(0xFFD1FAE5)
                            "beta" -> if (isCoreProject) Color(0xFF78350F) else Color(0xFFFEF3C7)
                            else -> if (isCoreProject) Color(0xFF7F1D1D) else Color(0xFFFEE2E2)
                        }
                        val badgeTextClr = when (versionType) {
                            "release" -> if (isCoreProject) Color.White else Color(0xFF065F46)
                            "beta" -> if (isCoreProject) Color(0xFFFBBF24) else Color(0xFF92400E)
                            else -> if (isCoreProject) Color(0xFFFCA5A5) else Color(0xFF991B1B)
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(badgeBg)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = versionType.uppercase(),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = badgeTextClr
                            )
                        }
                    }
                }

                // Call to actions
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Developer Simulation Beaker Trigger
                    TextButton(
                        onClick = onSimulate,
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (isCoreProject) VibrantBlueText else VibrantBlueContainer
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = if (isCoreProject) Color.White else VibrantBlueText,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Simular v+",
                            color = if (isCoreProject) Color.White else VibrantBlueText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            
            // Downloads and verification time footer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        tint = idColor,
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${formatQuantity(project.downloads)} descargas",
                        fontSize = 11.sp,
                        color = idColor
                    )
                }

                Text(
                    text = "Chequeado: ${formatRelativeTime(project.lastChecked)}",
                    fontSize = 11.sp,
                    color = idColor
                )
            }
        }
    }
}

@Composable
fun NotificationHistoryCard(
    notification: UpdateNotificationEntity,
    iconUrl: String?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("notification_card_${notification.id}"),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, VibrantGrayBorder),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Little indicator dot for unread status
            if (!notification.isRead) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(VibrantBlueText, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(Color.Transparent, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            // Project mini icons
            ProjectLogo(iconUrl = iconUrl, projectSlug = notification.projectSlug, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = notification.projectTitle,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = VibrantTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatRelativeTime(notification.notifiedAt),
                        fontSize = 10.sp,
                        color = VibrantTextSecondary
                    )
                }
                Text(
                    text = "Lanzamiento detectado: ${notification.versionNumber}",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = VibrantBlueText,
                    modifier = Modifier.padding(top = 1.dp)
                )
                Text(
                    text = "Soporta Minecraft: ${notification.gameVersions.takeIf { it.isNotEmpty() } ?: "1.x"}",
                    fontSize = 11.sp,
                    color = VibrantTextSecondary,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Leer changelog",
                tint = VibrantBlueText,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun ProjectLogo(iconUrl: String?, projectSlug: String, modifier: Modifier = Modifier) {
    if (!iconUrl.isNullOrEmpty()) {
        val painter = coil.compose.rememberAsyncImagePainter(model = iconUrl)
        val state = painter.state
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.Image(
                painter = painter,
                contentDescription = "Logo de $projectSlug",
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
            if (state is coil.compose.AsyncImagePainter.State.Loading || state is coil.compose.AsyncImagePainter.State.Error) {
                MinecraftAvatar(projectSlug = projectSlug, modifier = Modifier.fillMaxSize())
            }
        }
    } else {
        MinecraftAvatar(projectSlug = projectSlug, modifier = modifier)
    }
}

@Composable
fun MinecraftAvatar(projectSlug: String, modifier: Modifier = Modifier) {
    val hash = projectSlug.hashCode()
    val random = remember(projectSlug) { Random(hash.toLong()) }
    
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(VibrantNeutralCard)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val pixelGrid = 8
            val pWidth = size.width / pixelGrid
            val pHeight = size.height / pixelGrid
            
            // Procedural grass/dirt visual background or monster faces
            val baseHue = random.nextFloat() * 360f
            val baseSaturation = 0.5f + (random.nextFloat() * 0.4f)
            val baseValue = 0.4f + (random.nextFloat() * 0.4f)
            
            for (x in 0 until pixelGrid) {
                for (y in 0 until pixelGrid) {
                    // Create symmetric face-like pattern or random beautiful grid block
                    val drawX = if (x >= 4) pixelGrid - 1 - x else x
                    val pixelSalt = (drawX * 3 + y * 7).hashCode().absoluteValue % 100
                    
                    val color = if (pixelSalt % 3 == 0) {
                        Color.hsv(
                            (baseHue + (pixelSalt % 30) - 15).coerceIn(0f, 360f),
                            (baseSaturation + 0.1f).coerceIn(0f, 1f),
                            (baseValue - 0.1f).coerceIn(0f, 1f)
                        )
                    } else if (pixelSalt % 3 == 1) {
                        Color.hsv(
                            baseHue,
                            baseSaturation,
                            baseValue
                        )
                    } else {
                        // Shadow/border accents
                        Color.hsv(
                            baseHue,
                            (baseSaturation - 0.2f).coerceIn(0f, 1f),
                            (baseValue + 0.2f).coerceIn(0f, 1f)
                        )
                    }

                    drawRect(
                        color = color,
                        topLeft = androidx.compose.ui.geometry.Offset(x * pWidth, y * pHeight),
                        size = androidx.compose.ui.geometry.Size(pWidth, pHeight)
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyTrackedState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, VibrantGrayBorder),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Build,
                contentDescription = null,
                tint = VibrantTextSecondary,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "No se están monitoreando proyectos.",
                fontSize = 13.sp,
                color = VibrantTextPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Toca el botón + para agregar un slug.",
                fontSize = 11.sp,
                color = VibrantTextSecondary
            )
        }
    }
}

@Composable
fun EmptyNotificationsState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, VibrantGrayBorder),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = null,
                tint = VibrantTextSecondary,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Sin actualizaciones registradas.",
                fontSize = 13.sp,
                color = VibrantTextPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Toca 'Simular v+' en un proyecto para ver cómo funciona.",
                fontSize = 11.sp,
                color = VibrantTextSecondary
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProjectDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit
) {
    var slugText by remember { mutableStateOf("") }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, VibrantGrayBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "Añadir Proyecto Modrinth",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = VibrantTextPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Ingresa el slug unívoco del mod (ej. sodium, iris, o un custom Simonuwu).",
                    fontSize = 12.sp,
                    color = VibrantTextSecondary
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = slugText,
                    onValueChange = { slugText = it },
                    label = { Text("Slug del Proyecto") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VibrantBlueText,
                        unfocusedBorderColor = VibrantGrayBorder,
                        focusedLabelColor = VibrantBlueText,
                        unfocusedLabelColor = VibrantTextSecondary,
                        focusedTextColor = VibrantTextPrimary,
                        unfocusedTextColor = VibrantTextPrimary,
                        focusedContainerColor = VibrantBg,
                        unfocusedContainerColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_project_textfield"),
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(20.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancelar", color = VibrantTextSecondary, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onAdd(slugText) },
                        colors = ButtonDefaults.buttonColors(containerColor = VibrantBlueText),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Agregar", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun VersionChangelogDialog(
    notification: UpdateNotificationEntity,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, VibrantGrayBorder),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header with close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = notification.projectTitle,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = VibrantBlueText
                        )
                        Text(
                            text = notification.versionName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = VibrantTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = VibrantTextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = VibrantGrayBorder, thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(12.dp))

                // Information Board
                Card(
                    colors = CardDefaults.cardColors(containerColor = VibrantNeutralCard),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Lanzamiento", fontSize = 10.sp, color = VibrantTextSecondary, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = notification.versionNumber,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = VibrantTextPrimary
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Tipo", fontSize = 10.sp, color = VibrantTextSecondary, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = notification.releaseType.uppercase(),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when (notification.releaseType) {
                                        "release" -> Color(0xFF047857)
                                        "beta" -> Color(0xFFB45309)
                                        else -> Color(0xFFB91C1C)
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Loaders", fontSize = 10.sp, color = VibrantTextSecondary, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = notification.loaders.uppercase(),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = VibrantTextPrimary
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Minecraft", fontSize = 10.sp, color = VibrantTextSecondary, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = notification.gameVersions,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = VibrantTextPrimary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "CAMBIOS Y NOTAS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = VibrantTextSecondary,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(6.dp))

                // Scrollable Changelog box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(VibrantNeutralCard)
                        .padding(12.dp)
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            val changelogText = notification.changelog
                            if (changelogText.isNullOrEmpty()) {
                                Text(
                                    text = "El desarrollador no especificó un changelog para este lanzamiento.",
                                    fontSize = 12.sp,
                                    color = VibrantTextSecondary
                                )
                            } else {
                                MarkdownRenderer(
                                    markdown = changelogText,
                                    primaryColor = VibrantBlueText
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = VibrantBlueText),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("De acuerdo", color = Color.White)
                }
            }
        }
    }
}

// Format number (e.g. 104523 to "104K")
fun formatQuantity(num: Int): String {
    return when {
        num >= 1_000_000 -> String.format("%.1fM", num.toFloat() / 1_000_000)
        num >= 1_000 -> String.format("%.1fK", num.toFloat() / 1_000)
        else -> num.toString()
    }
}

// Time helper
fun formatRelativeTime(timestamp: Long): String {
    if (timestamp == 0L) return "Nunca"
    val diff = System.currentTimeMillis() - timestamp
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    return when {
        seconds < 60 -> "Hace un momento"
        minutes == 1L -> "Hace 1 minuto"
        minutes < 60 -> "Hace $minutes minutos"
        hours == 1L -> "Hace 1 hora"
        hours < 24 -> "Hace $hours horas"
        days == 1L -> "Ayer"
        else -> "Hace $days días"
    }
}
