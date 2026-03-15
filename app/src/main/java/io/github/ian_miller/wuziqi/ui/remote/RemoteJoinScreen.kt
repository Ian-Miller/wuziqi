package io.github.ian_miller.wuziqi.ui.remote

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.github.ian_miller.wuziqi.ui.theme.LocalStrings

/**
 * 加入对局页面（路由: remote_join）
 *
 * 导航进入前，调用方已经调用过 viewModel.startJoining()。
 * 支持系统返回键 → 调用 cancelJoining() 并弹出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteJoinScreen(
    onBack: () -> Unit,
    onNavigateToGame: () -> Unit,
    viewModel: RemoteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showScanner by remember { mutableStateOf(false) }

    // 系统返回键：取消加入
    BackHandler {
        viewModel.cancelJoining()
        onBack()
    }

    // 连接成功后自动导航到棋盘
    LaunchedEffect(state.pendingNavToGame) {
        if (state.pendingNavToGame) {
            viewModel.consumeNavToGame()
            onNavigateToGame()
        }
    }

    // QR 扫描界面
    if (showScanner) {
        QrScannerScreen(
            onScanned = { code ->
                viewModel.onJoinCodeChanged(code)
                viewModel.joinRoom()
                showScanner = false
            },
            onBack = { showScanner = false },
        )
        return
    }

    val woodBrush = remember {
        Brush.verticalGradient(colors = listOf(Color(0xFFE0C39E), Color(0xFFA47E5C)))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(LocalStrings.current.joiningRoom, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.cancelJoining(); onBack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF5D4037),
                    titleContentColor = Color(0xFFFFE082),
                    navigationIconContentColor = Color(0xFFFFE082),
                ),
            )
        },
        containerColor = Color.Transparent,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(woodBrush)
                .padding(padding),
        ) {
            when (val phase = state.phase) {
                is RemotePhase.Joining ->
                    RemoteJoiningContent(
                        inputCode = state.joinInputCode,
                        onCodeChanged = viewModel::onJoinCodeChanged,
                        onJoin = viewModel::joinRoom,
                        onScan = { showScanner = true },
                        onJoinCode = { code ->
                            viewModel.onJoinCodeChanged(code)
                            viewModel.joinRoom()
                        },
                    )

                is RemotePhase.WaitingForOpponent ->
                    RemoteWaitingContent(message = "等待房主确认…")

                is RemotePhase.Error ->
                    RemoteErrorContent(
                        message = phase.message,
                        onRetry = viewModel::startJoining,
                    )

                else -> RemoteWaitingContent(message = "准备中…")
            }
        }
    }
}

// ── 输入邀请码内容 ─────────────────────────────────────────────────────────────

@Composable
private fun RemoteJoiningContent(
    inputCode: String,
    onCodeChanged: (String) -> Unit,
    onJoin: () -> Unit,
    onScan: () -> Unit,
    onJoinCode: (String) -> Unit = {},
) {
    val context = LocalContext.current

    // 从相册选图并用 ML Kit 解码 QR
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val image = InputImage.fromFilePath(context, uri)
            val scanner = BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build()
            )
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    barcodes.firstOrNull()?.rawValue?.let { onJoinCode(it) }
                }
        }
    }
    val s = LocalStrings.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "输入邀请码",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF3E2723),
        )
        Text(
            text = "将好友发送的邀请码粘贴在此处，\n或扫描 QR 码直接加入",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF5D4037),
            modifier = Modifier.padding(top = 8.dp, bottom = 28.dp),
        )

        // 邀请码输入框
        OutlinedTextField(
            value = inputCode,
            onValueChange = onCodeChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("邀请码") },
            placeholder = { Text("粘贴邀请码…") },
            singleLine = false,
            maxLines = 4,
            trailingIcon = {
                IconButton(onClick = onScan) {
                    Icon(
                        Icons.Default.QrCodeScanner,
                        contentDescription = "扫描 QR 码",
                        tint = Color(0xFF5D4037),
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF5D4037),
                unfocusedBorderColor = Color(0xFF8D6E63),
                focusedLabelColor = Color(0xFF5D4037),
                cursorColor = Color(0xFF5D4037),
            ),
        )

        Spacer(modifier = Modifier.height(20.dp))

        // 操作按钮行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 扫码按钮
            OutlinedButton(
                onClick = onScan,
                modifier = Modifier
                    .weight(1f)
                    .sizeIn(minHeight = 52.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF5D4037)),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF5D4037)),
                shape = MaterialTheme.shapes.large,
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(s.scanQr, maxLines = 1)
            }

            // 相册按钮
            OutlinedButton(
                onClick = { galleryLauncher.launch("image/*") },
                modifier = Modifier
                    .weight(1f)
                    .sizeIn(minHeight = 52.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF5D4037)),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF5D4037)),
                shape = MaterialTheme.shapes.large,
            ) {
                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(s.gallery, maxLines = 1)
            }

            // 加入按钮
            Button(
                onClick = onJoin,
                modifier = Modifier
                    .weight(2f)
                    .sizeIn(minHeight = 52.dp),
                enabled = inputCode.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF5D4037),
                    contentColor = Color(0xFFFFE082),
                ),
                shape = MaterialTheme.shapes.large,
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(s.joinGame, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
        }
    }
}
