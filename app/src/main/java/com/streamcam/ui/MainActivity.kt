package com.streamcam.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen // NEW: Official Splash API
import com.streamcam.R
import com.streamcam.ui.StreamViewModel.StreamMode
import com.streamcam.ui.StreamViewModel.StreamState

val FlipCameraAndroidIcon: ImageVector
    get() = ImageVector.Builder(
        name = "FlipCameraAndroid",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(9.0f, 12.0f)
            curveToRelative(0.0f, 1.66f, 1.34f, 3.0f, 3.0f, 3.0f)
            reflectiveCurveToRelative(3.0f, -1.34f, 3.0f, -3.0f)
            reflectiveCurveToRelative(-1.34f, -3.0f, -3.0f, -3.0f)
            reflectiveCurveToRelative(-3.0f, 1.34f, -3.0f, 3.0f)
            close()
            moveTo(8.0f, 10.0f)
            lineTo(8.0f, 8.0f)
            lineTo(5.09f, 8.0f)
            curveTo(6.47f, 5.61f, 9.05f, 4.0f, 12.0f, 4.0f)
            curveToRelative(3.72f, 0.0f, 6.85f, 2.56f, 7.74f, 6.0f)
            horizontalLineToRelative(2.06f)
            curveToRelative(-0.93f, -4.56f, -4.96f, -8.0f, -9.8f, -8.0f)
            curveTo(8.73f, 2.0f, 5.8f, 3.58f, 4.0f, 6.0f)
            lineTo(4.0f, 4.0f)
            lineTo(2.0f, 4.0f)
            verticalLineToRelative(6.0f)
            horizontalLineToRelative(6.0f)
            close()
            moveTo(16.0f, 14.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(2.91f)
            curveToRelative(-1.38f, 2.39f, -3.96f, 4.0f, -6.91f, 4.0f)
            curveToRelative(-3.72f, 0.0f, -6.85f, -2.56f, -7.74f, -6.0f)
            lineTo(2.2f, 14.0f)
            curveToRelative(0.93f, 4.56f, 4.96f, 8.0f, 9.8f, 8.0f)
            curveToRelative(3.27f, 0.0f, 6.2f, -1.58f, 8.0f, -4.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(-6.0f)
            horizontalLineToRelative(-6.0f)
            close()
        }
    }.build()

class MainActivity : ComponentActivity() {

    private val viewModel: StreamViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.CAMERA] != true) {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // CRITICAL: MUST be called before super.onCreate
        installSplashScreen()

        super.onCreate(savedInstanceState)
        checkPermissions()

        setContent {
            StreamCamTheme {
                BroadcastScreen(viewModel)
            }
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }
}

@Composable
fun StreamCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF141317),
            surface = Color(0xFF1C1B1F),
            primary = Color(0xFFD0BCFF),
            onPrimary = Color(0xFF37265E),
            error = Color(0xFF93000A)
        ),
        content = content
    )
}

