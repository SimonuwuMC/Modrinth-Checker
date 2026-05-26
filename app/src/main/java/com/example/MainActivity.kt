package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import kotlinx.coroutines.launch
import com.example.ui.FileDownloader
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import android.net.Uri
import android.provider.DocumentsContract
import android.database.Cursor
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.ManagedActivityResultLauncher
import coil.compose.SubcomposeAsyncImage
import com.example.data.ProjectEntity
import com.example.data.UpdateNotificationEntity
import com.example.data.VersionFile
import com.example.data.VersionResponse
import com.example.data.SearchHit
import kotlinx.coroutines.delay
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
    var showDownloadDialogFor by remember { mutableStateOf<ProjectEntity?>(null) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val contentResolver = context.contentResolver
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)
                
                val name = getFolderDisplayName(context, uri)
                viewModel.saveFolder(uri.toString(), name)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error securing folder permission", e)
            }
        }
    }
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
                    items(uiState.projects, key = { project -> project.id }) { project ->
                        ProjectItemCard(
                            project = project,
                            onRefresh = { viewModel.refreshAll() },
                            onDelete = { viewModel.deleteProject(project.slug) },
                            onDownload = { showDownloadDialogFor = project }
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

    // Dialog for searching / adding / downloading a custom project
    if (showAddDialog) {
        ModrinthSearchDialog(
            onDismiss = { showAddDialog = false },
            onAddProject = { slug ->
                viewModel.addProject(slug)
            },
            onDownloadProject = { tempProject ->
                showDownloadDialogFor = tempProject
            },
            trackedProjects = uiState.projects,
            viewModel = viewModel
        )
    }

    // Dialog for viewing full version release changelog and particulars
    showChangelogDialogFor?.let { notification ->
        VersionChangelogDialog(
            notification = notification,
            onDismiss = { showChangelogDialogFor = null }
        )
    }

    // Dialog for downloading files locally to a selected directory
    showDownloadDialogFor?.let { project ->
        DownloadFileDialog(
            project = project,
            viewModel = viewModel,
            folderPickerLauncher = folderPickerLauncher,
            onDismiss = { showDownloadDialogFor = null }
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
        colors = CardDefaults.cardColors(containerColor = VibrantNeutralCard),
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
                            tint = Color.Black,
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
                        cursorColor = VibrantBlueText,
                        focusedTextColor = VibrantTextPrimary,
                        unfocusedTextColor = VibrantTextPrimary,
                        unfocusedLabelColor = VibrantTextSecondary,
                        unfocusedBorderColor = VibrantGrayBorder
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
                    Text("Guardar", color = Color.Black)
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
            containerColor = VibrantNeutralCard,
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
    onDownload: () -> Unit
) {
    val isCoreProject = project.slug.contains("simonuwu")
    val cardBg = if (isCoreProject) VibrantBlueContainer else VibrantNeutralCard
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
                    // Launcher local downloads trigger
                    TextButton(
                        onClick = onDownload,
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (isCoreProject) VibrantBlueText else VibrantBlueContainer
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("download_button_${project.slug}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = if (isCoreProject) Color.White else VibrantBlueText,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Descargar",
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
        colors = CardDefaults.cardColors(containerColor = VibrantNeutralCard),
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
        colors = CardDefaults.cardColors(containerColor = VibrantNeutralCard),
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
        colors = CardDefaults.cardColors(containerColor = VibrantNeutralCard),
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
fun ModrinthSearchDialog(
    onDismiss: () -> Unit,
    onAddProject: (String) -> Unit,
    onDownloadProject: (ProjectEntity) -> Unit,
    trackedProjects: List<ProjectEntity>,
    viewModel: ModrinthViewModel
) {
    var searchQuery by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(500)
        isSearching = true
        results = viewModel.searchProjects(searchQuery)
        isSearching = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = VibrantNeutralCard),
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, VibrantGrayBorder),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(4.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Buscador Modrinth",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = VibrantBlueText
                )
                Text(
                    text = "Busca, agrega o descarga cualquier mod de Modrinth directamente sin agregarlo.",
                    fontSize = 11.sp,
                    color = VibrantTextSecondary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Escribe para buscar... (ej. sodium, iris)", fontSize = 12.sp, color = VibrantTextSecondary) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = VibrantTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Limpiar",
                                    tint = VibrantTextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VibrantBlueText,
                        unfocusedBorderColor = VibrantGrayBorder,
                        focusedTextColor = VibrantTextPrimary,
                        unfocusedTextColor = VibrantTextPrimary,
                        focusedContainerColor = VibrantBg,
                        unfocusedContainerColor = VibrantBg,
                        focusedLabelColor = VibrantBlueText,
                        unfocusedLabelColor = VibrantTextSecondary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("modrinth_search_textfield"),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (isSearching) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = VibrantBlueText)
                    }
                } else if (results.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searchQuery.isBlank()) "Ingresa un término para empezar a buscar." else "No se encontraron resultados para \"$searchQuery\"",
                            fontSize = 12.sp,
                            color = VibrantTextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        itemsIndexed(results, key = { index, hit -> "${hit.projectId}_$index" }) { index, hit ->
                            val isAdded = trackedProjects.any { it.slug == hit.slug || it.id == hit.projectId }
                            SearchHitItem(
                                hit = hit,
                                isAdded = isAdded,
                                onAdd = {
                                    onAddProject(hit.slug)
                                },
                                onDownload = {
                                    val tempProject = ProjectEntity(
                                        id = hit.projectId,
                                        slug = hit.slug,
                                        title = hit.title,
                                        description = hit.description ?: "",
                                        iconUrl = hit.iconUrl,
                                        downloads = hit.downloads ?: 0
                                    )
                                    onDownloadProject(tempProject)
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cerrar", color = VibrantBlueText, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun SearchHitItem(
    hit: SearchHit,
    isAdded: Boolean,
    onAdd: () -> Unit,
    onDownload: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = VibrantBg),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, VibrantGrayBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProjectLogo(
                    iconUrl = hit.iconUrl,
                    projectSlug = hit.slug,
                    modifier = Modifier.size(40.dp)
                )
                
                Spacer(modifier = Modifier.width(10.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = hit.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = VibrantTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        
                        val typeBadge = hit.projectType ?: "mod"
                        Surface(
                            color = VibrantBlueText.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = typeBadge.uppercase(),
                                color = VibrantBlueText,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    
                    Text(
                        text = "por ${hit.author ?: "Desconocido"} • ${formatQuantity(hit.downloads ?: 0)} descargas",
                        fontSize = 10.sp,
                        color = VibrantTextSecondary
                    )
                }
            }
            
            if (!hit.description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = hit.description,
                    fontSize = 11.sp,
                    color = VibrantTextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            Spacer(modifier = Modifier.height(10.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDownload,
                    modifier = Modifier
                        .size(32.dp)
                        .background(VibrantBlueText.copy(alpha = 0.12f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Descargar sin agregar",
                        tint = VibrantBlueText,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = "Descargar",
                    fontSize = 11.sp,
                    color = VibrantBlueText,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { onDownload() }
                        .padding(start = 6.dp, end = 16.dp)
                )
                
                Button(
                    onClick = onAdd,
                    enabled = !isAdded,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isAdded) VibrantGrayBorder else VibrantBlueText,
                        contentColor = if (isAdded) VibrantTextSecondary else Color.Black
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(
                        text = if (isAdded) "Agregado" else "Agregar",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
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
            colors = CardDefaults.cardColors(containerColor = VibrantNeutralCard),
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
                    colors = CardDefaults.cardColors(containerColor = VibrantBg),
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
                                        "release" -> Color(0xFF10B981)
                                        "beta" -> Color(0xFFF59E0B)
                                        else -> Color(0xFFEF4444)
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
                        .background(VibrantBg)
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
                    Text("De acuerdo", color = Color.Black)
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

// Helper to extract a friendly visual display name for the user-selected SAF Uri directory
fun getFolderDisplayName(context: Context, uri: Uri): String {
    var displayName: String? = null
    try {
        if (uri.scheme == "content") {
            val documentId = if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.getDocumentId(uri)
            } else {
                DocumentsContract.getTreeDocumentId(uri)
            }
            val documentUri = if (DocumentsContract.isDocumentUri(context, uri)) {
                uri
            } else {
                DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
            }
            context.contentResolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (index != -1) {
                        displayName = cursor.getString(index)
                    }
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("MainActivity", "Error querying folder display name", e)
    }
    if (displayName.isNullOrBlank()) {
        displayName = uri.lastPathSegment ?: "Carpeta elegida"
    }
    return displayName
}

@Composable
fun DownloadFileDialog(
    project: ProjectEntity,
    viewModel: ModrinthViewModel,
    folderPickerLauncher: ManagedActivityResultLauncher<Uri?, Uri?>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var allVersions by remember { mutableStateOf<List<VersionResponse>?>(null) }
    var selectedVersion by remember { mutableStateOf<VersionResponse?>(null) }
    var isLoadingVersions by remember { mutableStateOf(true) }
    
    var filesList by remember { mutableStateOf<List<VersionFile>?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    var isCustomMode by remember { mutableStateOf(false) }
    var customUrl by remember { mutableStateOf("") }
    var customFilename by remember { mutableStateOf("") }
    
    var selectedFileIndex by remember { mutableStateOf(0) }
    
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0) }
    var statusText by remember { mutableStateOf<String?>(null) }
    
    var showVersionSelectorDialog by remember { mutableStateOf(false) }

    val savedFolderUri = viewModel.getSavedFolderUri()
    val savedFolderName = viewModel.getSavedFolderName() ?: "Descargas (Carpeta estándar de Android)"

    // Load available versions from Modrinth API in the background
    LaunchedEffect(project.slug) {
        isLoadingVersions = true
        errorMessage = null
        try {
            val result = viewModel.fetchAllVersions(project.slug)
            allVersions = result
            val latest = result.firstOrNull()
            selectedVersion = latest
            filesList = latest?.files ?: emptyList()
            if (filesList.isNullOrEmpty()) {
                isCustomMode = true
                customFilename = "${project.slug}.jar"
                customUrl = "https://api.modrinth.com/v2/project/${project.slug}/version"
            } else {
                selectedFileIndex = 0
                isCustomMode = false
            }
        } catch (e: Exception) {
            errorMessage = "No se pudieron obtener las versiones: ${e.message}"
            isCustomMode = true
            customFilename = "${project.slug}.jar"
            customUrl = ""
        } finally {
            isLoadingVersions = false
        }
    }

    Dialog(onDismissRequest = { if (!isDownloading) onDismiss() }) {
        Card(
            colors = CardDefaults.cardColors(containerColor = VibrantNeutralCard),
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, VibrantGrayBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "Descargador de Archivos",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = VibrantBlueText
                )
                Text(
                    text = project.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = VibrantTextPrimary
                )
                
                Spacer(modifier = Modifier.height(14.dp))
                
                // --- VERSION SELECTION DISPLAY ---
                Text(
                    text = "VERSIÓN DEL PROYECTO",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = VibrantTextSecondary,
                        letterSpacing = 1.sp
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                
                Surface(
                    color = VibrantBg,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, VibrantGrayBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isLoadingVersions && !allVersions.isNullOrEmpty()) {
                            showVersionSelectorDialog = true
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            if (isLoadingVersions) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        color = VibrantBlueText,
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 1.5.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Buscando versiones...", fontSize = 11.sp, color = VibrantTextSecondary)
                                }
                            } else if (selectedVersion != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = selectedVersion?.name ?: "",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = VibrantTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    val badgeColor = when (selectedVersion?.versionType?.lowercase()) {
                                        "release" -> Color(0xFF10B981)
                                        "beta" -> Color(0xFFF59E0B)
                                        "alpha" -> Color(0xFFEF4444)
                                        else -> VibrantTextSecondary
                                    }
                                    Surface(
                                        color = badgeColor.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = (selectedVersion?.versionType ?: "release").uppercase(),
                                            color = badgeColor,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Versión: v${selectedVersion?.versionNumber ?: ""} • MC: ${selectedVersion?.gameVersions?.take(2)?.joinToString(", ") ?: ""}",
                                    fontSize = 10.sp,
                                    color = VibrantTextSecondary
                                )
                            } else {
                                Text("No se encontraron versiones de Modrinth", fontSize = 12.sp, color = VibrantCoralText)
                            }
                        }
                        
                        if (!isLoadingVersions && !allVersions.isNullOrEmpty()) {
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Cambiar versión",
                                tint = VibrantBlueText,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(14.dp))
                
                Text(
                    text = "CONTENEDOR (CARPETA DESTINO)",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = VibrantTextSecondary,
                        letterSpacing = 1.sp
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                
                Surface(
                    color = VibrantBg,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, VibrantGrayBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Carpeta seleccionada:",
                                    fontSize = 10.sp,
                                    color = VibrantTextSecondary
                                )
                                Text(
                                    text = savedFolderName,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = VibrantTextPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            
                            IconButton(
                                onClick = { folderPickerLauncher.launch(null) },
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(VibrantBlueContainer, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Elegir carpeta",
                                    tint = VibrantBlueText,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        
                        if (savedFolderUri != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            TextButton(
                                onClick = { viewModel.clearSavedFolder() },
                                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFC2410C)),
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Restablecer a Descargas del sistema", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(14.dp))
                
                Text(
                    text = "SELECCIÓN DE ARCHIVOS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = VibrantTextSecondary,
                        letterSpacing = 1.sp
                    )
                )
                Spacer(modifier = Modifier.height(6.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { isCustomMode = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!isCustomMode) VibrantBlueContainer else VibrantBg,
                            contentColor = if (!isCustomMode) VibrantBlueText else VibrantTextSecondary
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        Text("Modrinth Releases", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { isCustomMode = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isCustomMode) VibrantBlueContainer else VibrantBg,
                            contentColor = if (isCustomMode) VibrantBlueText else VibrantTextSecondary
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        Text("Personalizado", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                if (!isCustomMode) {
                    if (isLoadingVersions) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = VibrantBlueText, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("Buscando archivos...", fontSize = 11.sp, color = VibrantTextSecondary)
                            }
                        }
                    } else if (!filesList.isNullOrEmpty()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            filesList?.forEachIndexed { index, file ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { selectedFileIndex = index }
                                        .background(if (selectedFileIndex == index) VibrantBlueContainer.copy(alpha = 0.5f) else Color.Transparent)
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = selectedFileIndex == index,
                                        onClick = { selectedFileIndex = index },
                                        colors = RadioButtonDefaults.colors(selectedColor = VibrantBlueText)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Column {
                                        Text(
                                            text = file.filename,
                                            fontSize = 12.sp,
                                            fontWeight = if (selectedFileIndex == index) FontWeight.Bold else FontWeight.Normal,
                                            color = VibrantTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val fileSizeInMb = (file.size ?: 0).toFloat() / (1024 * 1024)
                                        val suffix = if (file.primary == true) " • Primario" else ""
                                        Text(
                                            text = "${String.format("%.2f", fileSizeInMb)} MB$suffix",
                                            fontSize = 10.sp,
                                            color = VibrantTextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "No se encontraron descargas oficiales directas disponibles para esta versión/proyecto. Ingresa una URL en la pestaña Personalizado.",
                            fontSize = 11.sp,
                            color = Color(0xFFC2410C),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = customFilename,
                            onValueChange = { customFilename = it },
                            label = { Text("Nombre del Archivo (ej. sodium.jar o config.mrpack)", fontSize = 11.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = VibrantBlueText,
                                unfocusedBorderColor = VibrantGrayBorder,
                                focusedLabelColor = VibrantBlueText,
                                unfocusedLabelColor = VibrantTextSecondary,
                                focusedTextColor = VibrantTextPrimary,
                                unfocusedTextColor = VibrantTextPrimary
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = customUrl,
                            onValueChange = { customUrl = it },
                            label = { Text("URL de Descarga Directa", fontSize = 11.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = VibrantBlueText,
                                unfocusedBorderColor = VibrantGrayBorder,
                                focusedLabelColor = VibrantBlueText,
                                unfocusedLabelColor = VibrantTextSecondary,
                                focusedTextColor = VibrantTextPrimary,
                                unfocusedTextColor = VibrantTextPrimary
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                
                if (statusText != null) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = statusText ?: "",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (statusText?.contains("Error") == true) Color(0xFFC2410C) else VibrantBlueText,
                                modifier = Modifier.weight(1f)
                            )
                            if (isDownloading) {
                                Text(
                                    text = "$downloadProgress%",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = VibrantBlueText
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = downloadProgress.toFloat() / 100f,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = VibrantBlueText,
                            trackColor = VibrantBlueContainer
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isDownloading
                    ) {
                        Text("Cancelar", color = VibrantTextSecondary, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                try {
                                    isDownloading = true
                                    downloadProgress = 0
                                    statusText = "Conectando al servidor..."

                                    val urlToDownload = if (isCustomMode) customUrl else {
                                        filesList?.getOrNull(selectedFileIndex)?.url ?: ""
                                    }
                                    val nameToDownload = if (isCustomMode) customFilename else {
                                        filesList?.getOrNull(selectedFileIndex)?.filename ?: "${project.slug}.jar"
                                    }

                                    if (urlToDownload.isBlank() || nameToDownload.isBlank()) {
                                        statusText = "Error: Nombre de archivo o URL vacíos"
                                        isDownloading = false
                                        return@launch
                                    }

                                    statusText = "Descargando: $nameToDownload"
                                    
                                    val result = FileDownloader.download(
                                        context = context,
                                        url = urlToDownload,
                                        filename = nameToDownload,
                                        folderUriString = savedFolderUri,
                                        onProgress = { p -> downloadProgress = p }
                                    )

                                    if (result.isSuccess) {
                                        downloadProgress = 100
                                        statusText = "¡Guardado con éxito!"
                                        Toast.makeText(context, "Descargado con éxito en $savedFolderName", Toast.LENGTH_SHORT).show()
                                    } else {
                                        statusText = "Error: " + (result.exceptionOrNull()?.message ?: "Descarga fallida")
                                    }
                                } catch (ex: Exception) {
                                    statusText = "Error: ${ex.message}"
                                } finally {
                                    isDownloading = false
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = VibrantBlueText),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isDownloading && (isCustomMode || (!filesList.isNullOrEmpty()))
                    ) {
                        Text("Descargar", color = Color.Black)
                    }
                }
            }
        }
    }

    // --- SECONDARY DIALOG FOR PICKING ANY VERSION ---
    if (showVersionSelectorDialog && !allVersions.isNullOrEmpty()) {
        VersionSelectorDialog(
            versions = allVersions ?: emptyList(),
            selectedVersion = selectedVersion,
            onVersionSelected = { version ->
                selectedVersion = version
                filesList = version.files ?: emptyList()
                selectedFileIndex = 0
                if (filesList.isNullOrEmpty()) {
                    isCustomMode = true
                    customFilename = "${project.slug}.jar"
                    customUrl = "https://api.modrinth.com/v2/project/${project.slug}/version"
                } else {
                    isCustomMode = false
                }
            },
            onDismiss = { showVersionSelectorDialog = false }
        )
    }
}

@Composable
fun VersionSelectorDialog(
    versions: List<VersionResponse>,
    selectedVersion: VersionResponse?,
    onVersionSelected: (VersionResponse) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    
    val filteredVersions = remember(versions, searchQuery) {
        if (searchQuery.isBlank()) {
            versions
        } else {
            versions.filter { version ->
                version.name.contains(searchQuery, ignoreCase = true) ||
                version.versionNumber.contains(searchQuery, ignoreCase = true) ||
                (version.gameVersions?.any { it.contains(searchQuery, ignoreCase = true) } == true) ||
                (version.loaders?.any { it.contains(searchQuery, ignoreCase = true) } == true)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = VibrantNeutralCard),
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, VibrantGrayBorder),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Seleccionar Versión",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = VibrantBlueText
                )
                Text(
                    text = "Busca y elige cualquier versión del proyecto",
                    fontSize = 12.sp,
                    color = VibrantTextSecondary
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Buscar por versión, Minecraft, Loader...", fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = VibrantTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Limpiar",
                                    tint = VibrantTextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VibrantBlueText,
                        unfocusedBorderColor = VibrantGrayBorder,
                        focusedTextColor = VibrantTextPrimary,
                        unfocusedTextColor = VibrantTextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                if (filteredVersions.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No se encontraron versiones coincidentes.",
                            fontSize = 13.sp,
                            color = VibrantTextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(filteredVersions) { version ->
                            val isSelected = version.id == selectedVersion?.id
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onVersionSelected(version)
                                        onDismiss()
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) VibrantBlueContainer else VibrantBg
                                ),
                                shape = RoundedCornerShape(14.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) VibrantBlueText.copy(alpha = 0.4f) else Color.Transparent
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = version.name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = VibrantTextPrimary,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        
                                        val badgeColor = when ((version.versionType ?: "release").lowercase()) {
                                            "release" -> Color(0xFF10B981)
                                            "beta" -> Color(0xFFF59E0B)
                                            "alpha" -> Color(0xFFEF4444)
                                            else -> VibrantTextSecondary
                                        }
                                        Surface(
                                            color = badgeColor.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = (version.versionType ?: "release").uppercase(),
                                                color = badgeColor,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.height(4.dp))
                                    
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "v${version.versionNumber}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = VibrantTextSecondary
                                        )
                                        Text(
                                            text = formatPublishDate(version.datePublished),
                                            fontSize = 10.sp,
                                            color = VibrantTextSecondary.copy(alpha = 0.8f)
                                        )
                                    }
                                    
                                    Spacer(modifier = Modifier.height(6.dp))
                                    
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        if (!version.loaders.isNullOrEmpty()) {
                                            Surface(
                                                color = VibrantBlueText.copy(alpha = 0.08f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = version.loaders.joinToString(", ").uppercase(),
                                                    color = VibrantBlueText,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = "• Minecraft " + (version.gameVersions?.joinToString(", ") ?: ""),
                                            color = VibrantTextSecondary,
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cerrar", color = VibrantBlueText, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// Format ISO dateStr (e.g. "2023-05-24T18:02:11.123Z") to "24/05/2023"
fun formatPublishDate(dateStr: String?): String {
    if (dateStr == null) return ""
    return try {
        val date = dateStr.substringBefore('T')
        val parts = date.split('-')
        if (parts.size == 3) {
            "${parts[2]}/${parts[1]}/${parts[0]}"
        } else {
            date
        }
    } catch (e: Exception) {
        dateStr
    }
}
