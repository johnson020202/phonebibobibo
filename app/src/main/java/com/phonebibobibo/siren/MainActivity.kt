package com.phonebibobibo.siren

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.phonebibobibo.siren.audio.SirenEngine
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var controller: SirenController
    private var volumeToast: Toast? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()
        controller = SirenController(applicationContext)
        setContent {
            PhoneSirenTheme {
                SirenScreen(controller)
            }
        }
    }

    override fun onStop() {
        controller.onBackground()
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val step =
                        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 0.05f else -0.05f
                    val next = (controller.volume + step).coerceIn(0f, 1f)
                    controller.updateVolume(next)
                    if (event.repeatCount == 0) {
                        volumeToast?.cancel()
                        volumeToast = Toast.makeText(
                            this,
                            "警报音量 ${(next * 100).roundToInt()}%",
                            Toast.LENGTH_SHORT
                        ).also { it.show() }
                    }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        controller.release()
        super.onDestroy()
    }

    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

// ---------------------------------------------------------------------------
// Palette
// ---------------------------------------------------------------------------

private data class Palette(
    val bgTop: Color,
    val bgBottom: Color,
    val border: Color,
    val title: Color,
    val subtitle: Color,
    val activeTop: Color,
    val activeBottom: Color,
    val activeBorder: Color,
    val glow: Color,
    val activeTitle: Color,
    val activeSubtitle: Color
)

private val RedCard = Palette(
    bgTop = Color(0xFF2A1013), bgBottom = Color(0xFF190A0C), border = Color(0xFF6D2830),
    title = Color(0xFFFF6B70), subtitle = Color(0xFFC28086),
    activeTop = Color(0xFF4C151C), activeBottom = Color(0xFF300C11),
    activeBorder = Color(0xFFFF7D84), glow = Color(0xFFFF3B47),
    activeTitle = Color(0xFFFFF5F5), activeSubtitle = Color(0xFFFFC9CB)
)

private val GreenCard = Palette(
    bgTop = Color(0xFF0C2417), bgBottom = Color(0xFF07150D), border = Color(0xFF2C6B45),
    title = Color(0xFF5BE38B), subtitle = Color(0xFF7EA58E),
    activeTop = Color(0xFF12452B), activeBottom = Color(0xFF0A2A1A),
    activeBorder = Color(0xFF5BE38B), glow = Color(0xFF22C55E),
    activeTitle = Color(0xFFF2FFF7), activeSubtitle = Color(0xFFB5EAC8)
)

private val AmberCard = Palette(
    bgTop = Color(0xFF2A1E0C), bgBottom = Color(0xFF191206), border = Color(0xFF6B5124),
    title = Color(0xFFE8B45F), subtitle = Color(0xFFA68A58),
    activeTop = Color(0xFFF07A12), activeBottom = Color(0xFFC14605),
    activeBorder = Color(0xFFFFC58C), glow = Color(0xFFF97316),
    activeTitle = Color(0xFFFFFAF4), activeSubtitle = Color(0xFFFFE2C2)
)

private val GoldCard = Palette(
    bgTop = Color(0xFF2A210E), bgBottom = Color(0xFF181206), border = Color(0xFF6A5624),
    title = Color(0xFFE7CD6F), subtitle = Color(0xFFA8935A),
    activeTop = Color(0xFF4B3812), activeBottom = Color(0xFF2E2208),
    activeBorder = Color(0xFFF0D274), glow = Color(0xFFEAB308),
    activeTitle = Color(0xFFFFFBEB), activeSubtitle = Color(0xFFE8D89B)
)

private val SlateCard = Palette(
    bgTop = Color(0xFF141A22), bgBottom = Color(0xFF0E131A), border = Color(0xFF2B3644),
    title = Color(0xFFBBD0EA), subtitle = Color(0xFF77859A),
    activeTop = Color(0xFF1F3C58), activeBottom = Color(0xFF142A3F),
    activeBorder = Color(0xFF7DC0F0), glow = Color(0xFF38BDF8),
    activeTitle = Color(0xFFF5FBFF), activeSubtitle = Color(0xFFC5E4F7)
)

