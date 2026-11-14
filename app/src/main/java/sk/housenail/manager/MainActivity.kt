@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package sk.housenail.manager

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.content.Context
import android.graphics.Bitmap
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.integration.android.IntentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val api = ApiClient()
    private val zone = ZoneId.of("Europe/Bratislava")
    private val dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    private var authenticated by mutableStateOf(false)
    private var lockEnabled by mutableStateOf(false)
    private var deviceUnlocked by mutableStateOf(true)
    private var authenticating = false
    private var pendingDeviceUnlock: (() -> Unit)? = null
    private var loading by mutableStateOf(false)
    private var tab by mutableIntStateOf(0)
    private var selectedDay by mutableStateOf(LocalDate.now(zone))
    private var bookings by mutableStateOf<List<JSONObject>>(emptyList())
    private var members by mutableStateOf<List<JSONObject>>(emptyList())
    private var memberPage by mutableIntStateOf(1)
    private var memberTotal by mutableIntStateOf(0)
    private var memberQuery = ""
    private var memberRefresh by mutableIntStateOf(0)
    private var bookingFilter by mutableStateOf("Tất cả")
    private var bookingSearch by mutableStateOf("")
    private var timelineMode by mutableStateOf(true)
    private var selectedBooking by mutableStateOf<JSONObject?>(null)
    private var selectedMember by mutableStateOf<String?>(null)
    private var themeName by mutableStateOf("pearl")
    private var lastSync by mutableStateOf<String?>(null)
    private var screen by mutableStateOf("main")
    private var reportPeriod by mutableStateOf("Tháng")
    private var reportAnchor by mutableStateOf(LocalDate.now(zone))
    private var reportRows by mutableStateOf<List<JSONObject>>(emptyList())
    private var reportBusy by mutableStateOf(false)
    private var reportError by mutableStateOf("")
    private var reportSelected by mutableStateOf<String?>(null)
    private var reportRefresh by mutableIntStateOf(0)
    private var insightRows by mutableStateOf<List<JSONObject>>(emptyList())
    private var insightBusy by mutableStateOf(false)
    private var insightError by mutableStateOf("")
    private var insightSelected by mutableStateOf<String?>(null)
    private var insightRefresh by mutableIntStateOf(0)
    private var diagnostics by mutableStateOf("Chưa kiểm tra")
    private var diagnosticBusy by mutableStateOf(false)
    private var editorOpen by mutableStateOf(false)
    private var editorMoving by mutableStateOf<JSONObject?>(null)
    private var editorServiceId by mutableStateOf("")
    private var editorServices by mutableStateOf<List<JSONObject>>(emptyList())
    private var editorSlots by mutableStateOf<List<String>>(emptyList())
    private var editorDate by mutableStateOf(LocalDate.now(zone))
    private var editorTime by mutableStateOf("")
    private var editorName by mutableStateOf("")
    private var editorPhone by mutableStateOf("")
    private var editorEmail by mutableStateOf("")
    private var editorNote by mutableStateOf("")
    private var editorConsent by mutableStateOf(false)
    private var editorBusy by mutableStateOf(false)
    private var editorSlotsBusy by mutableStateOf(false)
    private var editorError by mutableStateOf("")
    private var editorRequestId = UUID.randomUUID().toString()
    private var editorRequestGeneration = 0
    private var pendingAlertBookingId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeName = getSharedPreferences("housenail_v10", MODE_PRIVATE).getString("theme", "pearl") ?: "pearl"
        lockEnabled = getSharedPreferences("housenail_v10", MODE_PRIVATE).getBoolean("deviceLock", false)
        deviceUnlocked = !lockEnabled
        BookingAlerts.createChannel(this)
        handleBookingAlert(intent)
        setContent { HouseNailV10() }
        verifySession()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (authenticated && BookingAlerts.enabled(this@MainActivity)) {
                        withContext(Dispatchers.IO) {
                            try { BookingAlerts.checkNow(this@MainActivity) }
                            catch (_: Exception) { /* Retry on the next scan. */ }
                        }
                    }
                    delay(45_000L)
                }
            }
        }
    }

    private fun verifySession() {
        loading = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                api.verifyOwnerSession()
                withContext(Dispatchers.Main) {
                    authenticated = true
                    BookingAlerts.start(this@MainActivity)
                    BookingAlerts.requestPermissionIfNeeded(this@MainActivity)
                    lifecycleScope.launch(Dispatchers.IO) {
                        try { BookingAlerts.checkNow(this@MainActivity) } catch (_: Exception) {}
                    }
                    reloadDay()
                }
            } catch (_: ApiClient.AuthException) {
                withContext(Dispatchers.Main) { openLogin() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast(e.message ?: "Không kết nối được máy chủ.") }
            } finally {
                withContext(Dispatchers.Main) { loading = false }
            }
        }
    }

    private fun reloadDay() {
        loading = true
        val day = selectedDay.format(dayFmt)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val arr = api.callArray("adminList", JSONArray().put(day).put(day))
                val list = mutableListOf<JSONObject>()
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(list::add)
                withContext(Dispatchers.Main) {
                    bookings = list
                    lastSync = java.time.LocalTime.now(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
                    val id = pendingAlertBookingId
                    if (!id.isNullOrBlank()) {
                        selectedBooking = list.firstOrNull { it.optString("id") == id }
                        pendingAlertBookingId = null
                    }
                }
            } catch (_: ApiClient.AuthException) {
                withContext(Dispatchers.Main) { openLogin() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast(e.message ?: "Không tải được lịch.") }
            } finally {
                withContext(Dispatchers.Main) { loading = false }
            }
        }
    }

    private fun loadMembers(query: String = "", loadMore: Boolean = false) {
        if (loading && loadMore) return
        loading = true
        val page = if (loadMore) memberPage + 1 else 1
        val requested = if (loadMore) memberQuery else query
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = api.call("adminLoyaltyList", JSONArray().put(page).put(requested))
                val arr = result.optJSONArray("items") ?: JSONArray()
                val list = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                withContext(Dispatchers.Main) {
                    members = if (loadMore) (members + list).distinctBy { it.optString("cardId") } else list
                    memberTotal = result.optInt("total", list.size)
                    memberPage = result.optInt("page", page)
                    memberQuery = requested
                }
            } catch (_: ApiClient.AuthException) {
                withContext(Dispatchers.Main) { openLogin() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast(e.message ?: "Không tải được khách hàng.") }
            } finally {
                withContext(Dispatchers.Main) { loading = false }
            }
        }
    }

    private fun effectiveTier(card: JSONObject): String {
        val saved = card.optString("vipTier")
        if (saved in listOf("standard", "silver", "gold", "vip")) return saved
        return when (card.optInt("totalVisits")) {
            in 0..4 -> "standard"
            in 5..14 -> "silver"
            in 15..29 -> "gold"
            else -> "vip"
        }
    }

    private fun mutateMember(action: String, args: JSONArray, success: String, returnToList: Boolean = false) {
        loading = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                api.call(action, args)
                withContext(Dispatchers.Main) {
                    if (returnToList) selectedMember = null
                    memberRefresh++
                    loadMembers(memberQuery)
                    toast(success)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast((e.message ?: "Chưa xác nhận được thay đổi.") +
                        " Kiểm tra lại trên website trước khi thử lại.")
                }
            } finally {
                withContext(Dispatchers.Main) { loading = false }
            }
        }
    }

    private fun mutate(action: String, args: JSONArray, success: String) {
        loading = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                api.call(action, args)
                withContext(Dispatchers.Main) {
                    toast(success)
                    selectedBooking = null
                    reloadDay()
                }
            } catch (_: ApiClient.AuthException) {
                withContext(Dispatchers.Main) { openLogin() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast(e.message ?: "Chưa hoàn tất.") }
            } finally {
                withContext(Dispatchers.Main) { loading = false }
            }
        }
    }

    private fun openLogin() {
        authenticated = false
        BookingAlerts.markLoggedOut(this)
        startActivityForResult(Intent(this, LoginActivity::class.java), 4101)
    }

    private fun handleBookingAlert(next: Intent?) {
        if (next?.getBooleanExtra("hn_from_booking_alert", false) != true) return
        val date = next.getStringExtra("hn_booking_date")
        val bookingId = next.getStringExtra("hn_booking_id")
        val parsed = runCatching { LocalDate.parse(date) }.getOrNull()
        if (parsed != null) selectedDay = parsed
        if (!bookingId.isNullOrBlank() && !bookingId.startsWith("summary_") && !bookingId.startsWith("test_")) {
            pendingAlertBookingId = bookingId
        }
        tab = 1
        screen = "main"
        selectedBooking = null
        next.removeExtra("hn_from_booking_alert")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleBookingAlert(intent)
        if (authenticated) reloadDay()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 4103) {
            authenticating = false
            if (resultCode == Activity.RESULT_OK) {
                deviceUnlocked = true
                val callback = pendingDeviceUnlock
                pendingDeviceUnlock = null
                callback?.invoke()
            } else pendingDeviceUnlock = null
            return
        }
        val scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scan != null) {
            scan.contents?.let {
                val phone = normalizePhone(it)
                if (phone == null) toast("Mã QR không hợp lệ.")
                else {
                    selectedMember = phone
                    tab = 3
                }
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 4101 && resultCode == Activity.RESULT_OK) {
            authenticated = true
            BookingAlerts.start(this)
            BookingAlerts.requestPermissionIfNeeded(this)
            lifecycleScope.launch(Dispatchers.IO) {
                try { BookingAlerts.checkNow(this@MainActivity) } catch (_: Exception) {}
            }
            reloadDay()
        }
    }

    private fun startQr() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 4102)
            return
        }
        IntentIntegrator(this).apply {
            setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            setPrompt("Quét mã HouseNail")
            setBeepEnabled(true)
            setOrientationLocked(false)
            initiateScan()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 4102 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startQr()
        if (requestCode == BookingAlerts.PERMISSION_CODE &&
            results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            toast("Đã bật thông báo lịch hẹn mới.")
        }
    }

    @Composable
    private fun HouseNailV10() {
        val p = palette(themeName)
        val dark = themeName == "obsidian"

        SideEffect {
            window.statusBarColor = p.paper.toArgbCompat()
            window.navigationBarColor = p.surface.toArgbCompat()
        }

        MaterialTheme(
            colorScheme = if (dark) darkColorScheme(
                background = p.paper,
                surface = p.surface,
                primary = p.accent,
                onBackground = p.ink,
                onSurface = p.ink
            ) else lightColorScheme(
                background = p.paper,
                surface = p.surface,
                primary = p.accent,
                onBackground = p.ink,
                onSurface = p.ink
            )
        ) {
            Surface(Modifier.fillMaxSize(), color = p.paper) {
                if (lockEnabled && !deviceUnlocked) {
                    DeviceLockScreen(p)
                } else if (!authenticated) {
                    LoginWaiting(p)
                } else {
                    Scaffold(
                        containerColor = p.paper,
                        topBar = { V10TopBar(p) },
                        bottomBar = { V10TabBar(p) }
                    ) { pad ->
                        Box(Modifier.padding(pad).fillMaxSize()) {
                            when {
                                selectedBooking != null -> BookingDetail(p, selectedBooking!!)
                                selectedMember != null -> MemberDetail(p, selectedMember!!)
                                screen == "booking-control" -> BookingControlScreen(p)
                                screen == "website-access" -> WebsiteAccessScreen(p)
                                screen == "services" -> ServiceVisibilityScreen(p)
                                screen == "waitlist" -> WaitlistScreen(p)
                                screen == "history" -> BookingHistory(p)
                                screen == "reports" -> Reports(p)
                                screen == "insights" -> Insights(p)
                                screen == "diagnostics" -> Diagnostics(p)
                                screen == "sync" -> SyncCenter(p)
                                tab == 0 -> Dashboard(p)
                                tab == 1 -> Appointments(p)
                                tab == 2 -> QuickScan(p)
                                tab == 3 -> Customers(p)
                                else -> MoreScreen(p)
                            }
                            if (loading) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                                    color = p.accent
                                )
                            }
                        }
                    }
                }
                if (editorOpen) BookingEditor(p)
            }
        }
    }

    @Composable
    private fun LoginWaiting(p: Palette) {
        Box(Modifier.fillMaxSize().background(p.paper), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Icon(Icons.Default.CalendarMonth, null, tint = p.accent, modifier = Modifier.size(58.dp))
                Text("HOUSE NAIL", fontSize = 30.sp, fontFamily = FontFamily.Serif, letterSpacing = 4.sp, color = p.ink)
                CircularProgressIndicator(color = p.accent)
                Text("Đang khôi phục đăng nhập…", color = p.secondary)
            }
        }
    }

    @Composable
    private fun V10TopBar(p: Palette) {
        val title = when {
            selectedBooking != null -> "Chi tiết lịch"
            selectedMember != null -> "Thẻ khách"
            screen == "reports" -> if(reportSelected==null)"Báo cáo tuần · tháng · năm" else reportSelected!!
            screen == "insights" -> if(insightSelected==null)"Khách hàng 360°" else insightSelected!!
            screen == "diagnostics" -> "Chẩn đoán kết nối"
            screen == "sync" -> "Trung tâm đồng bộ"
            tab == 0 -> "Tổng quan"
            tab == 1 -> "Lịch hẹn"
            tab == 2 -> "Quét QR"
            tab == 3 -> "Khách hàng"
            else -> "Thêm"
        }
        CenterAlignedTopAppBar(
            title = { Text(title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                if (selectedBooking != null || selectedMember != null || screen != "main") {
                    IconButton(onClick = {
                        when {
                            selectedBooking != null -> selectedBooking = null
                            selectedMember != null -> selectedMember = null
                            reportSelected != null -> reportSelected = null
                            insightSelected != null -> insightSelected = null
                            else -> screen = "main"
                        }
                    }) { Icon(Icons.Default.ArrowBack, "Quay lại") }
                }
            },
            actions = {
                if (tab == 1 && selectedBooking == null) {
                    IconButton(onClick = { openBookingEditor() }) { Icon(Icons.Default.AddCircle, "Thêm lịch") }
                }
                IconButton(onClick = { if (tab == 3) loadMembers() else reloadDay() }) {
                    Icon(Icons.Default.Refresh, "Đồng bộ lại")
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = p.paper)
        )
    }

    @Composable
    private fun V10TabBar(p: Palette) {
        val labels = listOf("Tổng quan", "Lịch hẹn", "Quét QR", "Khách hàng", "Thêm")
        val icons = listOf(Icons.Default.GridView, Icons.Default.CalendarMonth, Icons.Default.QrCodeScanner, Icons.Default.Groups, Icons.Default.Tune)
        Surface(color = p.surface, shadowElevation = 6.dp) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().height(61.dp)
                    .border(.5.dp, p.border),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                labels.forEachIndexed { i, label ->
                    val active = tab == i && selectedBooking == null && selectedMember == null && screen == "main"
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clickable {
                            tab = i
                            screen = "main"
                            selectedBooking = null
                            selectedMember = null
                            if (i == 3 && members.isEmpty()) loadMembers()
                        }.padding(top = 8.dp, bottom = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(icons[i], label, modifier = Modifier.size(22.dp), tint = if (active) p.accent else p.secondary)
                        Text(label, fontSize = 10.sp, maxLines = 1, color = if (active) p.accent else p.secondary, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
    }

        @Composable
    private fun Dashboard(p: Palette) {
        val active = bookings.filter { !it.cancelled() }
        val waiting = active.filter { !it.completed() && !it.noShow() }
        val completed = active.count { it.completed() && !it.noShow() }
        val cancelled = bookings.count { it.cancelled() }
        val noShows = active.count { it.noShow() }
        val upcoming = waiting.sortedBy { it.optString("time") }.take(4)
        val phone = active.count { it.optString("bookingSource") == "phone" }
        val web = active.size - phone

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 19.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            item { VIPHero(p) }
            item { VIPPanel(p) { DaySelector(p); Spacer(Modifier.height(10.dp)); HorizontalDivider(color = p.border); Spacer(Modifier.height(10.dp)); SyncBanner(p) } }

            item {
                SectionTitle(p, "Nhịp làm việc", "Số liệu thực cho ngày đang chọn · chạm thẻ để xem danh sách")
                Spacer(Modifier.height(13.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    MetricTile(p, "Tổng lịch", bookings.size, Icons.Default.CalendarMonth, p.accent, Modifier.weight(1f)) { bookingFilter = "Tất cả"; tab = 1 }
                    MetricTile(p, "Đang chờ", waiting.size, Icons.Default.Schedule, Color(0xFFA6614A), Modifier.weight(1f)) { bookingFilter = "Đang chờ"; tab = 1 }
                }
                Spacer(Modifier.height(11.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    MetricTile(p, "Hoàn thành", completed, Icons.Default.Verified, Color(0xFF488C70), Modifier.weight(1f)) { bookingFilter = "Hoàn thành"; tab = 1 }
                    MetricTile(p, "Đã hủy", cancelled, Icons.Default.EventBusy, Color(0xFFAA5C6E), Modifier.weight(1f)) { bookingFilter = "Đã hủy"; tab = 1 }
                }
                TextButton(onClick = { bookingFilter = "Không đến"; tab = 1 }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PersonOff, null, tint = p.secondary)
                    Spacer(Modifier.width(6.dp))
                    Text("$noShows lịch không đến", color = p.secondary)
                    Spacer(Modifier.weight(1f))
                    Text("Xem", color = p.accent, fontWeight = FontWeight.SemiBold)
                }
            }

            item {
                SectionTitle(p, "Một chạm, xong ngay", "Thao tác quản lý được sử dụng nhiều nhất")
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionTile(p, "Thêm lịch", Icons.Default.EventAvailable, true, Modifier.weight(1f)) { openBookingEditor() }
                    ActionTile(p, "Quét QR", Icons.Default.QrCodeScanner, false, Modifier.weight(1f)) { startQr() }
                    ActionTile(p, "Khách hàng", Icons.Default.Groups, false, Modifier.weight(1f)) { tab = 3; loadMembers() }
                }
            }

            item {
                SectionTitle(p, "Lịch sắp tới", "Các cuộc hẹn chưa hoàn thành")
                Spacer(Modifier.height(12.dp))
                VIPPanel(p) {
                    if (upcoming.isEmpty()) {
                        EmptyBlock(p, "Không có lịch sắp tới", "Chọn ngày khác hoặc tạo lịch hẹn mới.")
                    } else {
                        upcoming.forEachIndexed { index, b ->
                            UpcomingRow(p, b)
                            if (index != upcoming.lastIndex) HorizontalDivider(color = p.border)
                        }
                    }
                }
            }

            item {
                SectionTitle(p, "Nguồn khách", "Phân bổ lịch đang hoạt động trong ngày")
                Spacer(Modifier.height(12.dp))
                VIPPanel(p) {
                    StatLine(p, "Đặt trực tuyến", web)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { if (active.isEmpty()) 0f else web.toFloat() / active.size }, color = p.accent)
                    Spacer(Modifier.height(16.dp))
                    StatLine(p, "Qua điện thoại", phone)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { if (active.isEmpty()) 0f else phone.toFloat() / active.size }, color = p.champagne)
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = p.border)
                    Spacer(Modifier.height(14.dp))
                    Row {
                        Text("Thời lượng dịch vụ", color = p.ink)
                        Spacer(Modifier.weight(1f))
                        Text(active.sumOf { durationMinutes(it) }.toString() + " phút", color = p.ink, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            item {
                TextButton(onClick={screen="reports"},modifier=Modifier.fillMaxWidth()){
                    Icon(Icons.Default.BarChart,null,tint=p.accent)
                    Spacer(Modifier.width(7.dp))
                    Text("Xem phân tích và báo cáo",color=p.accent,fontWeight=FontWeight.SemiBold)
                }
            }
            item {
                Text(
                    "HOUSE NAIL  •  PRIVATE STUDIO",
                    fontSize = 10.sp,
                    letterSpacing = 3.sp,
                    color = p.champagne,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                )
            }
        }
    }

    @Composable
    private fun VIPHero(p: Palette) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(29.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF2E2629), Color(0xFF5C4047))))
        ) {
            Canvas(Modifier.matchParentSize()) {
                val w = size.width
                val unit = 1.dp.toPx()
                drawCircle(Color.White.copy(alpha = .11f), radius = 95 * unit, center = Offset(w + 8 * unit, -8 * unit), style = Stroke(unit))
                drawCircle(p.champagne.copy(alpha = .28f), radius = 67.5f * unit, center = Offset(w - 28 * unit, 69 * unit), style = Stroke(unit))
            }
            Column(Modifier.padding(23.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("H O U S E N A I L", fontSize = 11.sp, fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, color = p.champagne)
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFFBFEBC4), modifier = Modifier.size(11.dp))
                        Text(if (lastSync != null) "ĐÃ KẾT NỐI" else "CHỜ ĐỒNG BỘ",
                            fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                            color = if (lastSync != null) Color(0xFFBFEBC4) else Color(0xFFFFC779))
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Studio quản lý", fontSize = 31.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, color = Color.White)
                    Text("Lịch hẹn, khách hàng và sự chăm sóc trong một nơi.", fontSize = 14.sp, color = Color.White.copy(alpha = .77f))
                }
                HorizontalDivider(color = p.champagne.copy(alpha = .4f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CalendarMonth, null, tint = Color.White.copy(alpha = .87f), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(selectedDay.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale("vi", "VN"))),
                        fontSize = 12.sp, color = Color.White.copy(alpha = .87f), modifier = Modifier.weight(1f))
                    Text("V10 VIP", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = .12f))
                            .padding(horizontal = 9.dp, vertical = 6.dp))
                }
            }
        }
    }

        @Composable
    private fun DaySelector(p: Palette) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selectedDay = selectedDay.minusDays(1); reloadDay() }) { Icon(Icons.Default.ChevronLeft, "Ngày trước", tint = p.ink) }
                Box(Modifier.weight(1f).clip(CircleShape).background(p.paper).clickable { chooseDate() }.padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text(selectedDay.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("vi", "VN"))), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = p.ink)
                }
                IconButton(onClick = { selectedDay = selectedDay.plusDays(1); reloadDay() }) { Icon(Icons.Default.ChevronRight, "Ngày sau", tint = p.ink) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, null, tint = p.secondary, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("Giờ Slovakia", fontSize = 12.sp, color = p.secondary)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { selectedDay = LocalDate.now(zone); reloadDay() }) { Text("Hôm nay", color = p.accent, fontWeight = FontWeight.SemiBold) }
            }
        }
    }

    private fun chooseDate() {
        DatePickerDialog(this, { _, year, month, day ->
            selectedDay = LocalDate.of(year, month + 1, day)
            reloadDay()
        }, selectedDay.year, selectedDay.monthValue - 1, selectedDay.dayOfMonth).show()
    }

    @Composable
    private fun SyncBanner(p: Palette) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = p.accent)
            } else {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (lastSync != null) p.accent else Color(0xFFFFA200)))
            }
            Spacer(Modifier.width(8.dp))
            Text(if (lastSync == null) "Đang kết nối dữ liệu" else "Đồng bộ lúc $lastSync",
                color = p.secondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { reloadDay() }, modifier = Modifier.size(28.dp), enabled = !loading) {
                Icon(Icons.Default.Refresh, "Đồng bộ lại", tint = p.accent, modifier = Modifier.size(18.dp))
            }
        }
    }


    private fun openBookingEditor(moving: JSONObject? = null) {
        editorRequestGeneration++
        editorMoving = moving
        editorDate = moving?.optString("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: selectedDay
        editorServiceId = moving?.optString("serviceId").orEmpty()
        editorServices = emptyList()
        editorSlots = emptyList()
        editorTime = ""
        editorName = ""
        editorPhone = ""
        editorEmail = ""
        editorNote = ""
        editorConsent = false
        editorError = ""
        editorBusy = false
        editorRequestId = UUID.randomUUID().toString()
        editorOpen = true
        if (moving == null) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val config = api.call("adminBookingConfig", JSONArray())
                    val rows = config.optJSONArray("services") ?: JSONArray()
                    val services = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                    withContext(Dispatchers.Main) {
                        if (editorOpen && editorMoving == null) editorServices = services
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { editorError = e.message ?: "Không tải được dịch vụ." }
                }
            }
        } else loadEditorSlots()
    }

    private fun loadEditorSlots() {
        val gen = ++editorRequestGeneration
        val service = editorServiceId
        val day = editorDate.format(dayFmt)
        editorTime = ""
        editorSlots = emptyList()
        if (service.isBlank()) { editorSlotsBusy = false; return }
        editorSlotsBusy = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val rows = api.callArray("adminAvailability", JSONArray().put(day).put(service))
                val slots = (0 until rows.length()).mapNotNull { rows.optString(it).takeIf(String::isNotBlank) }
                withContext(Dispatchers.Main) {
                    if (editorOpen && editorRequestGeneration == gen) {
                        editorSlots = slots
                        editorSlotsBusy = false
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (editorOpen && editorRequestGeneration == gen) {
                        editorError = e.message ?: "Không tải được giờ trống."
                        editorSlotsBusy = false
                    }
                }
            }
        }
    }

    private fun submitBookingEditor() {
        val moving = editorMoving
        val name = editorName.trim()
        val phone = editorPhone.trim()
        if (editorBusy || editorSlotsBusy || editorTime.isBlank()) return
        if (moving == null && (name.length < 2 || normalizePhone(phone) == null || !editorConsent)) {
            editorError = "Nhập tên và số điện thoại hợp lệ, đồng thời xác nhận khách đồng ý lưu thông tin."
            return
        }
        editorBusy = true
        editorError = ""
        val day = editorDate.format(dayFmt)
        val date = editorDate
        val time = editorTime
        val action: String
        val args: JSONArray
        if (moving != null) {
            action = "adminEdit"
            args = JSONArray().put(moving.optString("id")).put("move").put(day).put(time)
        } else {
            action = "adminCreateBooking"
            val payload = JSONObject().apply {
                put("serviceId", editorServiceId); put("date", day); put("time", time)
                put("name", name); put("phone", phone); put("email", editorEmail.trim())
                put("note", editorNote); put("guestCount", 1); put("privacyAccepted", editorConsent)
                put("website", ""); put("lang", "vi"); put("requestId", editorRequestId)
            }
            args = JSONArray().put(payload)
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                api.call(action, args)
                withContext(Dispatchers.Main) {
                    editorOpen = false
                    editorMoving = null
                    selectedBooking = null
                    selectedDay = date
                    reloadDay()
                    toast(if (moving == null) "Đã tạo lịch khách gọi đến." else "Đã đổi lịch hẹn.")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    editorError = (e.message ?: "Chưa xác nhận được thao tác.") +
                        " Kiểm tra lại danh sách trước khi thử lần nữa."
                }
            } finally {
                withContext(Dispatchers.Main) { editorBusy = false }
            }
        }
    }

    @Composable
    private fun BookingEditor(p: Palette) {
        val moving = editorMoving
        val canSave = !editorBusy && !editorSlotsBusy && editorTime.isNotBlank() &&
            (moving != null || (editorName.trim().length >= 2 && normalizePhone(editorPhone) != null && editorConsent))
        Dialog(onDismissRequest = { if (!editorBusy) editorOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.88f),
                shape = RoundedCornerShape(24.dp), color = p.paper) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (moving != null) "Đổi lịch" else "Thêm lịch", fontSize = 23.sp,
                            fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, color = p.ink,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { editorOpen = false }, enabled = !editorBusy) { Text("Đóng") }
                    }
                    HorizontalDivider(color = p.border)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Spacer(Modifier.height(4.dp))
                        Text("Lịch hẹn", fontWeight = FontWeight.SemiBold, color = p.ink)
                        if (moving != null) {
                            Text(moving.optString("service"), color = p.ink)
                            Text("Hiện tại: " + moving.optString("date") + " · " + moving.optString("time"),
                                fontSize = 12.sp, color = p.secondary)
                        } else {
                            Text("Chọn dịch vụ", fontSize = 12.sp, color = p.secondary)
                            if (editorServices.isEmpty() && editorError.isEmpty()) {
                                LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent)
                            }
                            editorServices.forEach { service ->
                                val id = service.optString("id")
                                val name = service.optString("vi").ifBlank { service.optString("sk") }
                                FilterChip(
                                    selected = editorServiceId == id,
                                    onClick = { editorServiceId = id; loadEditorSlots() },
                                    label = { Text(name + " · " + service.optInt("minutes") + " phút") }
                                )
                            }
                        }
                        OutlinedButton(onClick = {
                            DatePickerDialog(this@MainActivity, { _, year, month, dayOfMonth ->
                                editorDate = LocalDate.of(year, month + 1, dayOfMonth)
                                loadEditorSlots()
                            }, editorDate.year, editorDate.monthValue - 1, editorDate.dayOfMonth).show()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.CalendarMonth, null)
                            Spacer(Modifier.width(8.dp))
                            Text(editorDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")))
                        }
                        Text("Giờ trống từ website", color = p.secondary, fontSize = 12.sp)
                        if (editorSlotsBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (editorSlots.isEmpty() && !editorSlotsBusy) Text("Chưa có giờ trống cho dịch vụ và ngày đã chọn.", fontSize = 12.sp, color = p.secondary)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            editorSlots.forEach { slot ->
                                FilterChip(selected = editorTime == slot, onClick = { editorTime = slot },
                                    label = { Text(slot) })
                            }
                        }
                        TextButton(onClick = { loadEditorSlots() }) { Text("Tải lại giờ trống") }
                        if (moving == null) {
                            OutlinedTextField(editorName, { editorName = it }, label = { Text("Tên khách") },
                                singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(editorPhone, { editorPhone = it }, label = { Text("Số điện thoại") },
                                singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(editorEmail, { editorEmail = it }, label = { Text("Email (không bắt buộc)") },
                                singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(editorNote, { editorNote = it }, label = { Text("Ghi chú") },
                                maxLines = 4, modifier = Modifier.fillMaxWidth())
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { editorConsent = !editorConsent }) {
                                Checkbox(checked = editorConsent, onCheckedChange = { editorConsent = it })
                                Text("Khách đồng ý lưu thông tin đặt lịch", fontSize = 12.sp, color = p.ink)
                            }
                        }
                        if (editorError.isNotBlank()) Text(editorError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                    HorizontalDivider(color = p.border)
                    Button(onClick = { submitBookingEditor() }, enabled = canSave,
                        modifier = Modifier.fillMaxWidth()) {
                        if (editorBusy) CircularProgressIndicator(Modifier.size(16.dp),
                            strokeWidth = 2.dp, color = Color.White)
                        else Text(if (moving != null) "Lưu giờ hẹn mới" else "Tạo lịch hẹn")
                    }
                }
            }
        }
    }

    @Composable
    private fun Appointments(p: Palette) {
        val filtered = bookings.filter { b ->
            val ok = when (bookingFilter) {
                "Đang chờ" -> !b.cancelled() && !b.completed() && !b.noShow()
                "Hoàn thành" -> !b.cancelled() && b.completed() && !b.noShow()
                "Không đến" -> !b.cancelled() && b.noShow()
                "Đã hủy" -> b.cancelled()
                else -> true
            }
            val hay = listOf(b.optString("name"), b.optString("phone"), b.optString("service")).joinToString(" ").lowercase()
            ok && (bookingSearch.isBlank() || hay.contains(bookingSearch.lowercase()))
        }.sortedWith(compareBy<JSONObject> { it.optString("time") }.thenBy { displayName(it) })
        val names = listOf("Tất cả", "Đang chờ", "Hoàn thành", "Không đến", "Đã hủy")
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                VIPPanel(p) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("LỊCH HẸN · HOUSE NAIL", fontSize = 10.sp, letterSpacing = 2.4.sp,
                                color = p.accent, fontWeight = FontWeight.SemiBold)
                            Text("Mỗi lịch, một sự chăm sóc", fontSize = 22.sp,
                                fontFamily = FontFamily.Serif, color = p.ink)
                        }
                        Icon(Icons.Default.Event, null, tint = p.champagne, modifier = Modifier.size(26.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    DaySelector(p)
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = p.border)
                    Spacer(Modifier.height(10.dp))
                    SyncBanner(p)
                }
            }
            item {
                SectionTitle(p, "Trạng thái lịch", "Chọn để xem đúng những lịch cần xử lý")
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    names.forEach { name ->
                        val count = when(name) {
                            "Đang chờ" -> bookings.count { !it.cancelled() && !it.completed() && !it.noShow() }
                            "Hoàn thành" -> bookings.count { !it.cancelled() && it.completed() && !it.noShow() }
                            "Không đến" -> bookings.count { !it.cancelled() && it.noShow() }
                            "Đã hủy" -> bookings.count { it.cancelled() }
                            else -> bookings.size
                        }
                        val selected = bookingFilter == name
                        Row(Modifier.clip(CircleShape).background(if(selected) Color(0xFF5C4047) else p.surface)
                            .border(.8.dp, p.border, CircleShape).clickable { bookingFilter=name }
                            .padding(horizontal = 13.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(name, fontSize = 13.sp, color = if(selected) Color.White else p.ink)
                            Text(count.toString(), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                color = if(selected) Color.White else p.ink)
                        }
                    }
                }
            }
            item {
                OutlinedTextField(value = bookingSearch, onValueChange = { bookingSearch = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Tên khách, số điện thoại hoặc dịch vụ") },
                    leadingIcon = { Icon(Icons.Default.Search, null) })
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(filtered.size.toString() + " lịch phù hợp", fontSize = 17.sp,
                        fontFamily = FontFamily.Serif, color = p.ink, modifier = Modifier.weight(1f))
                    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(p.surface),
                        horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                        listOf("Timeline" to true, "Danh sách" to false).forEach { (name, mode) ->
                            Text(name, fontSize = 12.sp,
                                color = if(timelineMode==mode) Color.White else p.ink,
                                modifier = Modifier.clip(RoundedCornerShape(9.dp))
                                    .background(if(timelineMode==mode) Color(0xFF5C4047) else p.surface)
                                    .clickable { timelineMode=mode }
                                    .padding(horizontal = 10.dp, vertical = 9.dp))
                        }
                    }
                }
            }
            if (filtered.isEmpty()) item {
                VIPPanel(p) { EmptyBlock(p, "Chưa có lịch phù hợp", "Đổi bộ lọc, chọn ngày khác hoặc tạo lịch mới.") }
            }
            items(filtered, key = { it.optString("id") }) { b -> BookingCard(p, b) }
            item {
                Text(if(lastSync == null) "Đang kết nối dữ liệu" else "Đồng bộ lúc " + lastSync,
                    color = p.secondary, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
    }

    @Composable
    private fun BookingCard(p: Palette, b: JSONObject) {
        VIPPanel(p) {
            if(timelineMode) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(
                        if(b.cancelled()) p.secondary else if(b.completed()) Color(0xFF478C70) else if(b.noShow()) Color.Red else p.accent))
                    Text(b.optString("time") + " – " + b.optString("endTime"),
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = p.accent, modifier = Modifier.weight(1f))
                    Text(if(b.cancelled()) "Đã hủy" else if(b.completed()) "Hoàn thành" else if(b.noShow()) "Không đến" else "Đã đặt",
                        fontSize = 11.sp, color = p.accent)
                }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = p.border)
                Spacer(Modifier.height(10.dp))
            }
            Row(Modifier.clickable { selectedBooking = b }, horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top) {
                Column(Modifier.width(60.dp).clip(RoundedCornerShape(14.dp))
                        .background(p.accent.copy(alpha=.08f)).padding(vertical=12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(b.optString("time"), fontSize = 19.sp, fontWeight = FontWeight.Bold, color=p.accent)
                    Box(Modifier.width(1.dp).height(22.dp).background(p.accent.copy(alpha=.35f)))
                    Text(b.optString("endTime"), fontSize=12.sp, color=p.accent)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(displayName(b), fontSize = 16.sp, fontWeight=FontWeight.SemiBold, color = p.ink)
                    Text(b.optString("service"), fontSize=14.sp, color=p.secondary)
                    StatusPill(p, b)
                    b.optString("phone").takeIf(String::isNotBlank)?.let {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Icon(Icons.Default.Phone, null, modifier=Modifier.size(13.dp), tint=p.secondary)
                            Spacer(Modifier.width(4.dp))
                            Text(it, fontSize=12.sp, color=p.secondary)
                        }
                    }
                    Text(durationMinutes(b).toString() + " phút · " + b.optInt("guestCount",1)+" khách",
                        fontSize=12.sp,color=p.secondary)
                    Text(if (b.optString("bookingSource")=="phone") "☎ Khách gọi đến" else "◎ Khách đặt web",
                        fontSize=12.sp, color=p.secondary)
                    b.optString("note").takeIf(String::isNotBlank)?.let {
                        Text(it, fontSize=12.sp, maxLines=2, color=p.accent)
                    }
                }
                Icon(Icons.Default.ChevronRight, null, tint=p.secondary, modifier=Modifier.size(16.dp))
            }
            val phone = normalizePhone(b.optString("phone"))
            if(phone!=null) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color=p.border)
                TextButton(onClick={ selectedMember=phone; tab=3 }) {
                    Icon(Icons.Default.CreditCard, null, modifier=Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Tích điểm / Quản lý thẻ", fontSize=12.sp)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.ChevronRight, null, modifier=Modifier.size(14.dp))
                }
            }
        }
    }

    @Composable
    private fun BookingDetail(p: Palette, b: JSONObject) {
        var confirmCancel by remember(b.optString("id")) { mutableStateOf(false) }
        var confirmOutcome by remember(b.optString("id")) { mutableStateOf(false) }
        val ended = runCatching {
            val date = LocalDate.parse(b.optString("date"))
            val end = LocalTime.parse(b.optString("endTime"))
            !date.atTime(end).atZone(zone).toInstant().isAfter(java.time.Instant.now())
        }.getOrDefault(false)
        val editable = !b.cancelled() && !b.completed()
        LazyColumn(contentPadding=PaddingValues(16.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item {
                VIPPanel(p) {
                    Text(displayName(b), fontSize=22.sp, fontFamily=FontFamily.Serif,
                        fontWeight=FontWeight.SemiBold, color=p.ink)
                    Spacer(Modifier.height(10.dp))
                    LabelLine(p, "Dịch vụ", b.optString("service"))
                    LabelLine(p, "Ngày", b.optString("date")+" · "+b.optString("time")+"–"+b.optString("endTime"))
                    LabelLine(p, "Số khách", b.optInt("guestCount",1).toString())
                }
            }
            item {
                VIPPanel(p) {
                    Text("Thông tin khách", fontWeight=FontWeight.SemiBold, color=p.ink)
                    Spacer(Modifier.height(10.dp))
                    b.optString("phone").takeIf(String::isNotBlank)?.let {
                        LabelLine(p, "Điện thoại", it)
                        TextButton(onClick={ startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL,
                            android.net.Uri.parse("tel:"+it))) }) { Text("Gọi khách") }
                        val normalized=normalizePhone(it)
                        if(normalized!=null) TextButton(onClick={selectedMember=normalized;tab=3}) {
                            Text("Mở thẻ khách")
                        }
                    }
                    b.optString("email").takeIf(String::isNotBlank)?.let { LabelLine(p, "Email", it) }
                    b.optString("note").takeIf(String::isNotBlank)?.let { Text(it,color=p.secondary) }
                }
            }
            if(editable) item {
                VIPPanel(p) {
                    Text("Quản lý lịch", fontWeight=FontWeight.SemiBold, color=p.ink)
                    Spacer(Modifier.height(9.dp))
                    TextButton(onClick={openBookingEditor(b)}) { Text("Đổi ngày / giờ") }
                    TextButton(onClick={confirmOutcome=true}, enabled=ended || b.noShow()) {
                        Text(if(b.noShow()) "Bỏ đánh dấu không đến" else "Đánh dấu khách không đến")
                    }
                    if(!ended) Text("Chỉ đánh dấu không đến sau khi giờ hẹn kết thúc.",
                        color=p.secondary,fontSize=12.sp)
                    TextButton(onClick={confirmCancel=true}) {
                        Text("Hủy lịch hẹn",color=MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if(confirmCancel) AlertDialog(onDismissRequest={confirmCancel=false},
            title={Text("Hủy lịch hẹn?")},
            text={Text("Hủy lịch của "+displayName(b)+" lúc "+b.optString("time")+"?")},
            confirmButton={TextButton(onClick={
                confirmCancel=false
                mutate("adminEdit", JSONArray().put(b.optString("id")).put("cancel").put(JSONObject.NULL).put(JSONObject.NULL),"Đã hủy lịch hẹn.")
            }){Text("Xác nhận hủy",color=MaterialTheme.colorScheme.error)}},
            dismissButton={TextButton(onClick={confirmCancel=false}){Text("Không")}})
        if(confirmOutcome) AlertDialog(onDismissRequest={confirmOutcome=false},
            title={Text("Cập nhật trạng thái khách?")},
            text={Text(if(b.noShow()) "Bỏ đánh dấu không đến cho lịch này?" else "Xác nhận khách không đến sau giờ hẹn?")},
            confirmButton={TextButton(onClick={
                confirmOutcome=false
                mutate("adminSetBookingOutcome",JSONArray().put(b.optString("id")).put(if(b.noShow()) JSONObject.NULL else "no_show"),"Đã cập nhật trạng thái khách.")
            }){Text("Xác nhận")}},
            dismissButton={TextButton(onClick={confirmOutcome=false}){Text("Đóng")}})
    }

    @Composable
    private fun QuickScan(p: Palette) {
        LazyColumn(contentPadding = PaddingValues(horizontal = 19.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            item {
                Text("H O U S E  N A I L", fontSize = 11.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, color = p.accent)
                Spacer(Modifier.height(9.dp))
                Text("Quét thẻ & chăm sóc", fontSize = 29.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, color = p.ink)
                Text("Mở hồ sơ thành viên nhanh chóng, chính xác và bảo mật.", fontSize = 14.sp, color = p.secondary)
            }
            item {
                Box(
                    Modifier.fillMaxWidth().height(310.dp).clip(RoundedCornerShape(29.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF2E2629), Color(0xFF5C4047))))
                        .border(1.dp, p.champagne.copy(alpha = .53f), RoundedCornerShape(29.dp)).clickable { startQr() },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.size(180.dp).border(1.dp, p.champagne.copy(alpha = .72f), RoundedCornerShape(29.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.QrCodeScanner, null, tint = p.champagne, modifier = Modifier.size(96.dp))
                        }
                        Text("QR EXPRESS", fontSize = 11.sp, letterSpacing = 4.sp, color = p.champagne)
                        Text("CHẠM ĐỂ MỞ CAMERA", fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, color = Color.White)
                        Text("Quét mã HouseNail", color = Color.White.copy(alpha = .76f))
                    }
                }
            }
            item {
                SectionTitle(p, "Quy trình chăm sóc", "Ba bước, không làm thay đổi dữ liệu khi chỉ quét thẻ")
                Spacer(Modifier.height(12.dp))
                VIPPanel(p) {
                    StepLine(p, "01", "Quét mã QR", "Đưa thẻ HouseNail vào khung camera")
                    HorizontalDivider(color = p.border)
                    StepLine(p, "02", "Kiểm tra thành viên", "Xem hạng VIP, số lượt và lịch sử")
                    HorizontalDivider(color = p.border)
                    StepLine(p, "03", "Xác nhận thao tác", "Chỉ ghi lượt khi bạn chủ động xác nhận")
                }
            }
        }
    }

    @Composable
    private fun Customers(p: Palette) {
        var query by remember { mutableStateOf("") }
        var cardFilter by remember { mutableStateOf("Tất cả") }
        LaunchedEffect(Unit) { if (members.isEmpty()) loadMembers() }
        val visibleMembers = members.filter { card ->
            when(cardFilter) {
                "VIP" -> effectiveTier(card) == "vip"
                "Sắp đủ lượt" -> card.optString("lockStatus") != "locked" && card.optInt("cycleVisits") >= 4
                "Hoạt động" -> card.optString("lockStatus") != "locked"
                "Đã khóa" -> card.optString("lockStatus") == "locked"
                else -> true
            }
        }
        val vipLoaded = members.count { effectiveTier(it) == "vip" }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                VIPPanel(p) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("PRIVATE MEMBERSHIP", fontSize = 10.sp,
                                letterSpacing = 2.5.sp, color = p.accent, fontWeight = FontWeight.SemiBold)
                            Text("Khách hàng thân thiết", fontFamily = FontFamily.Serif, fontSize = 23.sp,
                                fontWeight = FontWeight.SemiBold, color = p.ink)
                            Text("Hồ sơ, hạng VIP và hành trình tích điểm", fontSize = 14.sp, color = p.secondary)
                        }
                        Icon(Icons.Default.WorkspacePremium, null, tint = p.champagne, modifier=Modifier.size(30.dp))
                    }
                    Spacer(Modifier.height(15.dp))
                    HorizontalDivider(color = p.border)
                    Spacer(Modifier.height(15.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(members.size.toString() + "/" + memberTotal,
                                fontSize=23.sp, fontWeight=FontWeight.Bold, color=p.ink)
                            Text("Thẻ đã tải / tổng",fontSize=12.sp,color=p.secondary)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(vipLoaded.toString(),fontSize=23.sp,fontWeight=FontWeight.Bold,color=p.ink)
                            Text("VIP trong thẻ đã tải",fontSize=12.sp,color=p.secondary)
                        }
                    }
                    Spacer(Modifier.height(15.dp))
                    Button(onClick = { startQr() }, modifier=Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.QrCodeScanner, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Quét QR mở thẻ khách")
                    }
                }
            }
            item {
                SectionTitle(p, "Tìm thành viên", "Tìm tên trong danh sách hoặc nhập số điện thoại để mở thẻ")
                Spacer(Modifier.height(10.dp))
                VIPPanel(p) {
                    OutlinedTextField(value=query, onValueChange={query=it},
                        modifier=Modifier.fillMaxWidth(), placeholder={Text("Tên hoặc số điện thoại")},
                        leadingIcon={Icon(Icons.Default.Search,null)},singleLine=true)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick={loadMembers(query)},modifier=Modifier.weight(1f)) {
                            Text("Tìm trong danh sách",fontSize=12.sp)
                        }
                        OutlinedButton(onClick={
                            val valid=normalizePhone(query)
                            if(valid==null)toast("Để mở trực tiếp, hãy nhập số điện thoại hợp lệ.")
                            else selectedMember=valid
                        }){Text("Mở thẻ",fontSize=12.sp)}
                    }
                }
            }
            item {
                SectionTitle(p,"Danh sách thẻ","Bộ lọc áp dụng cho những thẻ đã tải; có thể bấm Tải thêm")
                Spacer(Modifier.height(9.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("Tất cả","VIP","Sắp đủ lượt","Hoạt động","Đã khóa").forEach { name ->
                        val active=cardFilter==name
                        Text(name,fontSize=13.sp,
                            color=if(active) Color.White else p.ink,
                            modifier=Modifier.clip(CircleShape).background(if(active) Color(0xFF5C4047) else p.surface)
                                .border(.8.dp,p.border,CircleShape).clickable{cardFilter=name}
                                .padding(horizontal=13.dp,vertical=10.dp))
                    }
                }
            }
            if(visibleMembers.isEmpty() && !loading) item {
                VIPPanel(p){EmptyBlock(p,"Chưa có thẻ phù hợp","Thử bộ lọc khác hoặc tải thêm thẻ.")}
            }
            items(visibleMembers,key={it.optString("cardId")}) { card -> MemberCard(p,card) }
            if(members.size < memberTotal && !loading) item {
                OutlinedButton(onClick={loadMembers(loadMore=true)},modifier=Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.ExpandMore,null)
                    Spacer(Modifier.width(7.dp))
                    Text("Tải thêm thành viên")
                }
            }
            item {
                Text("Quét thẻ chỉ mở hồ sơ. Mọi thao tác cộng lượt, khóa thẻ hoặc hủy thẻ đều cần xác nhận riêng.",
                    color=p.secondary,fontSize=12.sp,modifier=Modifier.padding(vertical=10.dp))
            }
        }
    }

    @Composable
    private fun MemberCard(p: Palette, m: JSONObject) {
        VIPPanel(p, Modifier.clickable { selectedMember = m.optString("cardId") }) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(p.champagne.copy(alpha = .13f)), contentAlignment = Alignment.Center) {
                    Text(m.optString("name", "K").take(1).uppercase(), fontSize = 22.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, color = p.accent)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row {
                        Text(m.optString("name", "Khách hàng"), fontWeight = FontWeight.SemiBold, color = p.ink, modifier = Modifier.weight(1f))
                        Text(tierTitle(effectiveTier(m)).uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = p.accent)
                    }
                    Text(m.optString("cardId"), fontSize = 12.sp, color = p.secondary)
                    Row {
                        Text("Hành trình thưởng", fontSize = 12.sp, color = p.ink)
                        Spacer(Modifier.weight(1f))
                        Text(m.optInt("cycleVisits").toString() + "/5", fontSize = 12.sp, color = p.ink)
                    }
                    LinearProgressIndicator(progress = { m.optInt("cycleVisits").coerceIn(0, 5) / 5f }, color = p.champagne)
                    Row {
                        Text(m.optInt("totalVisits").toString() + " lượt đã ghi", fontSize = 12.sp, color = p.secondary)
                        Spacer(Modifier.weight(1f))
                        Text(if (m.optString("lockStatus") == "locked") "Đã khóa" else "Hoạt động", fontSize = 11.sp, color = if (m.optString("lockStatus") == "locked") Color.Red else p.accent)
                    }
                }
                Icon(Icons.Default.ChevronRight, null, tint = p.secondary)
            }
        }
    }

    @Composable
    private fun MemberDetail(p: Palette, phone: String) {
        var card by remember(phone) { mutableStateOf<JSONObject?>(null) }
        var noShowCount by remember(phone) { mutableStateOf<Int?>(null) }
        var count by remember(phone) { mutableIntStateOf(1) }
        var lockReason by remember(phone) { mutableStateOf("") }
        var confirmReward by remember(phone) { mutableStateOf(false) }
        var confirmDelete by remember(phone) { mutableStateOf(false) }
        var confirmLock by remember(phone) { mutableStateOf(false) }
        var confirmDuplicate by remember(phone) { mutableStateOf<JSONObject?>(null) }
        var bookingChoices by remember(phone) { mutableStateOf<List<JSONObject>>(emptyList()) }
        var feedback by remember(phone) { mutableStateOf("") }
        var busy by remember(phone) { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(phone, memberRefresh) {
            busy = true
            try {
                val results = withContext(Dispatchers.IO) {
                    api.callArray("adminLoyaltyFind", JSONArray().put(phone))
                }
                card = if (results.length() == 1) results.optJSONObject(0) else null
                feedback = if (results.length() > 1) "Có nhiều thẻ trùng số. Hãy xử lý trên website." else if(results.length()==0) "Không tìm thấy thẻ." else ""
                noShowCount = try {
                    val details = withContext(Dispatchers.IO) {
                        api.callArray("adminNoShowCustomerInfo", JSONArray().put(JSONArray().put(phone)))
                    }
                    details.optJSONObject(0)?.optInt("noShowCount")
                } catch (_: Exception) { null }
            } catch (e: Exception) {
                card = null
                feedback = e.message ?: "Không thể tải thông tin thẻ."
            } finally { busy = false }
        }

        fun addVisit(reward: Boolean, additionalBookingID: String? = null, selectedBookingID: String? = null) {
            val current = card ?: return
            if (busy) return
            busy = true
            feedback = ""
            scope.launch {
                try {
                    val args = JSONArray().put(current.optString("cardId")).put(reward).put(count)
                        .put(additionalBookingID != null)
                        .put(additionalBookingID ?: selectedBookingID ?: "")
                        .put(current.optInt("totalVisits"))
                    val result = withContext(Dispatchers.IO) { api.call("adminLoyaltyAddVisit", args) }
                    if (result.optBoolean("bookingSelectionRequired")) {
                        val choices = result.optJSONArray("choices") ?: JSONArray()
                        bookingChoices = (0 until choices.length()).mapNotNull { choices.optJSONObject(it) }
                        if (bookingChoices.isEmpty()) feedback = "Máy chủ yêu cầu chọn lịch nhưng không trả danh sách."
                    } else if (result.optBoolean("confirmationRequired")) {
                        confirmDuplicate = result
                    } else {
                        val verify = withContext(Dispatchers.IO) {
                            api.callArray("adminLoyaltyFind", JSONArray().put(phone))
                        }
                        val updated = verify.optJSONObject(0)
                        val before = current.optInt("totalVisits")
                        if (updated == null || updated.optInt("totalVisits") < before + count) {
                            feedback = "Máy chủ chưa xác nhận lượt tăng. Kiểm tra trên website trước khi thử lại."
                        } else {
                            card = updated
                            memberRefresh++
                            count = 1
                            feedback = "Đã cập nhật tiến độ " + updated.optInt("cycleVisits") + "/5."
                        }
                    }
                } catch (e: Exception) {
                    feedback = (e.message ?: "Chưa xác nhận được thao tác.") + " Kiểm tra lại trước khi thử lần nữa."
                } finally { busy = false }
            }
        }

        fun needsReward(c: JSONObject): Boolean {
            val old = c.optInt("cycleVisits")
            return (old + count) / 5 > old / 5
        }

        LazyColumn(contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if(busy) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color=p.accent) }
            if(feedback.isNotBlank()) item { Text(feedback,fontSize=12.sp,
                color=if(feedback.startsWith("Đã")) p.accent else MaterialTheme.colorScheme.error) }
            card?.let { c ->
                item { VIPMemberCard(p, c) }
                item {
                    VIPPanel(p) {
                        Text("Ghi nhận hoàn thành",fontWeight=FontWeight.SemiBold,color=p.ink)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text("Số lượt: " + count,color=p.ink,modifier=Modifier.weight(1f))
                            IconButton(onClick={ count=(count-1).coerceAtLeast(1) },enabled=count>1&&!busy){
                                Icon(Icons.Default.RemoveCircleOutline,null,tint=p.accent)
                            }
                            IconButton(onClick={ count=(count+1).coerceAtMost(5) },enabled=count<5&&!busy){
                                Icon(Icons.Default.AddCircleOutline,null,tint=p.accent)
                            }
                        }
                        Text("Thao tác này có thể đánh dấu lịch hôm nay hoàn thành theo quy tắc hiện có của website.",
                            fontSize=12.sp,color=p.secondary)
                        if(needsReward(c)) Text("Có lượt thứ 5: cần xác nhận đã giảm 50%.",
                            fontSize=12.sp,color=p.accent)
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick={ if(needsReward(c))confirmReward=true else addVisit(false) },
                            modifier=Modifier.fillMaxWidth(), enabled=!busy&&c.optString("lockStatus")!="locked"
                        ){Text("Tích điểm +" + count)}
                    }
                }
                item {
                    VIPPanel(p) {
                        Text("Hạng thành viên · website",fontWeight=FontWeight.SemiBold,color=p.ink)
                        Spacer(Modifier.height(10.dp))
                        LabelLine(p,"Hạng tự động",tierTitle(when(c.optInt("totalVisits")){
                            in 0..4 -> "standard"
                            in 5..14 -> "silver"
                            in 15..29 -> "gold"
                            else -> "vip"
                        }))
                        Text("Điều chỉnh hạng",fontSize=12.sp,color=p.secondary)
                        Row(Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            listOf("auto","standard","silver","gold","vip").forEach { tier ->
                                val selected=if(tier=="auto") c.optString("vipTier") !in listOf("standard","silver","gold","vip")
                                    else c.optString("vipTier")==tier
                                FilterChip(selected=selected,onClick={
                                    if(!busy) mutateMember("adminLoyaltySetVipTier",
                                        JSONArray().put(c.optString("cardId")).put(tier)
                                            .put(c.optString("vipTier").takeIf { it in listOf("standard","silver","gold","vip") } ?: "auto"),
                                        "Đã cập nhật hạng thành viên.")
                                },label={Text(if(tier=="auto")"Tự động" else tierTitle(tier))})
                            }
                        }
                        Text("Ngưỡng đề xuất: 0–4 Thường · 5–14 Trung bình · 15–29 Cao · 30+ VIP.",
                            fontSize=11.sp,color=p.secondary)
                    }
                }
                item {
                    VIPPanel(p) {
                        Text("Lịch sử thẻ",fontWeight=FontWeight.SemiBold,color=p.ink)
                        Spacer(Modifier.height(9.dp))
                        LabelLine(p,"Tổng lượt",c.optInt("totalVisits").toString())
                        LabelLine(p,"Đã dùng ưu đãi",c.optInt("rewardsRedeemed").toString())
                        noShowCount?.let { LabelLine(p,"Số lần không đến",it.toString()) }
                        LabelLine(p,"Trạng thái",if(c.optString("lockStatus")=="locked")"Đã khóa" else "Hoạt động")
                        c.optString("lockReason").takeIf(String::isNotBlank)?.let {
                            Text("Lý do: " + it,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
                        }
                    }
                }
                item {
                    VIPPanel(p) {
                        Text("Quản lý thẻ",fontWeight=FontWeight.SemiBold,color=p.ink)
                        Spacer(Modifier.height(8.dp))
                        if(c.optString("lockStatus")!="locked")
                            OutlinedTextField(lockReason,{lockReason=it},label={Text("Lý do khóa thẻ")},
                                modifier=Modifier.fillMaxWidth(),maxLines=3)
                        OutlinedButton(onClick={confirmLock=true},modifier=Modifier.fillMaxWidth(),
                            enabled=!busy&&(c.optString("lockStatus")=="locked"||lockReason.trim().isNotEmpty())) {
                            Text(if(c.optString("lockStatus")=="locked")"Mở khóa thẻ" else "Khóa thẻ")
                        }
                        TextButton(onClick={confirmDelete=true},modifier=Modifier.fillMaxWidth(),
                            enabled=!busy) {
                            Text("Hủy thẻ",color=MaterialTheme.colorScheme.error)
                        }
                    }
                }
                item { OutlinedButton(onClick={memberRefresh++},modifier=Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Refresh,null); Spacer(Modifier.width(6.dp)); Text("Tải lại thông tin thẻ")
                } }
            }
        }

        if(confirmReward) AlertDialog(onDismissRequest={confirmReward=false},
            title={Text("Ưu đãi 50%")},
            text={Text("Bạn đã thực sự áp dụng giảm giá cho khách chưa?")},
            confirmButton={TextButton(onClick={confirmReward=false;addVisit(true)}){Text("Đã giảm 50% · Ghi điểm")}},
            dismissButton={TextButton(onClick={confirmReward=false}){Text("Chưa giảm · Để sau")}})
        if(confirmDuplicate!=null) AlertDialog(onDismissRequest={confirmDuplicate=null},
            title={Text("Ghi thêm lượt?")},
            text={Text("Lịch này đã hoàn thành. Có chắc ghi thêm " + count + " lượt?")},
            confirmButton={TextButton(onClick={
                val id=confirmDuplicate?.optString("bookingId")
                confirmDuplicate=null
                if(!id.isNullOrBlank()) addVisit(card?.let(::needsReward)==true, additionalBookingID=id)
                else feedback="Máy chủ không trả mã lịch hợp lệ."
            }){Text("Ghi thêm lượt")}},
            dismissButton={TextButton(onClick={confirmDuplicate=null}){Text("Không ghi thêm")}})
        if(bookingChoices.isNotEmpty()) AlertDialog(onDismissRequest={bookingChoices=emptyList()},
            title={Text("Chọn lịch để ghi điểm")},
            text={Column { bookingChoices.forEach { choice ->
                TextButton(onClick={
                    val id=choice.optString("id")
                    bookingChoices=emptyList()
                    addVisit(card?.let(::needsReward)==true,selectedBookingID=id)
                }){Text(choice.optString("date")+" · "+choice.optString("time")+" · "+choice.optString("name"))}
            } }},
            confirmButton={TextButton(onClick={bookingChoices=emptyList()}){Text("Hủy")}})
        if(confirmLock) AlertDialog(onDismissRequest={confirmLock=false},
            title={Text(if(card?.optString("lockStatus")=="locked")"Mở khóa thẻ?" else "Khóa thẻ?")},
            text={Text("Thay đổi trạng thái thẻ sẽ được đồng bộ với website.")},
            confirmButton={TextButton(onClick={
                confirmLock=false
                val isLocked=card?.optString("lockStatus")=="locked"
                mutateMember("adminLoyaltySetLock",
                    JSONArray().put(phone).put(!isLocked).put(if(isLocked)"" else lockReason.trim()),
                    if(isLocked)"Đã mở khóa thẻ." else "Đã khóa thẻ.")
            }){Text("Xác nhận")}},
            dismissButton={TextButton(onClick={confirmLock=false}){Text("Không")}})
        if(confirmDelete) AlertDialog(onDismissRequest={confirmDelete=false},
            title={Text("Hủy thẻ vĩnh viễn?")},
            text={Text("Hủy thẻ của " + (card?.optString("name") ?: "khách") +
                " sẽ xóa tiến độ tích điểm. Lịch hẹn và lịch sử không đến vẫn được giữ.")},
            confirmButton={TextButton(onClick={
                confirmDelete=false
                mutateMember("adminLoyaltyDeleteCard",JSONArray().put(phone),"Đã hủy thẻ.",true)
            }){Text("Hủy thẻ vĩnh viễn",color=MaterialTheme.colorScheme.error)}},
            dismissButton={TextButton(onClick={confirmDelete=false}){Text("Không")}})
    }

    private fun loadReport(period: String, anchor: LocalDate) {
        reportBusy = true
        reportError = ""
        val start = when(period) {
            "Tuần" -> anchor.minusDays(anchor.dayOfWeek.value.toLong() - 1)
            "Năm" -> anchor.withDayOfYear(1)
            else -> anchor.withDayOfMonth(1)
        }
        val end = when(period) {
            "Tuần" -> start.plusDays(6)
            "Năm" -> start.plusYears(1).minusDays(1)
            else -> start.plusMonths(1).minusDays(1)
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val arr = api.callArray("adminList",JSONArray().put(start.format(dayFmt)).put(end.format(dayFmt)))
                val data = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                withContext(Dispatchers.Main) {
                    if (reportPeriod == period && reportAnchor == anchor) reportRows = data
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { reportError = e.message ?: "Không tải được báo cáo." }
            } finally { withContext(Dispatchers.Main) { reportBusy = false } }
        }
    }

    private fun loadInsights() {
        insightBusy = true
        insightError = ""
        val end = LocalDate.now(zone)
        val start = end.minusDays(90)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val arr = api.callArray("adminList",JSONArray().put(start.format(dayFmt)).put(end.format(dayFmt)))
                val data = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                withContext(Dispatchers.Main) { insightRows = data }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { insightError = e.message ?: "Không tải được báo cáo." }
            } finally { withContext(Dispatchers.Main) { insightBusy = false } }
        }
    }

    private fun reportFilter(name: String, rows: List<JSONObject>, insight: Boolean): List<JSONObject> {
        val complete = rows.filter { it.completed() && !it.cancelled() && !it.noShow() }
        val phoneGroups = complete.mapNotNull { b ->
            normalizePhone(b.optString("phone"))?.let { it to b }
        }.groupBy({it.first},{it.second})
        return when(name) {
            "Tổng lịch" -> rows
            "Hoàn thành","Lượt hoàn thành" -> complete
            "Đã hủy" -> rows.filter { it.cancelled() }
            "Không đến" -> rows.filter { it.noShow() && !it.cancelled() }
            "Chưa hoàn thành" -> rows.filter { !it.completed() && !it.cancelled() && !it.noShow() }
            "Khách duy nhất" -> phoneGroups.values.mapNotNull { it.firstOrNull() }
            "Lượt khách quay lại" -> complete.filter {
                val phone = normalizePhone(it.optString("phone"))
                phone != null && (phoneGroups[phone]?.size ?: 0) >= 2
            }
            "Khách vắng 30+ ngày" -> {
                val cutoff = LocalDate.now(zone).minusDays(30)
                phoneGroups.values.mapNotNull { group ->
                    group.maxByOrNull { it.optString("date") }
                }.filter { runCatching { LocalDate.parse(it.optString("date")) <= cutoff }.getOrDefault(false) }
            }
            else -> rows
        }
    }

    @Composable
    private fun ReportTile(p: Palette,title: String, rows: List<JSONObject>, modifier: Modifier, onClick: () -> Unit) {
        Column(modifier.clip(RoundedCornerShape(18.dp)).background(p.surface)
            .border(.8.dp,p.border,RoundedCornerShape(18.dp)).clickable(onClick=onClick)
            .padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Assessment,null,tint=p.accent)
            Text(rows.size.toString(),fontSize=28.sp,fontWeight=FontWeight.Bold,color=p.ink)
            Text(title,fontSize=12.sp,color=p.ink)
            Text("Xem danh sách ↗",fontSize=11.sp,color=p.secondary)
        }
    }

    @Composable
    private fun ServiceBreakdown(p: Palette, data: List<JSONObject>) {
        val services = data.groupBy{it.optString("service")}.map{it.key to it.value.size}
            .sortedByDescending{it.second}.take(8)
        if(services.isEmpty()) Text("Chưa có dữ liệu",color=p.secondary)
        else {
            val max=services.maxOf{it.second}.coerceAtLeast(1)
            services.forEach{ (name,n) ->
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Row { Text(name,fontSize=12.sp,color=p.ink,modifier=Modifier.weight(1f))
                        Text(n.toString(),fontSize=12.sp,color=p.accent) }
                    LinearProgressIndicator(progress={n.toFloat()/max},color=p.accent,modifier=Modifier.fillMaxWidth())
                    Spacer(Modifier.height(5.dp))
                }
            }
        }
    }

    @Composable
    private fun Reports(p: Palette) {
        LaunchedEffect(reportPeriod,reportAnchor,reportRefresh) { loadReport(reportPeriod,reportAnchor) }
        val categories=listOf("Tổng lịch","Hoàn thành","Đã hủy","Không đến","Chưa hoàn thành","Khách duy nhất")
        val completed=reportFilter("Hoàn thành",reportRows,false)
        val label=when(reportPeriod){
            "Tuần" -> reportAnchor.format(DateTimeFormatter.ofPattern("'Tuần' ww · MMMM yyyy",Locale("vi","VN")))
            "Năm" -> reportAnchor.year.toString()
            else -> reportAnchor.format(DateTimeFormatter.ofPattern("MMMM yyyy",Locale("vi","VN")))
        }
        LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            if(reportSelected!=null) {
                val rows=reportFilter(reportSelected!!,reportRows,false)
                items(rows,key={it.optString("id")}){BookingCard(p,it)}
            } else {
                item {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(p.surface).padding(6.dp),
                        horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                        listOf("Tuần","Tháng","Năm").forEach{ name ->
                            Text(name,color=if(name==reportPeriod)Color.White else p.ink,
                                textAlign=TextAlign.Center,fontSize=13.sp,
                                modifier=Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                    .background(if(name==reportPeriod)Color(0xFF5C4047)else p.surface)
                                    .clickable{reportPeriod=name}
                                    .padding(vertical=10.dp))
                        }
                    }
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        IconButton(onClick={
                            reportAnchor=when(reportPeriod){"Tuần"->reportAnchor.minusWeeks(1);"Năm"->reportAnchor.minusYears(1);else->reportAnchor.minusMonths(1)}
                        }){Icon(Icons.Default.ChevronLeft,"Kỳ trước")}
                        Text(label,fontWeight=FontWeight.SemiBold,color=p.ink,
                            modifier=Modifier.weight(1f),textAlign=TextAlign.Center)
                        IconButton(onClick={
                            reportAnchor=when(reportPeriod){"Tuần"->reportAnchor.plusWeeks(1);"Năm"->reportAnchor.plusYears(1);else->reportAnchor.plusMonths(1)}
                        }){Icon(Icons.Default.ChevronRight,"Kỳ tiếp")}
                    }
                    if(reportBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(reportError.isNotBlank()) Text(reportError,color=MaterialTheme.colorScheme.error)
                }
                categories.chunked(2).forEach{ chunk ->
                    item {
                        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            chunk.forEach{ key ->
                                val rows=reportFilter(key,reportRows,false)
                                ReportTile(p,key,rows,Modifier.weight(1f)){reportSelected=key}
                            }
                        }
                    }
                }
                item {
                    SectionTitle(p,"Xu hướng lịch hoàn thành")
                    VIPPanel(p) {
                        val counts=completed.groupBy{it.optString("date")}.toSortedMap()
                        if(counts.isEmpty()) Text("Chưa có lượt hoàn thành",color=p.secondary)
                        else counts.entries.toList().takeLast(12).forEach { (day,rows) ->
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text(day.takeLast(5),fontSize=11.sp,color=p.secondary,modifier=Modifier.width(53.dp))
                                LinearProgressIndicator(progress={rows.size.toFloat()/counts.values.maxOf{it.size}.coerceAtLeast(1)},
                                    modifier=Modifier.weight(1f),color=p.accent)
                                Text(rows.size.toString(),fontSize=11.sp,modifier=Modifier.width(24.dp),
                                    textAlign=TextAlign.End,color=p.ink)
                            }
                        }
                    }
                }
                item {
                    SectionTitle(p,"Dịch vụ phổ biến")
                    VIPPanel(p) { ServiceBreakdown(p,completed) }
                    Text("Số liệu chỉ phản ánh lịch hẹn API website trả về; không suy đoán doanh thu.",
                        color=p.secondary,fontSize=12.sp)
                }
            }
        }
    }

    @Composable
    private fun Insights(p: Palette) {
        LaunchedEffect(insightRefresh){loadInsights()}
        val completed=reportFilter("Hoàn thành",insightRows,true)
        val categories=listOf("Lượt hoàn thành","Không đến","Lượt khách quay lại","Khách vắng 30+ ngày")
        LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            if(insightSelected!=null) {
                items(reportFilter(insightSelected!!,insightRows,true),key={it.optString("id")}){BookingCard(p,it)}
            } else {
                item {
                    SectionTitle(p,"Chăm sóc khách hàng","Phân tích 90 ngày gần nhất từ lịch hẹn thực tế trên website.")
                    if(insightBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(insightError.isNotBlank())Text(insightError,color=MaterialTheme.colorScheme.error)
                }
                categories.chunked(2).forEach{chunk->item{
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                        chunk.forEach { key ->
                            ReportTile(p,key,reportFilter(key,insightRows,true),Modifier.weight(1f)){insightSelected=key}
                        }
                    }
                }}
                item {
                    SectionTitle(p,"Dịch vụ được sử dụng nhiều")
                    VIPPanel(p){ServiceBreakdown(p,completed)}
                    Text("Khách quay lại được xác định bằng số điện thoại có ít nhất 2 lịch hoàn thành trong 90 ngày. Khách vắng 30+ ngày chỉ tính trong phạm vi dữ liệu 90 ngày.",
                        fontSize=12.sp,color=p.secondary)
                }
            }
        }
    }

    private fun runDiagnostics() {
        diagnosticBusy = true
        lifecycleScope.launch(Dispatchers.IO) {
            val message=StringBuilder()
            try {
                api.verifyOwnerSession()
                message.append("Phiên quản trị HouseNail: Thành công\n")
                val config=api.call("adminBookingConfig",JSONArray())
                message.append("API quản trị: Thành công\n")
            } catch(e:Exception){message.append("API quản trị: "+(e.message?:"Thất bại")+"\n")}
            try {
                val day=selectedDay.format(dayFmt)
                val rows=api.callArray("adminList",JSONArray().put(day).put(day))
                message.append("API lịch hẹn: "+rows.length()+" lịch · "+day+"\n")
            } catch(e:Exception){message.append("API lịch hẹn: "+(e.message?:"Thất bại"))}
            withContext(Dispatchers.Main) {
                diagnostics=message.toString()
                diagnosticBusy=false
            }
        }
    }

    @Composable
    private fun Diagnostics(p: Palette) {
        LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            item {
                VIPPanel(p) {
                    SectionTitle(p,"Kiểm tra kết nối · Build 20","Chẩn đoán API và đồng bộ")
                    Spacer(Modifier.height(12.dp))
                    Text(diagnostics,color=p.ink,fontSize=13.sp)
                    if(diagnosticBusy)CircularProgressIndicator()
                    Button(onClick={runDiagnostics()},enabled=!diagnosticBusy,modifier=Modifier.fillMaxWidth()){
                        Icon(Icons.Default.NetworkCheck,null)
                        Text("Kiểm tra kết nối ngay")
                    }
                    Text("Chẩn đoán không xuất token hoặc cookie của khách hàng.",
                        color=p.secondary,fontSize=12.sp)
                }
            }
        }
    }

    @Composable
    private fun SyncCenter(p: Palette) {
        LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            item {
                VIPPanel(p) {
                    SectionTitle(p,"Máy chủ dữ liệu")
                    LabelLine(p,"Website","housenail.sk")
                    LabelLine(p,"API","/api/admin")
                    LabelLine(p,"Phiên đăng nhập",if(authenticated)"Đã xác thực"else"Chưa xác thực")
                    LabelLine(p,"Lịch đồng bộ",lastSync?:"Chưa đồng bộ")
                    OutlinedButton(onClick={reloadDay()},modifier=Modifier.fillMaxWidth()){
                        Icon(Icons.Default.Refresh,null)
                        Spacer(Modifier.width(7.dp))
                        Text("Kiểm tra kết nối và làm mới lịch")
                    }
                }
            }
            item {
                VIPPanel(p) {
                    SectionTitle(p,"Phạm vi đã xác minh")
                    Text("Lịch hẹn và thẻ thành viên qua API website.",color=p.ink)
                    Text("Thông báo đẩy nền và đồng bộ ngoài ứng dụng cần máy chủ và dịch vụ bổ sung.",
                        color=p.secondary,fontSize=12.sp)
                }
            }
        }
    }


    private fun requestDeviceAuthentication(onSuccess: () -> Unit) {
        if (authenticating) return
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard?.isDeviceSecure != true) {
            toast("Hãy bật khóa màn hình trên điện thoại trước khi sử dụng bảo mật ứng dụng.")
            return
        }
        authenticating = true
        if (Build.VERSION.SDK_INT < 29) {
            val intent = keyguard.createConfirmDeviceCredentialIntent(
                "HOUSE NAIL", "Mở khóa dữ liệu quản lý HouseNail"
            )
            if (intent != null) {
                pendingDeviceUnlock = onSuccess
                startActivityForResult(intent, 4103)
            } else {
                authenticating = false
                toast("Thiết bị không hỗ trợ xác thực bằng khóa màn hình.")
            }
            return
        }
        try {
            val builder = BiometricPrompt.Builder(this)
                .setTitle("HOUSE NAIL")
                .setSubtitle("Ứng dụng đang được bảo vệ")
                .setDescription("Mở khóa dữ liệu quản lý HouseNail")
                .setDeviceCredentialAllowed(true)
            builder.build().authenticate(CancellationSignal(), mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        authenticating = false
                        deviceUnlocked = true
                        onSuccess()
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        authenticating = false
                        toast(errString.toString())
                    }
                })
        } catch (e: Exception) {
            authenticating = false
            toast(e.message ?: "Chưa mở được khóa thiết bị.")
        }
    }

    private fun setDeviceLock(enabled: Boolean) {
        requestDeviceAuthentication {
            lockEnabled = enabled
            deviceUnlocked = true
            getSharedPreferences("housenail_v10", MODE_PRIVATE)
                .edit().putBoolean("deviceLock", enabled).apply()
        }
    }

    override fun onResume() {
        super.onResume()
        if (lockEnabled && !deviceUnlocked && !authenticating)
            requestDeviceAuthentication { deviceUnlocked = true }
    }

    override fun onStop() {
        if (lockEnabled) deviceUnlocked = false
        super.onStop()
    }

    @Composable
    private fun DeviceLockScreen(p: Palette) {
        Column(
            Modifier.fillMaxSize().background(p.paper).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Lock, null, tint = p.accent, modifier = Modifier.size(58.dp))
            Spacer(Modifier.height(18.dp))
            Text("HOUSE NAIL", fontSize = 30.sp, fontFamily = FontFamily.Serif, letterSpacing = 3.sp, color = p.ink)
            Spacer(Modifier.height(9.dp))
            Text("Ứng dụng đang được bảo vệ", color = p.secondary)
            Spacer(Modifier.height(21.dp))
            Button(
                onClick = { requestDeviceAuthentication { deviceUnlocked = true } },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Mở khóa bằng sinh trắc học / mật mã") }
        }
    }

    @Composable
    private fun BookingControlScreen(p: Palette) {
        var status by remember { mutableStateOf<JSONObject?>(null) }
        var busy by remember { mutableStateOf(true) }
        var saving by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var notice by remember { mutableStateOf<String?>(null) }
        var refresh by remember { mutableIntStateOf(0) }
        var dateText by remember { mutableStateOf(LocalDate.now(zone).format(dayFmt)) }
        var pending by remember { mutableStateOf<JSONObject?>(null) }
        LaunchedEffect(refresh) {
            busy=true
            error=null
            try {
                status=withContext(Dispatchers.IO) { api.call("adminBookingControl",JSONArray()) }
            } catch (_: ApiClient.AuthException) {
                status=null
                openLogin()
                error="Phiên đăng nhập đã hết hạn."
            } catch (e:Exception) {
                status=null
                error=e.message ?: "Website chưa hỗ trợ API kiểm soát đặt lịch."
            } finally { busy=false }
        }
        if(pending!=null) {
            val request=pending!!
            val next=request.optBoolean("enabled")
            AlertDialog(onDismissRequest={pending=null},
                title={Text(if(next) "Mở nhận lịch?" else "Khóa nhận lịch?")},
                text={Text("Thao tác chỉ ảnh hưởng đặt lịch trực tuyến mới. Lịch đã đặt và điểm tích lũy được giữ nguyên.")},
                confirmButton={TextButton(onClick={
                    pending=null
                    saving=true
                    error=null
                    notice=null
                    lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                api.call("adminSetBookingControl",JSONArray().put(request))
                            }
                            val updated=withContext(Dispatchers.IO) {
                                api.call("adminBookingControl",JSONArray())
                            }
                            status=updated
                            if(request.optString("scope")=="global" &&
                                updated.optBoolean("globalEnabled")!=next)
                                error="Máy chủ chưa xác nhận trạng thái."
                            else notice="Đã lưu và đọc lại trạng thái từ website."
                        } catch (_: ApiClient.AuthException) {
                            status=null
                            openLogin()
                            error="Phiên đăng nhập đã hết hạn."
                        } catch (e:Exception) {
                            status=null
                            error="Chưa xác minh được kết quả: "+(e.message ?: "Lỗi mạng")
                            // A lost response may have applied the mutation: read, never retry write.
                            refresh++
                        } finally { saving=false }
                    }
                }) { Text("Xác nhận") }},
                dismissButton={TextButton(onClick={pending=null}) { Text("Hủy") }})
        }
        LazyColumn(contentPadding=PaddingValues(16.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                VIPPanel(p) {
                    SectionTitle(p,"Kiểm soát đặt lịch","Chỉ điều khiển lịch trực tuyến")
                    if(busy || saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(error!=null) Text(error!!,color=MaterialTheme.colorScheme.error)
                    if(notice!=null) Text(notice!!,color=p.accent)
                    if(status!=null) {
                        val current=status!!
                        Text("Múi giờ: "+current.optString("timezone","Europe/Bratislava"),
                            color=p.secondary)
                        Text("Nhận lịch toàn website: "+if(current.optBoolean("globalEnabled")) "Đang bật" else "Đang tắt",
                            color=p.ink)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(onClick={pending=JSONObject().put("scope","global").put("enabled",true)},
                                enabled=!saving && !busy && !current.optBoolean("globalEnabled")) {
                                Text("Mở toàn bộ")
                            }
                            OutlinedButton(onClick={pending=JSONObject().put("scope","global").put("enabled",false)},
                                enabled=!saving && !busy && current.optBoolean("globalEnabled")) {
                                Text("Tạm khóa")
                            }
                        }
                        Text("Hôm nay: "+current.optString("today"),color=p.secondary)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick={pending=JSONObject().put("scope","today").put("enabled",false)},
                                enabled=!saving && !busy) { Text("Khóa hôm nay") }
                            OutlinedButton(onClick={pending=JSONObject().put("scope","today").put("enabled",true)},
                                enabled=!saving && !busy) { Text("Mở hôm nay") }
                        }
                        OutlinedTextField(value=dateText,onValueChange={dateText=it},
                            label={Text("Ngày cần khóa / mở (yyyy-MM-dd)")},
                            singleLine=true,modifier=Modifier.fillMaxWidth())
                        val validDay=runCatching {
                            LocalDate.parse(dateText,dayFmt).format(dayFmt)==dateText
                        }.getOrDefault(false)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick={
                                pending=JSONObject().put("scope","dates").put("enabled",false)
                                    .put("dates",JSONArray().put(dateText))
                            },enabled=validDay && !saving && !busy) { Text("Khóa ngày") }
                            OutlinedButton(onClick={
                                pending=JSONObject().put("scope","dates").put("enabled",true)
                                    .put("dates",JSONArray().put(dateText))
                            },enabled=validDay && !saving && !busy) { Text("Mở ngày") }
                        }
                        val closed=current.optJSONArray("closedDates") ?: JSONArray()
                        Text("Ngày đã đóng: "+closed.length(),color=p.secondary)
                        (0 until minOf(closed.length(),12)).forEach { index ->
                            val day=closed.optJSONObject(index)
                            if(day!=null) Text(day.optString("date")+
                                " · "+day.optString("source"),color=p.secondary,fontSize=12.sp)
                        }
                    }
                    TextButton(onClick={refresh++},enabled=!busy && !saving) {
                        Text("Tải lại trạng thái")
                    }
                }
            }
        }
    }

    @Composable
    private fun WebsiteAccessScreen(p: Palette) {
        var enabled by remember { mutableStateOf<Boolean?>(null) }
        var busy by remember { mutableStateOf(true) }
        var saving by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var message by remember { mutableStateOf<String?>(null) }
        var pending by remember { mutableStateOf<Boolean?>(null) }
        var refresh by remember { mutableIntStateOf(0) }
        LaunchedEffect(refresh) {
            busy=true
            error=null
            try {
                val result=withContext(Dispatchers.IO) {
                    api.call("adminWebsiteAccessStatus",JSONArray())
                }
                if(!result.has("enabled")) throw Exception("Thiếu trạng thái website.")
                enabled=result.getBoolean("enabled")
            } catch (_: ApiClient.AuthException) {
                enabled=null
                openLogin()
                error="Phiên đăng nhập đã hết hạn."
            } catch (e:Exception) {
                enabled=null
                error="Chưa đọc được trạng thái: "+(e.message ?: "Lỗi máy chủ")
            } finally { busy=false }
        }
        if(pending!=null) {
            val next=pending!!
            AlertDialog(onDismissRequest={pending=null},
                title={Text(if(next) "Mở website công khai?" else "Tạm đóng website công khai?")},
                text={Text("Chỉ thay đổi truy cập của khách. API quản trị, lịch hẹn và dữ liệu khách vẫn được giữ.")},
                confirmButton={TextButton(onClick={
                    pending=null
                    saving=true
                    error=null
                    message=null
                    lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                api.call("adminSetWebsiteAccess",
                                    JSONArray().put(JSONObject().put("enabled",next)))
                            }
                            val latest=withContext(Dispatchers.IO) {
                                api.call("adminWebsiteAccessStatus",JSONArray())
                            }
                            enabled=latest.getBoolean("enabled")
                            if(enabled==next) message="Đã đồng bộ với website."
                            else error="Máy chủ chưa xác nhận thay đổi."
                        } catch (_: ApiClient.AuthException) {
                            enabled=null
                            openLogin()
                            error="Phiên đăng nhập đã hết hạn."
                        } catch (e:Exception) {
                            enabled=null
                            error="Chưa xác minh được kết quả: "+(e.message ?: "Lỗi mạng")
                            // Reconcile read-only, never retry a write.
                            refresh++
                        } finally { saving=false }
                    }
                }){Text("Xác nhận")},
                dismissButton={TextButton(onClick={pending=null}){Text("Hủy")}})
        }
        LazyColumn(contentPadding=PaddingValues(16.dp),
            verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item {
                VIPPanel(p) {
                    SectionTitle(p,"Website công khai","Kiểm soát truy cập của khách hàng")
                    Text("Khi tạm đóng, chủ tiệm và nhân viên vẫn có thể sử dụng trang quản trị.",
                        color=p.secondary)
                    Spacer(Modifier.height(12.dp))
                    if(busy || saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(error!=null) Text(error!!,color=MaterialTheme.colorScheme.error)
                    if(message!=null) Text(message!!,color=p.accent)
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("Cho phép khách truy cập",color=p.ink,modifier=Modifier.weight(1f))
                        Switch(checked=enabled ?: false,onCheckedChange={pending=it},
                            enabled=enabled!=null && !busy && !saving)
                    }
                    Text(when(enabled) {
                        true -> "Website đang mở"
                        false -> "Website đang tạm đóng"
                        null -> "Chưa xác minh được trạng thái từ máy chủ"
                    },color=p.secondary)
                    TextButton(onClick={refresh++},enabled=!busy && !saving) {
                        Text("Tải lại trạng thái")
                    }
                }
            }
        }
    }

    @Composable
    private fun ServiceVisibilityScreen(p: Palette) {
        var confirmed by remember { mutableStateOf<JSONObject?>(null) }
        var nails by remember { mutableStateOf(true) }
        var lashes by remember { mutableStateOf(true) }
        var hair by remember { mutableStateOf(true) }
        var busy by remember { mutableStateOf(true) }
        var saving by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var notice by remember { mutableStateOf<String?>(null) }
        var refresh by remember { mutableIntStateOf(0) }
        var confirmDiscard by remember { mutableStateOf(false) }
        fun normalized(config: JSONObject): JSONObject {
            val flags=config.optJSONObject("serviceVisibility")
            return JSONObject().put("nails",flags?.optBoolean("nails",true) ?: true)
                .put("lashes",flags?.optBoolean("lashes",true) ?: true)
                .put("hair",flags?.optBoolean("hair",true) ?: true)
        }
        fun setFlags(flags: JSONObject) {
            nails=flags.optBoolean("nails",true)
            lashes=flags.optBoolean("lashes",true)
            hair=flags.optBoolean("hair",true)
        }
        fun dirty(): Boolean {
            val old=confirmed ?: return false
            return old.optBoolean("nails")!=nails ||
                old.optBoolean("lashes")!=lashes || old.optBoolean("hair")!=hair
        }
        LaunchedEffect(refresh) {
            busy=true
            error=null
            notice=null
            try {
                val config=withContext(Dispatchers.IO) {
                    api.call("adminBookingConfig",JSONArray())
                }
                val flags=normalized(config)
                confirmed=flags
                setFlags(flags)
            } catch (_: ApiClient.AuthException) {
                confirmed=null
                openLogin()
                error="Phiên đăng nhập đã hết hạn."
            } catch (e:Exception) {
                confirmed=null
                error=e.message ?: "Không đọc được cấu hình website."
            } finally { busy=false }
        }
        if(confirmDiscard) AlertDialog(onDismissRequest={confirmDiscard=false},
            title={Text("Bỏ thay đổi chưa lưu?")},
            text={Text("Tải lại trạng thái mới nhất từ website.")},
            confirmButton={TextButton(onClick={confirmDiscard=false;refresh++}){Text("Bỏ và tải lại")}},
            dismissButton={TextButton(onClick={confirmDiscard=false}){Text("Hủy")}})
        LazyColumn(contentPadding=PaddingValues(16.dp),
            verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item {
                VIPPanel(p) {
                    SectionTitle(p,"Dịch vụ đặt lịch","Dùng chung cấu hình với website và IPA")
                    Spacer(Modifier.height(8.dp))
                    Text("Chỉ ảnh hưởng dịch vụ khách được chọn khi đặt trực tuyến. Lịch đã đặt được giữ.",
                        color=p.secondary)
                    if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(error!=null) Text(error!!,color=MaterialTheme.colorScheme.error)
                    if(notice!=null) Text(notice!!,color=p.accent)
                    if(confirmed!=null) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text("Làm móng",color=p.ink,modifier=Modifier.weight(1f))
                            Switch(checked=nails,onCheckedChange={nails=it},enabled=!busy && !saving)
                        }
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text("Làm mi",color=p.ink,modifier=Modifier.weight(1f))
                            Switch(checked=lashes,onCheckedChange={lashes=it},enabled=!busy && !saving)
                        }
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text("Gội đầu",color=p.ink,modifier=Modifier.weight(1f))
                            Switch(checked=hair,onCheckedChange={hair=it},enabled=!busy && !saving)
                        }
                        if(!nails && !lashes && !hair)
                            Text("Phải bật ít nhất một nhóm dịch vụ.",
                                color=MaterialTheme.colorScheme.error)
                        if(dirty()) Text("Có thay đổi chưa lưu.",color=p.secondary)
                        Button(onClick={
                            if(!dirty() || !(nails || lashes || hair)) return@Button
                            saving=true
                            error=null
                            notice=null
                            val requested=JSONObject().put("nails",nails)
                                .put("lashes",lashes).put("hair",hair)
                            lifecycleScope.launch {
                                try {
                                    val original=confirmed ?: return@launch
                                    val current=withContext(Dispatchers.IO) {
                                        normalized(api.call("adminBookingConfig",JSONArray()))
                                    }
                                    if(current.toString()!=original.toString()) {
                                        confirmed=current
                                        setFlags(current)
                                        error="Website vừa thay đổi cấu hình. Đã tải lại."
                                        return@launch
                                    }
                                    withContext(Dispatchers.IO) {
                                        api.call("adminSetServiceVisibility",
                                            JSONArray().put(requested))
                                    }
                                    val verified=withContext(Dispatchers.IO) {
                                        normalized(api.call("adminBookingConfig",JSONArray()))
                                    }
                                    confirmed=verified
                                    setFlags(verified)
                                    if(verified.toString()==requested.toString())
                                        notice="Đã đồng bộ với website."
                                    else error="Máy chủ trả về cấu hình khác; đã hiển thị trạng thái mới nhất."
                                } catch (_: ApiClient.AuthException) {
                                    openLogin()
                                    error="Phiên đăng nhập đã hết hạn."
                                } catch (e:Exception) {
                                    error="Chưa xác minh được kết quả lưu: "+(e.message ?: "Lỗi mạng")
                                    // Do not repeat a mutation after a lost response.
                                    try {
                                        val latest=withContext(Dispatchers.IO) {
                                            normalized(api.call("adminBookingConfig",JSONArray()))
                                        }
                                        confirmed=latest
                                        setFlags(latest)
                                    } catch (_:Exception) { confirmed=null }
                                } finally { saving=false }
                            }
                        },enabled=!busy && !saving && dirty() && (nails || lashes || hair)) {
                            Text(if(saving) "Đang lưu..." else "Lưu lên website")
                        }
                    }
                    TextButton(onClick={if(dirty()) confirmDiscard=true else refresh++},
                        enabled=!busy && !saving) { Text("Tải lại từ website") }
                }
            }
        }
    }

    @Composable
    private fun WaitlistScreen(p: Palette) {
        var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
        var busy by remember { mutableStateOf(true) }
        var saving by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var refresh by remember { mutableIntStateOf(0) }
        var pending by remember { mutableStateOf<Pair<String,String>?>(null) }
        val statuses = listOf("waiting" to "Đang chờ","contacted" to "Đã liên hệ",
            "booked" to "Đã đặt lịch","closed" to "Đã đóng")
        LaunchedEffect(refresh) {
            busy=true
            error=null
            try {
                val data=withContext(Dispatchers.IO) { api.callArray("adminWaitlistList",JSONArray()) }
                rows=(0 until data.length()).mapNotNull { data.optJSONObject(it) }
            } catch (_: ApiClient.AuthException) {
                openLogin()
                error="Phiên đăng nhập đã hết hạn."
            } catch (e: Exception) { error=e.message ?: "Không tải được danh sách chờ." }
            finally { busy=false }
        }
        if(pending!=null) {
            val (id,next)=pending!!
            AlertDialog(onDismissRequest={pending=null},
                title={Text("Xác nhận cập nhật")},
                text={Text("Thay đổi trạng thái yêu cầu trên website thành " +
                    (statuses.firstOrNull{it.first==next}?.second ?: next) + "?")},
                confirmButton={TextButton(onClick={
                    pending=null
                    saving=id
                    lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                api.call("adminWaitlistUpdate",JSONArray().put(id).put(next))
                            }
                            refresh++
                        } catch (_: ApiClient.AuthException) { openLogin() }
                        catch (e:Exception) {
                            error=e.message ?: "Không thể cập nhật."
                            // Never retry a mutation automatically; read server state instead.
                            refresh++
                        } finally { saving=null }
                    }
                }) { Text("Xác nhận") }},
                dismissButton={TextButton(onClick={pending=null}) { Text("Hủy") }})
        }
        LazyColumn(contentPadding=PaddingValues(16.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                VIPPanel(p) {
                    SectionTitle(p,"Danh sách chờ","Dùng chung dữ liệu với iPhone và website")
                    Text("Tổng: ${rows.size} · Đang chờ: ${rows.count{it.optString("status")=="waiting"}}",
                        color=p.secondary)
                    TextButton(onClick={refresh++},enabled=!busy && saving==null) {
                        Text("Tải lại từ website")
                    }
                    if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(error!=null) Text(error!!,color=MaterialTheme.colorScheme.error)
                }
            }
            if(!busy && rows.isEmpty()) item { Text("Chưa có khách trong danh sách chờ.",color=p.secondary) }
            items(rows) { request ->
                VIPPanel(p) {
                    Text(request.optString("name"),fontWeight=FontWeight.SemiBold,color=p.ink)
                    Text(request.optString("date")+" · "+request.optString("service"),color=p.secondary)
                    Text(request.optString("phone"),color=p.ink)
                    val note=request.optString("note")
                    if(note.isNotBlank()) Text(note,color=p.secondary)
                    val current=request.optString("status")
                    Text("Trạng thái: "+(statuses.firstOrNull{it.first==current}?.second ?: current),
                        color=p.accent)
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        statuses.forEach { (code,label) ->
                            FilterChip(selected=current==code,
                                enabled=saving==null && !busy,
                                onClick={
                                    if(current!=code && request.optString("id").isNotBlank())
                                        pending=request.optString("id") to code
                                },label={Text(label)})
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun BookingHistory(p: Palette) {
        var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
        var query by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("Tất cả") }
        var busy by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var refresh by remember { mutableIntStateOf(0) }
        LaunchedEffect(refresh) {
            busy = true
            error = null
            try {
                val today = LocalDate.now(zone)
                val start = today.minusDays(29).format(dayFmt)
                val end = today.format(dayFmt)
                val result = withContext(Dispatchers.IO) {
                    api.callArray("adminList", JSONArray().put(start).put(end))
                }
                rows = (0 until result.length()).mapNotNull { result.optJSONObject(it) }
            } catch (_: ApiClient.AuthException) {
                openLogin()
                error = "Phiên đăng nhập đã hết hạn."
            } catch (e: Exception) {
                error = e.message ?: "Không tải được lịch sử."
            } finally {
                busy = false
            }
        }
        val filtered = rows.filter { b ->
            val matchesStatus = when (status) {
                "Hoàn thành" -> b.completed() && !b.cancelled() && !b.noShow()
                "Không đến" -> b.noShow() && !b.cancelled()
                "Đã hủy" -> b.cancelled()
                else -> true
            }
            matchesStatus && (query.isBlank() ||
                listOf(b.optString("name"),b.optString("phone"),b.optString("service"))
                    .joinToString(" ").contains(query.trim(),ignoreCase=true))
        }.sortedWith(compareByDescending<JSONObject> { it.optString("date") }
            .thenByDescending { it.optString("time") })
        LazyColumn(contentPadding=PaddingValues(16.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                VIPPanel(p) {
                    SectionTitle(p,"Lịch sử đặt lịch","30 ngày gần nhất · Dữ liệu từ website")
                    Spacer(Modifier.height(10.dp))
                    Text("Tổng: ${rows.size} lịch · Đang hiển thị: ${filtered.size}",color=p.secondary)
                    OutlinedTextField(value=query,onValueChange={query=it},
                        label={Text("Tìm tên, số điện thoại, dịch vụ")},
                        singleLine=true,modifier=Modifier.fillMaxWidth())
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        listOf("Tất cả","Hoàn thành","Không đến","Đã hủy").forEach { option ->
                            FilterChip(selected=status==option,onClick={status=option},
                                label={Text(option)})
                        }
                    }
                    TextButton(onClick={refresh++}) { Text("Tải lại lịch sử") }
                    if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(error!=null) Text(error!!,color=MaterialTheme.colorScheme.error)
                }
            }
            if(!busy && error==null && filtered.isEmpty()) {
                item { Text("Không có lịch phù hợp.",color=p.secondary) }
            }
            items(filtered) { booking -> BookingCard(p,booking) }
        }
    }

    @Composable
    private fun MoreScreen(p: Palette) {
        var confirmLogout by remember { mutableStateOf(false) }
        var newBookingAlertsEnabled by remember { mutableStateOf(BookingAlerts.enabled(this@MainActivity)) }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(17.dp)) {
            item {
                VIPPanel(p) {
                    Text("HOUSE NAIL • CONCIERGE", fontSize = 10.sp, fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold, letterSpacing = 2.4.sp, color = p.accent)
                    Spacer(Modifier.height(10.dp))
                    Text("Tùy chỉnh & quản trị", fontSize = 23.sp, fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold, color = p.ink)
                    Text("Bảo mật, thông báo, đồng bộ và báo cáo — một nơi.", fontSize = 14.sp,
                        color = p.secondary)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment=Alignment.CenterVertically){
                        Icon(Icons.Default.Lock,null,tint=p.accent,modifier=Modifier.size(15.dp))
                        Text("  HouseNail Account",fontSize=11.sp,color=p.accent,modifier=Modifier.weight(1f))
                        Icon(Icons.Default.AutoAwesome,null,tint=p.champagne,modifier=Modifier.size(14.dp))
                        Text(" V10 VIP",fontSize=11.sp,color=p.accent)
                    }
                }
            }
            item {
                SettingsGroup(p, "Kiểm soát nhận lịch") {
                    TextButton(onClick={screen="booking-control"},modifier=Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.EventBusy,null,tint=p.accent)
                        Spacer(Modifier.width(8.dp))
                        Text("Khóa / mở đặt lịch trực tuyến",color=p.ink)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.ChevronRight,null,tint=p.secondary)
                    }
                    Text("Toàn website, hôm nay hoặc ngày được chọn. Không xóa lịch cũ.",
                        fontSize=12.sp,color=p.secondary)
                }
            }
            item {
                SettingsGroup(p, "Website công khai") {
                    TextButton(onClick={screen="website-access"},modifier=Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Public,null,tint=p.accent)
                        Spacer(Modifier.width(8.dp))
                        Text("Mở / đóng website cho khách",color=p.ink)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.ChevronRight,null,tint=p.secondary)
                    }
                    Text("Chỉ thay đổi truy cập công khai, giữ nguyên API quản trị.",
                        fontSize=12.sp,color=p.secondary)
                }
            }
            item {
                SettingsGroup(p, "Dịch vụ đặt lịch trực tuyến") {
                    TextButton(onClick={screen="services"},modifier=Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Tune,null,tint=p.accent)
                        Spacer(Modifier.width(8.dp))
                        Text("Bật / tắt nhóm dịch vụ",color=p.ink)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.ChevronRight,null,tint=p.secondary)
                    }
                    Text("Làm móng · Làm mi · Gội đầu. Trạng thái lưu trên website.",
                        fontSize=12.sp,color=p.secondary)
                }
            }
            item {
                SettingsGroup(p, "Danh sách chờ từ website") {
                    TextButton(onClick={screen="waitlist"},modifier=Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Schedule,null,tint=p.accent)
                        Spacer(Modifier.width(8.dp))
                        Text("Khách chờ giờ trống",color=p.ink)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.ChevronRight,null,tint=p.secondary)
                    }
                    Text("Đọc và cập nhật trạng thái trên cùng API với iPhone.",fontSize=12.sp,color=p.secondary)
                }
            }
            item {
                SettingsGroup(p, "Lịch sử đặt lịch") {
                    TextButton(onClick={screen="history"},modifier=Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.History,null,tint=p.accent)
                        Spacer(Modifier.width(8.dp))
                        Text("Tra cứu 30 ngày gần đây",color=p.ink)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.ChevronRight,null,tint=p.secondary)
                    }
                    Text("Chỉ xem dữ liệu thực từ máy chủ; không xóa hay thay đổi lịch.",fontSize=12.sp,color=p.secondary)
                }
            }
            item {
                SettingsGroup(p,"Kiểm tra kết nối · Build 20") {
                    TextButton(onClick={screen="diagnostics"},modifier=Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.NetworkCheck,null,tint=p.accent)
                        Spacer(Modifier.width(8.dp))
                        Text("Kiểm tra API & đồng bộ",color=p.ink)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.ChevronRight,null,tint=p.secondary)
                    }
                    Text("Bản V10.0.0 · Chẩn đoán API và đồng bộ",fontSize=12.sp,color=p.secondary)
                }
            }
            item {
                SettingsGroup(p, "Luxury Theme Studio") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = themeName == "pearl", onClick = { setTheme("pearl") },
                            label = { Text("Pearl Ivory") })
                        FilterChip(selected = themeName == "obsidian", onClick = { setTheme("obsidian") },
                            label = { Text("Royal Obsidian") })
                    }
                    ThemePreview(p)
                    Text("Thay đổi nền, bề mặt, chữ và màu nhấn ngay lập tức.",
                        fontSize=12.sp,color=p.secondary)
                }
            }
            item {
                SettingsGroup(p,"Ngôn ngữ · Language · Jazyk"){
                    Text("Ứng dụng hiện dùng nhãn tiếng Việt. Định dạng thời gian theo Europe/Bratislava.",
                        fontSize=12.sp,color=p.secondary)
                }
            }
            item {
                SettingsGroup(p,"Bảo mật trên thiết bị"){
                    Row(verticalAlignment=Alignment.CenterVertically){
                        Text("Yêu cầu sinh trắc học / mật mã",
                            color=p.ink,modifier=Modifier.weight(1f),fontSize=13.sp)
                        Switch(checked=lockEnabled,onCheckedChange={setDeviceLock(it)})
                    }
                    Text("Khóa ứng dụng khi chuyển sang nền. Không thay thế đăng nhập và phân quyền máy chủ.",
                        fontSize=12.sp,color=p.secondary)
                }
            }
            item {
                SettingsGroup(p,"Thông báo lịch hẹn"){
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("Báo khi khách đặt lịch mới", color=p.ink, fontSize=13.sp,
                            modifier=Modifier.weight(1f))
                        Switch(checked=newBookingAlertsEnabled,onCheckedChange={
                            newBookingAlertsEnabled=it
                            BookingAlerts.setEnabled(this@MainActivity,it)
                            if (it) BookingAlerts.requestPermissionIfNeeded(this@MainActivity)
                        })
                    }
                    LabelLine(p,"Quyền Android",if (BookingAlerts.canNotify(this@MainActivity))
                        "Đã cho phép" else "Chưa cho phép")
                    Text("Khi ứng dụng mở: kiểm tra khoảng 45 giây/lần. Khi đóng: Android kiểm tra nền định kỳ từ 15 phút, có thể trễ tùy tiết kiệm pin.",
                        fontSize=12.sp,color=p.secondary)
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick={
                        if (BookingAlerts.canNotify(this@MainActivity)) {
                            BookingAlerts.testNotification(this@MainActivity)
                        } else {
                            BookingAlerts.requestPermissionIfNeeded(this@MainActivity)
                            toast("Hãy cho phép thông báo trước khi thử.")
                        }
                    }, modifier=Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.NotificationsActive,null)
                        Spacer(Modifier.width(7.dp))
                        Text("Gửi thông báo thử")
                    }
                    TextButton(onClick={
                        val settingsIntent=Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        settingsIntent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,packageName)
                        startActivity(settingsIntent)
                    }, modifier=Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Settings,null)
                        Spacer(Modifier.width(7.dp))
                        Text("Quản lý quyền thông báo trên điện thoại")
                    }
                }
            }
            item {
                SettingsGroup(p,"Thông báo đẩy Android (FCM)"){
                    LabelLine(p,"Firebase",if (BookingAlerts.isFcmConfigured())
                        "Đã cấu hình trong APK" else "Chưa cấu hình")
                    LabelLine(p,"Máy chủ",if (BookingAlerts.fcmServerRegistered(this@MainActivity))
                        "Đã đăng ký thiết bị" else "Chưa xác nhận FCM")
                    Text("Để nhận gần như ngay lập tức cả khi app đóng, cần bật Firebase cho APK và kết nối API FCM của website HouseNail. Không có cấu hình này, thông báo nền chỉ kiểm tra định kỳ.",
                        fontSize=12.sp,color=p.secondary)
                }
            }
            item {
                SettingsGroup(p,"Chẩn đoán kết nối"){
                    LabelLine(p,"API quản trị","/api/admin")
                    LabelLine(p,"Lần đồng bộ thành công",lastSync ?: "Chưa có")
                    Button(onClick={screen="diagnostics";runDiagnostics()},modifier=Modifier.fillMaxWidth()){
                        Icon(Icons.Default.NetworkCheck,null)
                        Spacer(Modifier.width(8.dp))
                        Text("Kiểm tra kết nối ngay")
                    }
                }
            }
            item {
                SettingsGroup(p,"Báo cáo & quản trị"){
                    TextButton(onClick={reportSelected=null;screen="reports"},modifier=Modifier.fillMaxWidth()){
                        Icon(Icons.Default.BarChart,null)
                        Text("  Báo cáo tuần · tháng · năm",modifier=Modifier.weight(1f),color=p.ink)
                        Icon(Icons.Default.ChevronRight,null,tint=p.secondary)
                    }
                    HorizontalDivider(color=p.border)
                    TextButton(onClick={insightSelected=null;insightRefresh++;screen="insights"},
                        modifier=Modifier.fillMaxWidth()){
                        Icon(Icons.Default.People,null)
                        Text("  Phân tích khách hàng 90 ngày",modifier=Modifier.weight(1f),color=p.ink)
                        Icon(Icons.Default.ChevronRight,null,tint=p.secondary)
                    }
                    HorizontalDivider(color=p.border)
                    TextButton(onClick={screen="sync"},modifier=Modifier.fillMaxWidth()){
                        Icon(Icons.Default.Sync,null)
                        Text("  Trung tâm đồng bộ",modifier=Modifier.weight(1f),color=p.ink)
                        Icon(Icons.Default.ChevronRight,null,tint=p.secondary)
                    }
                }
            }
            item {
                SettingsGroup(p, "HouseNail Manager · V10 Pearl Luxury VIP"){
                    LabelLine(p,"Máy chủ","housenail.sk")
                    LabelLine(p,"Múi giờ","Europe/Bratislava")
                    Text("Lịch hẹn, khách hàng và thẻ dùng chung dữ liệu với website. Các thay đổi chỉ được lưu khi máy chủ xác nhận.",
                        fontSize=12.sp,color=p.secondary)
                }
            }
            item {
                OutlinedButton(onClick={confirmLogout=true},modifier=Modifier.fillMaxWidth()){
                    Icon(Icons.Default.Logout,null,tint=MaterialTheme.colorScheme.error)
                    Text("  Đăng xuất khỏi app",color=MaterialTheme.colorScheme.error)
                }
            }
        }
        if(confirmLogout) AlertDialog(onDismissRequest={confirmLogout=false},
            title={Text("Đăng xuất khỏi HouseNail?")},
            text={Text("Ứng dụng sẽ thu hồi phiên HouseNail trên máy chủ rồi xóa cookie thiết bị.")},
            confirmButton={TextButton(onClick={confirmLogout=false;logout()}){
                Text("Đăng xuất",color=MaterialTheme.colorScheme.error)
            }},
            dismissButton={TextButton(onClick={confirmLogout=false}){Text("Hủy")}})
    }

    @Composable
    private fun VIPPanel(p: Palette, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
        Column(
            modifier = modifier.fillMaxWidth()
                .shadow(17.dp, RoundedCornerShape(24.dp), ambientColor = Color.Black.copy(alpha = .045f), spotColor = Color.Black.copy(alpha = .045f))
                .clip(RoundedCornerShape(24.dp))
                .background(p.surface)
                .border(.8.dp, p.border, RoundedCornerShape(24.dp))
                .padding(19.dp),
            content = content
        )
    }

    @Composable
    private fun SectionTitle(p: Palette, title: String, subtitle: String? = null) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontSize = 20.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, color = p.ink)
            subtitle?.let { Text(it, fontSize = 12.sp, color = p.secondary) }
        }
    }

    @Composable
    private fun MetricTile(p: Palette, title: String, value: Int, symbol: ImageVector, tint: Color, modifier: Modifier, onClick: () -> Unit) {
        Column(
            modifier.clip(RoundedCornerShape(22.dp)).background(p.surface)
                .border(.8.dp, p.border, RoundedCornerShape(22.dp))
                .clickable { onClick() }.padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.size(41.dp).clip(RoundedCornerShape(14.dp))
                        .background(tint.copy(alpha = .12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(symbol, title, tint = tint, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.weight(1f))
                Icon(Icons.Default.NorthEast, null, tint = p.champagne, modifier = Modifier.size(14.dp))
            }
            Text(value.toString(), fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = p.ink)
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = p.ink.copy(alpha = .76f))
        }
    }

        @Composable
    private fun ActionTile(p: Palette, title: String, icon: ImageVector, emphasized: Boolean, modifier: Modifier, onClick: () -> Unit) {
        Column(
            modifier.heightIn(min = 116.dp).clip(RoundedCornerShape(21.dp)).background(if (emphasized) Color(0xFF5C4047) else p.surface)
                .border(1.dp, if (emphasized) p.champagne.copy(alpha = .6f) else p.border, RoundedCornerShape(21.dp)).clickable { onClick() }.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(45.dp).clip(RoundedCornerShape(14.dp)).background(if (emphasized) Color.White.copy(alpha = .16f) else p.accent.copy(alpha = .10f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = if (emphasized) Color.White else p.accent)
            }
            Spacer(Modifier.weight(1f))
            Row {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (emphasized) Color.White else p.ink, modifier = Modifier.weight(1f))
                Icon(Icons.Default.NorthEast, null, tint = if (emphasized) Color.White else p.ink, modifier = Modifier.size(13.dp))
            }
        }
    }

    @Composable
    private fun UpcomingRow(p: Palette, b: JSONObject) {
        Row(Modifier.fillMaxWidth().clickable { selectedBooking = b }.padding(vertical = 13.dp), horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.Top) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(55.dp)) {
                Text(b.optString("time"), fontWeight = FontWeight.SemiBold, color = p.accent)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.size(7.dp).clip(CircleShape).background(p.champagne))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(displayName(b), fontWeight = FontWeight.SemiBold, color = p.ink)
                Text(b.optString("service"), fontSize = 12.sp, color = p.secondary)
                StatusPill(p, b)
            }
            Icon(Icons.Default.ChevronRight, null, tint = p.secondary, modifier = Modifier.size(16.dp))
        }
    }

    @Composable
    private fun StatusPill(p: Palette, b: JSONObject) {
        val pair = when {
            b.cancelled() -> "Đã hủy" to p.secondary
            b.completed() -> "Hoàn thành" to Color(0xFF478C70)
            b.noShow() -> "Không đến" to Color.Red
            else -> "Đã đặt" to p.accent
        }
        Text(pair.first, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = pair.second, modifier = Modifier.clip(CircleShape).background(pair.second.copy(alpha = .1f)).padding(horizontal = 10.dp, vertical = 5.dp))
    }

    @Composable
    private fun StatLine(p: Palette, label: String, value: Int) {
        Row { Text(label, color = p.ink); Spacer(Modifier.weight(1f)); Text(value.toString(), fontWeight = FontWeight.SemiBold, color = p.ink) }
    }

    @Composable
    private fun EmptyBlock(p: Palette, title: String, detail: String) {
        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.EventAvailable, null, tint = p.secondary)
            Text(title, fontWeight = FontWeight.SemiBold, color = p.ink)
            Text(detail, fontSize = 12.sp, color = p.secondary)
        }
    }

    @Composable
    private fun StepLine(p: Palette, number: String, title: String, detail: String) {
        Row(verticalAlignment = Alignment.Top) {
            Text(number, color = p.accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(32.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = p.ink)
                Text(detail, fontSize = 12.sp, color = p.secondary)
            }
            Icon(Icons.Default.CheckCircleOutline, null, tint = p.champagne)
        }
    }

    @Composable
    private fun LabelLine(p: Palette, key: String, value: String) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(key, color = p.ink, modifier = Modifier.weight(1f))
            Text(value, color = p.secondary, fontWeight = FontWeight.Medium)
        }
    }

    @Composable
    private fun SettingsGroup(p: Palette, title: String, content: @Composable ColumnScope.() -> Unit) {
        VIPPanel(p) {
            SectionTitle(p, title)
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = p.border)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }

    @Composable
    private fun ThemePreview(p: Palette) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.paper).border(1.dp, p.champagne.copy(alpha = .5f), RoundedCornerShape(18.dp)).padding(16.dp)) {
            Row {
                Column {
                    Text("HOUSE NAIL", fontSize = 10.sp, letterSpacing = 3.sp, color = p.ink)
                    Text("Luxury Manager", fontFamily = FontFamily.Serif, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = p.ink)
                }
                Spacer(Modifier.weight(1f))
                Icon(Icons.Default.WorkspacePremium, null, tint = p.champagne)
            }
        }
    }

    @Composable
    private fun VIPMemberCard(p: Palette, card: JSONObject) {
        val midnight=Color(0xFF0E131A)
        val gold=Color(0xFFE0BD7D)
        val cardId=card.optString("cardId")
        val qr=remember(cardId) {
            runCatching {
                val bits=QRCodeWriter().encode(cardId,BarcodeFormat.QR_CODE,256,256)
                val image=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
                for(x in 0 until 256) for(y in 0 until 256)
                    image.setPixel(x,y,if(bits[x,y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                image
            }.getOrNull()
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(midnight,Color(0xFF273342))))
            .border(.8.dp,gold.copy(alpha=.45f),RoundedCornerShape(24.dp))
            .padding(20.dp),verticalArrangement=Arrangement.spacedBy(17.dp)) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text("HOUSE NAIL",fontFamily=FontFamily.Serif,fontSize=21.sp,
                        letterSpacing=4.sp,color=Color.White)
                    Text("PRIVATE MEMBERSHIP",fontSize=9.sp,letterSpacing=3.sp,color=gold)
                }
                Column(horizontalAlignment=Alignment.End) {
                    Icon(Icons.Default.AutoAwesome,null,tint=gold)
                    Text(tierTitle(effectiveTier(card)).uppercase(),fontSize=11.sp,
                        fontWeight=FontWeight.Bold,letterSpacing=2.sp,color=gold)
                }
            }
            HorizontalDivider(color=gold.copy(alpha=.45f))
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                    Text("THÀNH VIÊN",color=gold,letterSpacing=2.sp,fontSize=9.sp)
                    Text(card.optString("name","Khách hàng"),color=Color.White,
                        fontSize=23.sp,fontFamily=FontFamily.Serif,fontWeight=FontWeight.Medium)
                    Text("MÃ THẺ",color=gold,letterSpacing=2.sp,fontSize=9.sp)
                    Text(cardId,color=Color.White.copy(alpha=.85f),fontSize=12.sp,maxLines=2)
                }
                if(qr!=null) Box(Modifier.size(112.dp).clip(RoundedCornerShape(12.dp))
                    .background(Color.White).padding(7.dp),contentAlignment=Alignment.Center) {
                    Image(qr.asImageBitmap(),"QR thẻ khách HouseNail",Modifier.fillMaxSize())
                }
            }
            HorizontalDivider(color=gold.copy(alpha=.45f))
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("Hành trình thưởng",color=Color.White,fontSize=12.sp,modifier=Modifier.weight(1f))
                Text(card.optInt("cycleVisits").toString()+"/5",color=gold,fontWeight=FontWeight.Bold)
            }
            LinearProgressIndicator(progress={card.optInt("cycleVisits").coerceIn(0,5)/5f},
                modifier=Modifier.fillMaxWidth(),color=gold,trackColor=Color.White.copy(alpha=.18f))
            Text("Tổng "+card.optInt("totalVisits")+" lượt · Đã dùng "+
                card.optInt("rewardsRedeemed")+" ưu đãi",fontSize=11.sp,color=Color.White.copy(alpha=.75f))
        }
    }

    private fun setTheme(value: String) {
        themeName = value
        getSharedPreferences("housenail_v10", MODE_PRIVATE).edit().putString("theme", value).apply()
    }

    private fun logout() {
        loading = true
        lifecycleScope.launch(Dispatchers.IO) {
            BookingAlerts.unregisterBeforeLogout(this@MainActivity)
            api.logoutOwnerSession()
            android.webkit.CookieManager.getInstance().removeAllCookies {
                android.webkit.CookieManager.getInstance().flush()
                runOnUiThread {
                    authenticated = false
                    loading = false
                    openLogin()
                }
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun JSONObject.cancelled() = optString("status") == "cancelled"
    private fun JSONObject.completed() = optString("status") == "completed"
    private fun JSONObject.noShow() = optString("attendanceOutcome") == "no_show"
    private fun displayName(b: JSONObject) = b.optString("name").trim().ifBlank { "Khách hàng" }

    private fun durationMinutes(b: JSONObject): Int = try {
        Duration.between(LocalTime.parse(b.optString("time")), LocalTime.parse(b.optString("endTime"))).toMinutes().toInt().coerceAtLeast(0)
    } catch (_: Exception) { 0 }

    private fun tierTitle(value: String) = when (value) {
        "silver" -> "Trung bình"
        "gold" -> "Cao"
        "vip" -> "VIP"
        else -> "Thường"
    }

    private fun normalizePhone(input: String): String? {
        var text = input.trim()
        if(text.isEmpty()) return null
        if(text.contains("://")) {
            val uri = runCatching { android.net.Uri.parse(text) }.getOrNull() ?: return null
            if(!uri.scheme.equals("https",true) || !uri.host.equals("housenail.sk",true) ||
                uri.userInfo != null || (uri.port != -1 && uri.port != 443) ||
                uri.path !in listOf("/admin.html","/admin")) return null
            val parts = uri.queryParameterNames
            if("memberPhone" !in parts) return null
            val values = uri.getQueryParameters("memberPhone")
            if(values.size != 1) return null
            text = values[0]
        }
        if(!text.matches(Regex("^[+0-9() .-]+$"))) return null
        var digits = text.filter { it in '0'..'9' }
        if(digits.startsWith("00421")) digits = "0" + digits.drop(5)
        else if(digits.startsWith("421")) digits = "0" + digits.drop(3)
        return digits.takeIf { it.length in 8..15 }
    }

    data class Palette(
        val paper: Color,
        val surface: Color,
        val ink: Color,
        val accent: Color,
        val champagne: Color,
        val secondary: Color,
        val border: Color
    )

    private fun palette(name: String) = if (name == "obsidian") {
        Palette(Color(0xFF0F1113), Color(0xFF1F2126), Color(0xFFF5EFE3), Color(0xFFD6B378), Color(0xFFD6B070), Color(0xFFB9ACA4), Color(0x665C514A))
    } else {
        Palette(Color(0xFFFBF9F6), Color.White, Color(0xFF382B33), Color(0xFF8F6E45), Color(0xFFC29966), Color(0xFF7E6F68), Color(0x40C29966))
    }

    private fun Color.toArgbCompat() = AndroidColor.argb(
        (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()
    )
}