@Composable
fun BroadcastScreen(viewModel: StreamViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current

    val streamState by viewModel.state.observeAsState(StreamState.IDLE)
    val statusText by viewModel.status.observeAsState("Ready")
    val currentFps by viewModel.fps.observeAsState(0)
    val deviceIp by viewModel.deviceIp.observeAsState("0.0.0.0")
    val rtspUrl by viewModel.rtspUrl.observeAsState("")
    val recentIps by viewModel.recentIps.observeAsState(emptyList())
    val showSwipeHint by viewModel.showSwipeHint.observeAsState(false)

    BroadcastContent(
        streamState = streamState,
        statusText = statusText,
        currentFps = currentFps,
        deviceIp = deviceIp,
        rtspUrl = rtspUrl,
        recentIps = recentIps,
        showSwipeHint = showSwipeHint,
        onDismissHint = { viewModel.dismissSwipeHint() },
        onStartPreview = { previewView ->
            viewModel.startPreview(lifecycleOwner, previewView)
        },
        onStartStreaming = { host, tcpPort, rtspPort, mode, resolution, targetFps, previewView ->
            viewModel.tcpHost = host
            viewModel.tcpPort = tcpPort
            viewModel.rtspPort = rtspPort
            viewModel.streamMode = mode
            viewModel.resolution = resolution
            viewModel.targetFps = targetFps
            viewModel.startStreaming(lifecycleOwner, previewView)
        },
        onFlipCamera = { previewView ->
            viewModel.flipCamera(lifecycleOwner, previewView)
        },
        onStopStreaming = { viewModel.stopStreaming() }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroadcastContent(
    streamState: StreamState,
    statusText: String,
    currentFps: Int,
    deviceIp: String,
    rtspUrl: String,
    recentIps: List<String>,
    showSwipeHint: Boolean,
    onDismissHint: () -> Unit,
    onStartPreview: (PreviewView) -> Unit,
    onStartStreaming: (String, Int, Int, StreamMode, StreamViewModel.Resolution, Int, PreviewView) -> Unit,
    onFlipCamera: (PreviewView) -> Unit,
    onStopStreaming: () -> Unit
) {
    val context = LocalContext.current
    val previewViewState = remember { mutableStateOf<PreviewView?>(null) }
    val isPreview = LocalInspectionMode.current

    var activeMode by remember { mutableStateOf(StreamMode.TCP_JPEG) }

    var targetHost by remember(recentIps) { mutableStateOf(if (recentIps.isNotEmpty()) recentIps[0] else "192.168.1.100") }
    var tcpPortStr by remember { mutableStateOf("5000") }
    var rtspPortStr by remember { mutableStateOf("8554") }

    var resolutionExpanded by remember { mutableStateOf(false) }
    var ipDropdownExpanded by remember { mutableStateOf(false) }
    var selectedResolution by remember { mutableStateOf(StreamViewModel.Resolution.HD_720) }

    var selectedFps by remember { mutableIntStateOf(30) }

    var showAboutScreen by remember { mutableStateOf(false) }

    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
            skipHiddenState = true
        )
    )

    LaunchedEffect(scaffoldState.bottomSheetState.currentValue) {
        if (scaffoldState.bottomSheetState.currentValue == SheetValue.Expanded) {
            onDismissHint()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = 150.dp,
            sheetContainerColor = Color(0xFF1C1B1F),
            sheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            sheetDragHandle = {
                Box(
                    modifier = Modifier
                        .padding(vertical = 16.dp)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF49454F))
                )
            },
            sheetContent = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("BROADCAST DESK", color = Color(0xFFE5E1E7), fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
                        Surface(
                            shape = CircleShape, color = Color(0xFF0E0E11), border = BorderStroke(1.dp, Color(0xFF2B292D))
                        ) {
                            Text("IP: $deviceIp", color = Color(0xFFCAC4D0), fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val isLive = streamState == StreamState.STREAMING || streamState == StreamState.CONNECTING
                    Button(
                        onClick = {
                            val previewView = previewViewState.value
                            if (previewView == null && !isPreview) {
                                Toast.makeText(context, "Camera preview not ready", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (isLive) {
                                onStopStreaming()
                            } else {
                                previewView?.let {
                                    onStartStreaming(targetHost.trim(), tcpPortStr.toIntOrNull() ?: 5000, rtspPortStr.toIntOrNull() ?: 8554, activeMode, selectedResolution, selectedFps, it)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isLive) Color(0xFF93000A) else Color(0xFFD0BCFF),
                            contentColor = if (isLive) Color(0xFFFFDAD6) else Color(0xFF37265E)
                        ),
                        shape = CircleShape
                    ) {
                        Text(if (isLive) "STOP STREAMING" else "START STREAMING", fontSize = 15.sp, fontWeight = FontWeight.Black)
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text("PROTOCOL", color = Color(0xFF948F9A), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start), letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0E0E11))
                            .padding(4.dp)
                    ) {
                        val activeColor = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B292D), contentColor = Color.White)
                        val inactiveColor = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color(0xFF948F9A))

                        Button(
                            onClick = { activeMode = StreamMode.TCP_JPEG },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            shape = CircleShape,
                            colors = if (activeMode == StreamMode.TCP_JPEG) activeColor else inactiveColor
                        ) { Text("TCP / JPEG", fontSize = 13.sp) }

                        Button(
                            onClick = { activeMode = StreamMode.RTSP_H264 },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            shape = CircleShape,
                            colors = if (activeMode == StreamMode.RTSP_H264) activeColor else inactiveColor
                        ) { Text("RTSP / H.264", fontSize = 13.sp) }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (activeMode == StreamMode.TCP_JPEG) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            ExposedDropdownMenuBox(
                                expanded = ipDropdownExpanded,
                                onExpandedChange = { if (recentIps.isNotEmpty()) ipDropdownExpanded = it },
                                modifier = Modifier.weight(3f)
                            ) {
                                OutlinedTextField(
                                    value = targetHost,
                                    onValueChange = { targetHost = it },
                                    label = { Text("Receiver IP") },
                                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                                    trailingIcon = { if (recentIps.isNotEmpty()) { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ipDropdownExpanded) } },
                                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFD0BCFF), unfocusedBorderColor = Color(0xFF49454F))
                                )
                                if (recentIps.isNotEmpty()) {
                                    ExposedDropdownMenu(
                                        expanded = ipDropdownExpanded,
                                        onDismissRequest = { ipDropdownExpanded = false }
                                    ) {
                                        recentIps.forEach { ip ->
                                            DropdownMenuItem(text = { Text(ip, fontFamily = FontFamily.Monospace) }, onClick = { targetHost = ip; ipDropdownExpanded = false })
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedTextField(
                                value = tcpPortStr,
                                onValueChange = { tcpPortStr = it },
                                label = { Text("Port") },
                                modifier = Modifier.weight(1.2f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFD0BCFF), unfocusedBorderColor = Color(0xFF49454F))
                            )
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = rtspPortStr,
                                onValueChange = { rtspPortStr = it },
                                label = { Text("Server Binding Port") },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFD0BCFF), unfocusedBorderColor = Color(0xFF49454F))
                            )
                            if (streamState == StreamState.STREAMING && rtspUrl.isNotBlank()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth().clip(CircleShape).background(Color(0xFF0E0E11)).padding(start = 16.dp, end = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(rtspUrl, fontFamily = FontFamily.Monospace, color = Color(0xFFD0BCFF), fontSize = 11.sp, modifier = Modifier.weight(1f))
                                    TextButton(onClick = {
                                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cb.setPrimaryClip(ClipData.newPlainText("RTSP URL", rtspUrl))
                                        Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
                                    }) { Text("COPY", fontWeight = FontWeight.Black, color = Color(0xFFE9DDFF)) }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    ExposedDropdownMenuBox(
                        expanded = resolutionExpanded,
                        onExpandedChange = { resolutionExpanded = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = selectedResolution.label,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Resolution") },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = resolutionExpanded) },
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFD0BCFF), unfocusedBorderColor = Color(0xFF49454F))
                        )
                        ExposedDropdownMenu(
                            expanded = resolutionExpanded,
                            onDismissRequest = { resolutionExpanded = false }
                        ) {
                            StreamViewModel.Resolution.entries.forEach { res ->
                                DropdownMenuItem(text = { Text(res.label, fontFamily = FontFamily.Monospace) }, onClick = { selectedResolution = res; resolutionExpanded = false })
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text("FRAME RATE", color = Color(0xFF948F9A), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start), letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0E0E11))
                            .padding(4.dp)
                    ) {
                        val activeColor = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B292D), contentColor = Color.White)
                        val inactiveColor = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color(0xFF948F9A))

                        Button(
                            onClick = { selectedFps = 30 },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            shape = CircleShape,
                            colors = if (selectedFps == 30) activeColor else inactiveColor
                        ) { Text("30 FPS", fontSize = 13.sp) }

                        Button(
                            onClick = { selectedFps = 60 },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            shape = CircleShape,
                            colors = if (selectedFps == 60) activeColor else inactiveColor
                        ) { Text("60 FPS", fontSize = 13.sp) }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFB4AB), modifier = Modifier.size(14.dp).padding(top = 1.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "60 FPS forces aggressive CPU/GPU polling. Older or mid-range devices may drastically overheat, drop frames, or stutter during broadcast.",
                            color = Color(0xFFFFB4AB),
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(Color.Black)
            ) {
                if (isPreview) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) {
                        Text("CAMERA FEED PREVIEW", color = Color.LightGray)
                    }
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                                previewViewState.value = this
                                onStartPreview(this)
                            }
                        }
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(top = 16.dp, start = 16.dp, end = 16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .clip(CircleShape)
                            .background(Color(0xFF141317).copy(alpha = 0.75f))
                            .border(1.dp, Color(0xFF2B292D), CircleShape)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val dotColor = when (streamState) {
                            StreamState.STREAMING -> Color.Green
                            StreamState.CONNECTING -> Color.Yellow
                            StreamState.ERROR -> Color.Red
                            else -> Color.Gray
                        }
                        Box(modifier = Modifier.width(10.dp).height(10.dp).clip(CircleShape).background(dotColor))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (streamState == StreamState.STREAMING) "${activeMode.name} ACTIVE" else statusText.uppercase(),
                            color = Color(0xFFE5E1E7), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                        )
                        if (currentFps > 0) {
                            Spacer(modifier = Modifier.padding(horizontal = 12.dp).width(1.dp).height(14.dp).background(Color(0xFF49454F)))
                            Text(text = "$currentFps FPS", color = Color(0xFFB6C4FF), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }

                    IconButton(
                        onClick = { showAboutScreen = true },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .clip(CircleShape)
                            .background(Color(0xFF141317).copy(alpha = 0.75f))
                            .border(1.dp, Color(0xFF2B292D), CircleShape)
                            .size(38.dp)
                    ) {
                        Icon(imageVector = Icons.Default.MoreVert, contentDescription = "About App", tint = Color(0xFFE5E1E7))
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 16.dp, end = 16.dp)
                ) {
                    IconButton(
                        onClick = {
                            val previewView = previewViewState.value
                            if (previewView != null) onFlipCamera(previewView)
                            else Toast.makeText(context, "Camera not ready", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xFF141317).copy(alpha = 0.85f))
                            .border(1.dp, Color(0xFF2B292D), CircleShape)
                            .size(54.dp)
                    ) {
                        Icon(
                            imageVector = FlipCameraAndroidIcon,
                            contentDescription = "Flip Camera",
                            tint = Color(0xFFE5E1E7),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                AnimatedVisibility(
                    visible = showSwipeHint,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    val infiniteTransition = rememberInfiniteTransition(label = "bounce")
                    val offsetY by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = -25f,
                        animationSpec = infiniteRepeatable(animation = tween(800, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
                        label = "offset"
                    )

                    Surface(
                        shape = CircleShape, color = Color(0xFFD0BCFF),
                        modifier = Modifier.padding(bottom = 16.dp).offset(y = offsetY.dp).clickable { onDismissHint() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = Color(0xFF37265E))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Swipe up for Settings", color = Color(0xFF37265E), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showAboutScreen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            BackHandler(enabled = showAboutScreen) { showAboutScreen = false }

            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

            Column(
                modifier = Modifier.fillMaxSize().background(Color(0xFF141317)).statusBarsPadding().navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showAboutScreen = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close", tint = Color.White) }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("ABOUT", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 1.sp)
                }

                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp).verticalScroll(rememberScrollState())
                ) {
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Logo and Title Section
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                modifier = Modifier.size(80.dp),
                                shape = RoundedCornerShape(20.dp),
                                color = Color.White,
                                border = BorderStroke(1.dp, Color(0xFF2B292D))
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.applogo),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    tint = Color.Unspecified
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("StreamCam", color = Color(0xFFD0BCFF), fontSize = 28.sp, fontWeight = FontWeight.Black)
                            Text("v1.0.0 Stable Build", color = Color(0xFF49454F), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    // App Working Explanation
                    Text("HOW IT WORKS", color = Color(0xFF49454F), fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "StreamCam turns your Android phone into a wireless camera for your computer. Easily stream live video over your local network with low delay, making it perfect for monitoring, recording, video calls, or using your phone as a webcam. Just connect your phone and computer to the same network and start streaming in a few taps.",
                        color = Color(0xFFE5E1E7),
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    Text("PROJECT LINKS", color = Color(0xFF49454F), fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { uriHandler.openUri("https://drive.google.com/file/d/1WSrp0WbR6oczmZ886-D5AUNLE6jLw76z/view?usp=sharing") },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B292D), contentColor = Color(0xFFE5E1E7)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.youtube),
                            contentDescription = "YouTube",
                            modifier = Modifier.size(20.dp),
                            tint = Color.Unspecified
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Watch Video Demo", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { uriHandler.openUri("https://github.com/akshit-singhh/StreamCam") },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B292D), contentColor = Color(0xFFE5E1E7)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.github),
                            contentDescription = "GitHub",
                            modifier = Modifier.size(20.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("View Source on GitHub", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    Text("CORE ARCHITECTURE", color = Color(0xFF49454F), fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    LibraryItem("CameraX Engine", "androidx.camera", "Hardware-accelerated rendering and low-latency frame extraction.")
                    LibraryItem("Jetpack Compose", "androidx.compose", "Declarative, hardware-accelerated UI toolkit driving the broadcast HUD.")
                    LibraryItem("Kotlin Coroutines", "org.jetbrains.kotlinx", "Asynchronous non-blocking network socket operations.")
                    LibraryItem("MediaCodec API", "android.media", "Native Android GPU wrapper for raw H.264 video compression.")
                    LibraryItem("RFC 3984 Packetizer", "Custom TCP/UDP", "Interleaved RTSP streaming protocol implementation.")

                    Spacer(modifier = Modifier.height(40.dp))
                    
                    // Developer Section at Bottom
                    HorizontalDivider(color = Color(0xFF2B292D), thickness = 1.dp)
                    Spacer(modifier = Modifier.height(24.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Developed by Akshit Singh",
                            color = Color(0xFF948F9A),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Android Developer",
                            color = Color(0xFF49454F),
                            fontSize = 10.sp,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LibraryItem(name: String, pkg: String, desc: String) {
    Column(modifier = Modifier.padding(bottom = 20.dp)) {
        Text(name, color = Color(0xFFE5E1E7), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(2.dp))
        Text(pkg, color = Color(0xFFB6C4FF), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(desc, color = Color(0xFF948F9A), fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF141317)
@Composable
fun BroadcastPreview() {
    StreamCamTheme {
        BroadcastContent(
            streamState = StreamState.IDLE, statusText = "Ready", currentFps = 30, deviceIp = "192.168.1.50",
            rtspUrl = "rtsp://192.168.1.50:8554/live", recentIps = listOf("192.168.1.10"), showSwipeHint = true,
            onDismissHint = {}, onStartPreview = {}, onStartStreaming = { _, _, _, _, _, _, _ -> }, onFlipCamera = {}, onStopStreaming = {}
        )
    }
}