private val IndigoCard = Palette(
    bgTop = Color(0xFF151A26), bgBottom = Color(0xFF0E111A), border = Color(0xFF303A52),
    title = Color(0xFFC0C9F2), subtitle = Color(0xFF7E8AA8),
    activeTop = Color(0xFF262E4A), activeBottom = Color(0xFF171C30),
    activeBorder = Color(0xFFA9B7FF), glow = Color(0xFF6366F1),
    activeTitle = Color(0xFFF6F7FF), activeSubtitle = Color(0xFFC9D0F5)
)

private val TealCard = Palette(
    bgTop = Color(0xFF0A2423), bgBottom = Color(0xFF061617), border = Color(0xFF255E59),
    title = Color(0xFF4FD9C6), subtitle = Color(0xFF6C9F99),
    activeTop = Color(0xFF0F3B38), activeBottom = Color(0xFF082926),
    activeBorder = Color(0xFF45E0C8), glow = Color(0xFF14B8A6),
    activeTitle = Color(0xFFF0FFFC), activeSubtitle = Color(0xFFB2EFE6)
)

private val PurpleCard = Palette(
    bgTop = Color(0xFF201431), bgBottom = Color(0xFF140C1E), border = Color(0xFF4B3369),
    title = Color(0xFFCBA9F2), subtitle = Color(0xFF8D7AA5),
    activeTop = Color(0xFF33204D), activeBottom = Color(0xFF211333),
    activeBorder = Color(0xFFC9A8F0), glow = Color(0xFF8B5CF6),
    activeTitle = Color(0xFFFBF7FF), activeSubtitle = Color(0xFFDCC9F2)
)

private val SteelCard = Palette(
    bgTop = Color(0xFF151C26), bgBottom = Color(0xFF0E141C), border = Color(0xFF2C3A4A),
    title = Color(0xFFA9BED6), subtitle = Color(0xFF77879B),
    activeTop = Color(0xFF22364B), activeBottom = Color(0xFF162535),
    activeBorder = Color(0xFF8FB8DB), glow = Color(0xFF3B82F6),
    activeTitle = Color(0xFFF4FAFF), activeSubtitle = Color(0xFFC3DAEF)
)

private val CrimsonCard = Palette(
    bgTop = Color(0xFF2A1014), bgBottom = Color(0xFF190A0C), border = Color(0xFF6E2830),
    title = Color(0xFFFF6B70), subtitle = Color(0xFFC28086),
    activeTop = Color(0xFF5A151C), activeBottom = Color(0xFF3A0C11),
    activeBorder = Color(0xFFFF8A90), glow = Color(0xFFEF4444),
    activeTitle = Color(0xFFFFF5F5), activeSubtitle = Color(0xFFFFC9CB)
)

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

private val Bg = Color(0xFF06090D)
private val TextPrimary = Color(0xFFE9EDF3)

@Composable
private fun PhoneSirenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFF29B38),
            onPrimary = Color(0xFF1B0F02),
            background = Bg,
            onBackground = TextPrimary,
            surface = Color(0xFF10151C),
            onSurface = TextPrimary
        ),
        content = content
    )
}

