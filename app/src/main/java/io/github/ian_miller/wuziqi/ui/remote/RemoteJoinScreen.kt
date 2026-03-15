package io.github.ian_miller.wuziqi.ui.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.github.ian_miller.wuziqi.ui.theme.AppStrings
import io.github.ian_miller.wuziqi.ui.theme.LocalStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

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
    val s = LocalStrings.current
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
                title = { Text(s.joiningRoom, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.cancelJoining(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFE0C39E),
                    titleContentColor = Color(0xFF3E2723),
                    navigationIconContentColor = Color(0xFF3E2723),
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
                    RemoteWaitingContent(message = s.waitingHost)

                is RemotePhase.Error -> {
                    val msg = resolveErrorMessage(phase, s)
                    RemoteErrorContent(
                        message = msg,
                        onRetry = viewModel::startJoining,
                    )
                }

                else -> RemoteWaitingContent(message = s.preparing)
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
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isScanning by remember { mutableStateOf(false) }

    // 显示错误提示 Snackbar
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            errorMessage = null
        }
    }

    // 从相册选图并用 ML Kit 解码 QR
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        isScanning = true
        scope.launch {
            val (code, error) = decodeQrFromGallery(context, uri, s)
            isScanning = false
            if (code != null) {
                onJoinCode(code)
            } else {
                errorMessage = error
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 标题卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFF8E1).copy(alpha = 0.95f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = s.inputInviteCode,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF3E2723),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = s.inputInviteCodeHint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF5D4037),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 输入框卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFF8E1).copy(alpha = 0.95f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // 邀请码输入框（移除 trailingIcon 的扫码按钮，避免重复）
                    OutlinedTextField(
                        value = inputCode,
                        onValueChange = onCodeChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(s.inviteCodeLabel) },
                        placeholder = { Text(s.pasteInviteCode) },
                        singleLine = false,
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF5D4037),
                            unfocusedBorderColor = Color(0xFF8D6E63),
                            focusedLabelColor = Color(0xFF5D4037),
                            cursorColor = Color(0xFF5D4037),
                        ),
                    )

                    Spacer(modifier = Modifier.height(16.dp))

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
                                .height(48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF5D4037)),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF5D4037)),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(s.scanQr, maxLines = 1)
                        }

                        // 相册按钮
                        OutlinedButton(
                            onClick = { galleryLauncher.launch("image/*") },
                            enabled = !isScanning,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF5D4037)),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF5D4037)),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            if (isScanning) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(s.gallery, maxLines = 1)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 加入按钮（独占一行，更突出）
                    Button(
                        onClick = onJoin,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        enabled = inputCode.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF5D4037),
                            contentColor = Color(0xFFFFE082),
                        ),
                        shape = RoundedCornerShape(12.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(s.joinGame, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    }
                }
            }
        }

        // Snackbar 位于底部
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }
}

// ── 公共：ErrorType → 本地化字符串 ─────────────────────────────────────────────

/** 将 [RemotePhase.Error] 的 type 转为本地化消息。同一逻辑供 JoinScreen / LobbyScreen 复用。 */
internal fun resolveErrorMessage(error: RemotePhase.Error, s: AppStrings): String =
    when (error.type) {
        RemoteErrorType.INVALID_LAN_CODE    -> s.inviteCodeInvalidLan
        RemoteErrorType.INVALID_NOSTR_CODE  -> s.connectionFailed
        RemoteErrorType.CONNECTION_TIMEOUT   -> s.connectionTimeoutMsg
        RemoteErrorType.NO_NETWORK           -> s.networkUnavailableMsg
        RemoteErrorType.RELAY_UNAVAILABLE    -> s.relayUnavailableMsg
        RemoteErrorType.VERSION_MISMATCH     -> s.versionMismatchMsg
        RemoteErrorType.GENERIC              -> error.message.ifBlank { s.connectionFailed }
    }

// ── 相册 QR 解码（后台线程） ──────────────────────────────────────────────────

/**
 * 在 IO 线程加载位图，用 ML Kit 解码 QR。
 *
 * 比直接在主线程调用更可靠：
 * - 强制 ARGB_8888 配置，避免 hardware bitmap 导致 ML Kit 无法读取像素
 * - 对超大图片做 inSampleSize 降采样，降低内存压力
 * - 返回详细的失败信息帮助用户排查
 *
 * @return Pair(qrContent, errorMessage)；成功时 first 非空，失败时 second 非空
 */
private suspend fun decodeQrFromGallery(
    context: android.content.Context,
    uri: Uri,
    s: AppStrings,
): Pair<String?, String?> {
    // ── 1. 读取图片尺寸（不加载像素）──────────────────────────────────────────
    val (rawW, rawH) = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        bounds.outWidth to bounds.outHeight
    }
    if (rawW <= 0 || rawH <= 0) {
        return Pair(null, s.qrImageLoadFailed)
    }

    // ── 2. 计算降采样倍率（限制最大边 ≤ 1920） ───────────────────────────────
    var sampleSize = 1
    val maxDim = maxOf(rawW, rawH)
    while (maxDim / sampleSize > 1920) sampleSize *= 2

    // ── 3. 加载位图（IO 线程，ARGB_8888 强制软件位图）──────────────────────
    val bitmap: Bitmap = withContext(Dispatchers.IO) {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize
        }
        try {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (_: Exception) { null }
    } ?: return Pair(null, s.qrImageLoadFailed)

    // ── 4. ML Kit 扫描 ──────────────────────────────────────────────────────
    val image = InputImage.fromBitmap(bitmap, 0)
    val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )
    val code = suspendCancellableCoroutine { cont ->
        scanner.process(image)
            .addOnSuccessListener { barcodes -> cont.resume(barcodes.firstOrNull()?.rawValue) }
            .addOnFailureListener { cont.resume(null) }
    }
    bitmap.recycle()

    if (code != null) return Pair(code, null)

    // ── 5. 未检测到：给出详细提示 ───────────────────────────────────────────
    return Pair(
        null,
        "${s.qrNotFound}\n${s.qrNotFoundDetail}\n(${rawW}×${rawH})",
    )
}