@Composable
private fun SirenScreen(controller: SirenController) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var showSettings by remember { mutableStateOf(false) }
    var showContact by remember { mutableStateOf(false) }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, "需要麦克风权限才能录音", Toast.LENGTH_SHORT).show()
        }
    }

    val cutoutTop = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
    val scrollState = rememberScrollState()
    val view = LocalView.current
    SideEffect { view.keepScreenOn = controller.keepScreenOn }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(top = maxOf(cutoutTop, 26.dp))
    ) {
        AppHeader(controller.engine)
        StatusStrip(controller)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 14.dp)
        ) {
            SectionTitle("号角与广播")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TacticalCard(
                    title = "气笛号角",
                    subtitle = "点按触发",
                    palette = RedCard,
                    height = 58.dp,
                    titleSize = 19.sp,
                    subtitleSize = 10.sp,
                    modifier = Modifier.weight(1f),
                    active = controller.airHornActive,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        controller.triggerAirHorn()
                    }
                )
                TacticalCard(
                    title = "车外广播",
                    subtitle = if (controller.recording) "录音中 · 松开结束" else "按住录音",
                    palette = GreenCard,
                    height = 58.dp,
                    titleSize = 19.sp,
                    subtitleSize = 10.sp,
                    modifier = Modifier.weight(1f),
                    active = controller.recording,
                    onPress = {
                        val granted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            controller.startRecording()
                        } else {
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            Toast.makeText(
                                context, "授权后请再次按住录音", Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onRelease = { controller.stopRecordingAndPlay() }
                )
            }

            SectionTitle("战术警灯")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val lightTitle = 13.sp
                val lightSubtitle = 8.5.sp
                TacticalCard(
                    title = LightMode.AUTO.label, subtitle = LightMode.AUTO.hint,
                    palette = AmberCard, height = 50.dp,
                    titleSize = lightTitle, subtitleSize = lightSubtitle,
                    modifier = Modifier.weight(1f),
                    active = controller.lightMode == LightMode.AUTO,
                    onClick = { controller.toggleLight(LightMode.AUTO) }
                )
                TacticalCard(
                    title = LightMode.MODE_1.label, subtitle = LightMode.MODE_1.hint,
                    palette = AmberCard, height = 50.dp,
                    titleSize = lightTitle, subtitleSize = lightSubtitle,
                    modifier = Modifier.weight(1f),
                    active = controller.lightMode == LightMode.MODE_1,
                    onClick = { controller.toggleLight(LightMode.MODE_1) }
                )
                TacticalCard(
                    title = LightMode.MODE_2.label, subtitle = LightMode.MODE_2.hint,
                    palette = AmberCard, height = 50.dp,
                    titleSize = lightTitle, subtitleSize = lightSubtitle,
                    modifier = Modifier.weight(1f),
                    active = controller.lightMode == LightMode.MODE_2,
                    onClick = { controller.toggleLight(LightMode.MODE_2) }
                )
                TacticalCard(
                    title = LightMode.MODE_3.label, subtitle = LightMode.MODE_3.hint,
                    palette = AmberCard, height = 50.dp,
                    titleSize = lightTitle, subtitleSize = lightSubtitle,
                    modifier = Modifier.weight(1f),
                    active = controller.lightMode == LightMode.MODE_3,
                    onClick = { controller.toggleLight(LightMode.MODE_3) }
                )
                TacticalCard(
                    title = LightMode.STEADY.label, subtitle = LightMode.STEADY.hint,
                    palette = AmberCard, height = 50.dp,
                    titleSize = lightTitle, subtitleSize = lightSubtitle,
                    modifier = Modifier.weight(1f),
                    active = controller.lightMode == LightMode.STEADY,
                    onClick = { controller.toggleLight(LightMode.STEADY) }
                )
            }

            SectionTitle("手动电笛")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TacticalCard(
                    title = "高音电笛",
                    subtitle = "长按爬升 / 松开滑落",
                    palette = GoldCard,
                    height = 54.dp,
                    titleSize = 19.sp,
                    subtitleSize = 9.5.sp,
                    modifier = Modifier.weight(1f),
                    active = controller.manualHeld && controller.manualHigh,
                    onPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        controller.startManual(true)
                    },
                    onRelease = { controller.releaseManual() }
                )
                TacticalCard(
                    title = "低音电笛",
                    subtitle = "长按爬升 / 飞轮拖尾",
                    palette = GoldCard,
                    height = 54.dp,
                    titleSize = 19.sp,
                    subtitleSize = 9.5.sp,
                    modifier = Modifier.weight(1f),
                    active = controller.manualHeld && !controller.manualHigh,
                    onPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        controller.startManual(false)
                    },
                    onRelease = { controller.releaseManual() }
                )
            }

            SectionTitle("巡航警报")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SirenCard(controller, SirenProgram.WAIL_STD, SlateCard, Modifier.weight(1f))
                SirenCard(controller, SirenProgram.WAIL_SLOW, SlateCard, Modifier.weight(1f))
                SirenCard(controller, SirenProgram.WAIL_FAST, SlateCard, Modifier.weight(1f))
            }

            SectionTitle("急促警报")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SirenCard(controller, SirenProgram.YELP, IndigoCard, Modifier.weight(1f))
                SirenCard(controller, SirenProgram.PURSUIT, IndigoCard, Modifier.weight(1f))
                SirenCard(controller, SirenProgram.PHASER, IndigoCard, Modifier.weight(1f))
            }

            SectionTitle("特种与轮换")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SirenCard(controller, SirenProgram.TREMOLO, SlateCard, Modifier.weight(1f))
                SirenCard(controller, SirenProgram.HI_LO, SlateCard, Modifier.weight(1f))
                SirenCard(controller, SirenProgram.ROTATE, TealCard, Modifier.weight(1f))
            }

            SectionTitle("双音并发")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TacticalCard(
                    title = SirenProgram.DUAL_A.label,
                    subtitle = SirenProgram.DUAL_A.hint,
                    palette = PurpleCard,
                    height = 54.dp,
                    titleSize = 19.sp,
                    subtitleSize = 9.5.sp,
                    modifier = Modifier.weight(1f),
                    active = controller.activeSiren == SirenProgram.DUAL_A,
                    onClick = { controller.toggleSiren(SirenProgram.DUAL_A) }
                )
                TacticalCard(
                    title = SirenProgram.DUAL_B.label,
                    subtitle = SirenProgram.DUAL_B.hint,
                    palette = PurpleCard,
                    height = 54.dp,
                    titleSize = 19.sp,
                    subtitleSize = 9.5.sp,
                    modifier = Modifier.weight(1f),
                    active = controller.activeSiren == SirenProgram.DUAL_B,
                    onClick = { controller.toggleSiren(SirenProgram.DUAL_B) }
                )
            }

            SectionTitle("车辆与场景音效")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                EffectCard(controller, SoundEffect.TRUCK_HORN, SteelCard, Modifier.weight(1f))
                EffectCard(controller, SoundEffect.CAR_HORN, GoldCard, Modifier.weight(1f))
                EffectCard(controller, SoundEffect.CODE_BEEP, TealCard, Modifier.weight(1f))
                EffectCard(controller, SoundEffect.STATION_CHIME, IndigoCard, Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                EffectCard(controller, SoundEffect.SHIP_HORN, SlateCard, Modifier.weight(1f))
                EffectCard(controller, SoundEffect.WARSHIP_HORN, PurpleCard, Modifier.weight(1f))
                EffectCard(controller, SoundEffect.PORSCHE_REV, RedCard, Modifier.weight(1f))
                EffectCard(controller, SoundEffect.ACCEL, AmberCard, Modifier.weight(1f))
            }

            SectionTitle("系统控制")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TacticalCard(
                    title = "重低音脉冲",
                    subtitle = if (controller.subBassOn) "已开启" else "点击开启",
                    palette = AmberCard,
                    height = 52.dp,
                    titleSize = 14.sp,
                    subtitleSize = 8.5.sp,
                    modifier = Modifier.weight(1f),
                    active = controller.subBassOn,
                    onClick = { controller.toggleSubBass() }
                )
                TacticalCard(
                    title = "系统设置",
                    subtitle = "调音设置",
                    palette = SteelCard,
                    height = 52.dp,
                    titleSize = 14.sp,
                    subtitleSize = 8.5.sp,
                    modifier = Modifier.weight(1f),
                    onClick = { showSettings = true }
                )
                TacticalCard(
                    title = "低功率",
                    subtitle = if (controller.lowPower) "已开启" else "-12dB",
                    palette = SteelCard,
                    height = 52.dp,
                    titleSize = 14.sp,
                    subtitleSize = 8.5.sp,
                    modifier = Modifier.weight(1f),
                    active = controller.lowPower,
                    onClick = { controller.toggleLowPower() }
                )
                TacticalCard(
                    title = "静音",
                    subtitle = "一键全停",
                    palette = CrimsonCard,
                    height = 52.dp,
                    titleSize = 14.sp,
                    subtitleSize = 8.5.sp,
                    modifier = Modifier.weight(1f),
                    active = controller.muteFlash,
                    onClick = { controller.muteAll() }
                )
            }
            Spacer(Modifier.height(4.dp))
        }

        AppFooter(onContact = { showContact = true })
    }

    if (showSettings) {
        SettingsSheet(controller, onClose = { showSettings = false })
    }
    if (showContact) {
        ContactDialog(onDismiss = { showContact = false })
    }
}

@Composable
private fun SirenCard(
    controller: SirenController,
    program: SirenProgram,
    palette: Palette,
    modifier: Modifier
) {
    TacticalCard(
        title = program.label,
        subtitle = program.hint,
        palette = palette,
        height = 50.dp,
        titleSize = 16.sp,
        subtitleSize = 9.5.sp,
        modifier = modifier,
        active = controller.activeSiren == program,
        onClick = { controller.toggleSiren(program) }
    )
}

@Composable
private fun EffectCard(
    controller: SirenController,
    effect: SoundEffect,
    palette: Palette,
    modifier: Modifier
) {
    TacticalCard(
        title = effect.label,
        subtitle = effect.hint,
        palette = palette,
        height = 50.dp,
        titleSize = 12.sp,
        subtitleSize = 8.sp,
        modifier = modifier,
        active = controller.activeEffect == effect,
        onClick = { controller.playEffect(effect) }
    )
}

// ---------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------

@Composable
private fun AppHeader(engine: SirenEngine) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF181E26), Color(0xFF0E1319))
                )
            )
            .padding(horizontal = 14.dp)
    ) {
        Spacer(Modifier.height(6.dp))
        LevelMeter(engine)
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "手机警报器",
                color = Color(0xFFF4F8FC),
                fontSize = 21.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                style = TextStyle(
                    shadow = Shadow(
                        color = Color(0x553B82F6),
                        offset = Offset.Zero,
                        blurRadius = 16f
                    )
                )
            )
        }
        Spacer(Modifier.height(3.dp))
    }
}

@Composable
private fun LevelMeter(engine: SirenEngine) {
    var level by remember { mutableFloatStateOf(0.16f) }
    LaunchedEffect(engine) {
        while (true) {
            level = if (engine.sounding) engine.level.coerceIn(0.08f, 1f) else 0.16f
            delay(45)
        }
    }
    val filledCount = if (!engine.sounding) {
        4
    } else {
        (level * 17f).roundToInt().coerceIn(4, 12)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        repeat(12) { index ->
            val filled = index < filledCount
            val peak = filled && index == filledCount - 1
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        when {
                            peak -> Color(0xFFF5A236)
                            filled -> Color(0xFF2F7CF6)
                            else -> Color(0xFF1A212A)
                        }
                    )
            )
        }
    }
}

@Composable
private fun StatusStrip(controller: SirenController) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(29.dp)
            .background(Color(0xFF0B1016))
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawLine(Color(0xFF1B222C), Offset(0f, 0f), Offset(size.width, 0f), stroke)
                drawLine(
                    Color(0xFF1B222C),
                    Offset(0f, size.height),
                    Offset(size.width, size.height),
                    stroke
                )
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(13.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Color(0x223DDC84))
            )
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3DDC84))
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = controller.statusText,
            color = Color(0xFFD3DAE4),
            fontSize = 12.sp,
            letterSpacing = 0.4.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(12.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(Color(0xFFF29B38))
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = text,
            color = Color(0xFFB5BFCC),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.2.sp
        )
    }
}

@Composable
private fun TacticalCard(
    title: String,
    subtitle: String,
    palette: Palette,
    height: Dp,
    titleSize: TextUnit,
    subtitleSize: TextUnit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: (() -> Unit)? = null,
    onPress: (() -> Unit)? = null,
    onRelease: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(10.dp)
    val background = if (active) {
        Brush.verticalGradient(listOf(palette.activeTop, palette.activeBottom))
    } else {
        Brush.verticalGradient(listOf(palette.bgTop, palette.bgBottom))
    }
    val borderColor = if (active) palette.activeBorder else palette.border
    val titleColor = if (active) palette.activeTitle else palette.title
    val subtitleColor = if (active) palette.activeSubtitle else palette.subtitle

    val latestPress by rememberUpdatedState(onPress)
    val latestRelease by rememberUpdatedState(onRelease)

    var cardModifier = modifier
        .height(height)
        .then(
            if (active) {
                Modifier.shadow(
                    elevation = 11.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = palette.glow,
                    spotColor = palette.glow
                )
            } else {
                Modifier
            }
        )
        .clip(shape)
        .background(background)
        .border(if (active) 1.5.dp else 1.dp, borderColor, shape)

    if (onClick != null) {
        cardModifier = cardModifier.clickable { onClick() }
    } else if (onPress != null) {
        cardModifier = cardModifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    latestPress?.invoke()
                    tryAwaitRelease()
                    latestRelease?.invoke()
                }
            )
        }
    }

    Box(cardModifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                color = titleColor,
                fontSize = titleSize,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitle,
                color = subtitleColor,
                fontSize = subtitleSize,
                letterSpacing = 0.2.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AppFooter(onContact: () -> Unit) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFF171D25))
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                color = Color(0xFF6C7685),
                fontSize = 10.sp,
                letterSpacing = 0.6.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "免费软件 · 严禁倒卖",
                color = Color(0xFFFF5A61),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp
            )
            Spacer(Modifier.width(10.dp))
            val chipShape = RoundedCornerShape(6.dp)
            Box(
                modifier = Modifier
                    .clip(chipShape)
                    .background(Color(0xFF1A2634))
                    .border(1.dp, Color(0xFF36506C), chipShape)
                    .clickable { onContact() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "联系作者",
                    color = Color(0xFFBFD9FF),
                    fontSize = 10.sp
                )
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

// ---------------------------------------------------------------------------
// Settings & contact
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(controller: SirenController, onClose: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = Color(0xFF0F141B),
        contentColor = TextPrimary,
        scrimColor = Color(0xCC000000)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("调音设置", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))

            SettingLabel("总音量", "${(controller.volume * 100).roundToInt()}%")
            Slider(
                value = controller.volume,
                onValueChange = { controller.updateVolume(it) },
                valueRange = 0f..1f,
                colors = orangeSliderColors()
            )

            Spacer(Modifier.height(6.dp))
            SettingLabel(
                "音调微调",
                if (controller.transpose >= 0) "+${controller.transpose} 半音" else "${controller.transpose} 半音"
            )
            Slider(
                value = controller.transpose.toFloat(),
                onValueChange = { controller.updateTranspose(it.roundToInt()) },
                valueRange = -6f..6f,
                steps = 11,
                colors = orangeSliderColors()
            )

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("低功率模式", color = TextPrimary, fontSize = 13.sp)
                    Text(
                        "输出衰减约 -12dB，夜间使用更克制",
                        color = Color(0xFF7E8A99),
                        fontSize = 10.sp
                    )
                }
                Switch(
                    checked = controller.lowPower,
                    onCheckedChange = { controller.toggleLowPower() },
                    colors = switchColors()
                )
            }

            Spacer(Modifier.height(18.dp))
            Text(
                text = "提示：所有警报音由手机实时合成。连接蓝牙音箱或车载音响时效果更好。请勿贴近耳朵使用，长时间高音量可能损伤听力。",
                color = Color(0xFF7E8A99),
                fontSize = 10.5.sp,
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFFF5A236), Color(0xFFE36A08))
                        )
                    )
                    .clickable { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "完成",
                    color = Color(0xFF1A0D02),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun SettingLabel(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextPrimary, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = Color(0xFFF29B38), fontSize = 13.sp)
    }
}

@Composable
private fun orangeSliderColors() = SliderDefaults.colors(
    thumbColor = Color(0xFFF29B38),
    activeTrackColor = Color(0xFFF29B38),
    inactiveTrackColor = Color(0xFF27303B),
    activeTickColor = Color(0xFFF29B38),
    inactiveTickColor = Color(0xFF27303B)
)

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color(0xFF171006),
    checkedTrackColor = Color(0xFFF29B38),
    checkedBorderColor = Color(0xFFF29B38),
    uncheckedThumbColor = Color(0xFF8A95A4),
    uncheckedTrackColor = Color(0xFF1B232D),
    uncheckedBorderColor = Color(0xFF34404D)
)

@Composable
private fun ContactDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF10161F),
        titleContentColor = TextPrimary,
        textContentColor = Color(0xFF9AA6B5),
        shape = RoundedCornerShape(16.dp),
        title = { Text("联系作者", fontWeight = FontWeight.Bold) },
        text = {
            Text(
                text = "手机警报器 v${BuildConfig.VERSION_NAME}\n" +
                    "免费软件 · 严禁倒卖\n\n" +
                    "问题反馈与更新：\ngithub.com/johnson020202/phonebibobibo",
                fontSize = 12.sp,
                lineHeight = 20.sp
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    openRepo(context)
                    onDismiss()
                }
            ) {
                Text("打开仓库", color = Color(0xFFF29B38))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = Color(0xFF8FA0B5))
            }
        }
    )
}

private fun openRepo(context: Context) {
    val intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://github.com/johnson020202/phonebibobibo")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(context, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
        }
}
