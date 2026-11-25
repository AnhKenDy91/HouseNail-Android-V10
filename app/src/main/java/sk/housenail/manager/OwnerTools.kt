@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package sk.housenail.manager

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private val ownerToolLabels = linkedMapOf(
    "hours" to "Giờ làm & ngày nghỉ",
    "duration" to "Thời lượng dịch vụ",
    "notice" to "Thông báo website",
    "theme" to "Chủ đề website",
    "loyalty" to "Đối chiếu tích điểm",
    "cleanup" to "Xóa lịch"
)

private data class OpeningHours(val enabled: Boolean, val start: String, val end: String)
private data class DurationInput(val id: String, val name: String, val minutes: String)

@Composable
internal fun OwnerTools(
    request: suspend (String, JSONArray) -> JSONObject,
    mode: String,
    choose: (String) -> Unit,
    onLogin: () -> Unit,
    onUpdated: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val today = LocalDate.now(ZoneId.of("Europe/Bratislava")).toString()
    var busy by remember(mode) { mutableStateOf(false) }
    var loaded by remember(mode) { mutableStateOf(false) }
    var message by remember(mode) { mutableStateOf("") }
    var hours by remember(mode) { mutableStateOf(List(7) { OpeningHours(false, "09:00", "18:00") }) }
    var blocks by remember(mode) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var blockDate by rememberSaveable { mutableStateOf(today) }
    var blockReason by rememberSaveable { mutableStateOf("") }
    var durations by remember(mode) { mutableStateOf<List<DurationInput>>(emptyList()) }
    var durationRevision by remember(mode) { mutableStateOf("") }
    var notice by remember(mode) { mutableStateOf(JSONObject()) }
    var noticeEnabled by remember(mode) { mutableStateOf(false) }
    var noticeType by remember(mode) { mutableStateOf("promotion") }
    var noticeExpires by remember(mode) { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf("sk") }
    var noticeTitle by remember(mode) { mutableStateOf("") }
    var noticeBody by remember(mode) { mutableStateOf("") }
    var theme by remember(mode) { mutableStateOf("default") }
    var stats by remember(mode) { mutableStateOf(JSONObject()) }
    var from by rememberSaveable { mutableStateOf(today) }
    var to by rememberSaveable { mutableStateOf(today) }
    var cleanupMode by rememberSaveable { mutableStateOf("range") }
    var cleanupPhone by rememberSaveable { mutableStateOf("") }
    var cleanupId by rememberSaveable { mutableStateOf("") }
    var cleanupFuture by rememberSaveable { mutableStateOf(false) }
    var confirmCleanup by remember { mutableStateOf(false) }
    val cleanupModes = linkedMapOf("range" to "Khoảng ngày", "phone" to "Theo khách", "booking" to "Một lịch", "all" to "Toàn bộ")
    val validCleanup = when (cleanupMode) {
        "range" -> runCatching { LocalDate.parse(from) <= LocalDate.parse(to) }.getOrDefault(false)
        "phone" -> cleanupPhone.matches(Regex("^[+0-9() .-]{8,25}$")) && cleanupPhone.count(Char::isDigit) in 8..15
        "booking" -> cleanupId.matches(Regex("^[a-zA-Z0-9_:-]{1,160}$"))
        else -> true
    }

    fun selectLanguage(code: String) {
        language = code
        val copy = notice.optJSONObject("translations")?.optJSONObject(code)
        noticeTitle = copy?.optString("title").orEmpty()
        noticeBody = copy?.optString("body").orEmpty()
    }

    suspend fun load() {
        val action = when (mode) {
            "hours" -> "adminConfig"
            "duration" -> "adminServiceDurations"
            "notice" -> "adminWebsiteNotice"
            "theme" -> "adminBookingConfig"
            "loyalty" -> "adminLoyaltyAccounting"
            else -> return
        }
        val data = request(action, JSONArray())
        when (mode) {
            "hours" -> {
                val week = data.optJSONObject("hours") ?: JSONObject()
                hours = (0..6).map { day ->
                    val entry = week.optJSONObject(day.toString())
                    OpeningHours(entry != null, entry?.optString("start") ?: "09:00", entry?.optString("end") ?: "18:00")
                }
                val rows = data.optJSONArray("blocks") ?: JSONArray()
                blocks = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            }
            "duration" -> {
                val rows = data.optJSONArray("services") ?: JSONArray()
                durations = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.map {
                    DurationInput(it.getString("id"), it.optString("name"), it.getInt("minutes").toString())
                }
                durationRevision = data.getString("revision")
            }
            "notice" -> {
                notice = data
                noticeEnabled = data.optBoolean("enabled")
                noticeType = data.optString("type", "promotion")
                noticeExpires = data.optString("expires")
                selectLanguage(language)
            }
            "theme" -> theme = data.optString("campaignTheme", "default")
            "loyalty" -> stats = data
        }
        loaded = true
    }

    fun run(action: String? = null, args: JSONArray = JSONArray()) {
        if (busy) return
        busy = true
        message = ""
        scope.launch {
            try {
                val result = if (action != null) request(action, args) else null
                if (action == "adminCleanupDelete") {
                    message = "Đã xóa ${result?.optInt("deleted") ?: 0} lịch. Điểm tích lũy vẫn được giữ."
                } else {
                    load()
                    if (action != null) message = "Đã ghi và đọc lại dữ liệu trên máy chủ."
                }
                if (action != null) onUpdated()
            } catch (e: CancellationException) {
                throw e
            } catch (_: ApiClient.AuthException) {
                onLogin()
            } catch (e: Exception) {
                message = if (action == null) e.message ?: "Không tải được dữ liệu."
                    else "Chưa xác nhận được thay đổi. Hãy tải lại trước khi thử tiếp. " + e.message.orEmpty()
            } finally { busy = false }
        }
    }

    LaunchedEffect(mode) { if (mode in ownerToolLabels && mode != "cleanup") run() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(ownerToolLabels[mode] ?: "Quản trị website", style = MaterialTheme.typography.headlineSmall)
        if (mode !in ownerToolLabels) {
            ownerToolLabels.forEach { (key, label) ->
                OutlinedButton(onClick = { choose(key) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
        } else {
            TextButton(onClick = { choose("") }, enabled = !busy) { Text("Về công cụ quản trị") }
        }
        if (message.isNotBlank()) Text(message)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (mode in ownerToolLabels && mode != "cleanup") {
            TextButton(onClick = { run() }, enabled = !busy) { Text("Tải lại từ website") }
        }
        when (mode) {
            "hours" -> if (loaded) {
                val names = listOf("Chủ Nhật", "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy")
                hours.forEachIndexed { index, entry ->
                    fun edit(next: OpeningHours) { hours = hours.toMutableList().apply { set(index, next) } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(names[index], modifier = Modifier.weight(1f))
                        Switch(entry.enabled, { edit(entry.copy(enabled = it)) }, enabled = !busy)
                    }
                    if (entry.enabled) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(entry.start, { edit(entry.copy(start = it)) }, modifier = Modifier.weight(1f), label = { Text("Mở cửa") }, singleLine = true, enabled = !busy)
                        OutlinedTextField(entry.end, { edit(entry.copy(end = it)) }, modifier = Modifier.weight(1f), label = { Text("Đóng cửa") }, singleLine = true, enabled = !busy)
                    }
                }
                val valid = hours.filter { it.enabled }.all { h ->
                    runCatching { LocalTime.parse(h.start) < LocalTime.parse(h.end) }.getOrDefault(false)
                }
                Button(enabled = !busy && valid, onClick = {
                    val payload = JSONObject()
                    hours.forEachIndexed { index, h ->
                        payload.put(index.toString(), if (h.enabled) JSONObject().put("start", h.start).put("end", h.end) else JSONObject.NULL)
                    }
                    run("adminHours", JSONArray().put(payload))
                }) { Text("Lưu giờ làm") }
                HorizontalDivider()
                Text("Khóa lịch theo ngày", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(blockDate, { blockDate = it }, label = { Text("Ngày · yyyy-MM-dd") }, enabled = !busy, singleLine = true)
                OutlinedTextField(blockReason, { blockReason = it.take(120) }, label = { Text("Lý do") }, enabled = !busy)
                Button(onClick = { run("adminBlock", JSONArray().put(blockDate).put("00:00").put("24:00").put(blockReason.trim())) },
                    enabled = !busy && runCatching { LocalDate.parse(blockDate) }.isSuccess) { Text("Khóa cả ngày") }
                blocks.forEach { block ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(block.optString("date") + " · " + block.optString("reason"), modifier = Modifier.weight(1f))
                        TextButton(enabled = !busy && block.optString("id").isNotBlank(), onClick = {
                            run("adminUnblock", JSONArray().put(block.optString("id")))
                        }) { Text("Mở khóa") }
                    }
                }
            }
            "duration" -> if (loaded) {
                durations.forEachIndexed { index, row ->
                    OutlinedTextField(row.minutes, { value ->
                        durations = durations.toMutableList().apply { set(index, row.copy(minutes = value.filter(Char::isDigit).take(3))) }
                    }, label = { Text(row.name + " · phút") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                }
                val valid = durations.isNotEmpty() && durations.all { (it.minutes.toIntOrNull() ?: 0) in 5..720 }
                Button(enabled = !busy && valid && durationRevision.isNotBlank(), onClick = {
                    val rows = JSONArray()
                    durations.forEach { rows.put(JSONObject().put("id", it.id).put("minutes", it.minutes.toInt())) }
                    run("adminSetServiceDurations", JSONArray().put(JSONObject().put("revision", durationRevision).put("services", rows)))
                }) { Text("Lưu thời lượng") }
            }
            "notice" -> if (loaded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Hiển thị trên web", modifier = Modifier.weight(1f))
                    Switch(noticeEnabled, { noticeEnabled = it }, enabled = !busy)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("sk", "en", "vi").forEach { code ->
                        FilterChip(selected = language == code, onClick = {
                            val translations = notice.optJSONObject("translations") ?: JSONObject()
                            translations.put(language, JSONObject().put("title", noticeTitle).put("body", noticeBody))
                            notice.put("translations", translations)
                            selectLanguage(code)
                        }, label = { Text(code.uppercase()) }, enabled = !busy)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(noticeType == "promotion", { noticeType = "promotion" }, { Text("Khuyến mại") }, enabled = !busy)
                    FilterChip(noticeType == "service", { noticeType = "service" }, { Text("Dịch vụ") }, enabled = !busy)
                }
                OutlinedTextField(noticeExpires, { noticeExpires = it.take(10) }, label = { Text("Ngày kết thúc · yyyy-MM-dd") },
                    supportingText = { Text("Để trống nếu không giới hạn ngày.") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(noticeTitle, { noticeTitle = it.take(100) }, label = { Text("Tiêu đề") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(noticeBody, { noticeBody = it.take(600) }, label = { Text("Nội dung") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy, onClick = {
                    val payload = JSONObject(notice.toString())
                    val translations = payload.optJSONObject("translations") ?: JSONObject()
                    translations.put(language, JSONObject().put("title", noticeTitle.trim()).put("body", noticeBody.trim()))
                    payload.put("translations", translations).put("enabled", noticeEnabled)
                        .put("type", noticeType).put("expires", noticeExpires.trim())
                        .put("revision", notice.optString("revision"))
                    run("adminSaveWebsiteNotice", JSONArray().put(payload))
                }) { Text("Lưu thông báo") }
            }
            "theme" -> if (loaded) {
                Text(if (theme == "christmas") "Đang dùng: Giáng sinh" else "Đang dùng: Mặc định")
                OutlinedButton(enabled = !busy && theme != "default", onClick = {
                    run("adminSetCampaignTheme", JSONArray().put("default"))
                }) { Text("Mặc định") }
                OutlinedButton(enabled = !busy && theme != "christmas", onClick = {
                    run("adminSetCampaignTheme", JSONArray().put("christmas"))
                }) { Text("Giáng sinh") }
            }
            "loyalty" -> if (loaded) {
                Text("Tổng lượt trên thẻ: " + stats.optInt("totalVisits"))
                Text("Lượt gắn lịch hoàn thành: " + stats.optInt("bookingLinkedVisits"))
                Text("Lượt chưa gắn lịch: " + stats.optInt("unlinkedVisits"))
                if (stats.optInt("overLinkedVisits") > 0) Text("Có chênh lệch cần đối chiếu.")
            }
            "cleanup" -> {
                Text("Chọn phạm vi xóa. Lịch đã xóa không thể khôi phục từ màn này.")
                cleanupModes.entries.chunked(2).forEach { group ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        group.forEach { (key, label) ->
                            FilterChip(cleanupMode == key, { cleanupMode = key }, { Text(label) }, enabled = !busy)
                        }
                    }
                }
                when (cleanupMode) {
                    "range" -> {
                        OutlinedTextField(from, { from = it }, label = { Text("Từ ngày · yyyy-MM-dd") }, enabled = !busy, singleLine = true)
                        OutlinedTextField(to, { to = it }, label = { Text("Đến ngày · yyyy-MM-dd") }, enabled = !busy, singleLine = true)
                    }
                    "phone" -> OutlinedTextField(cleanupPhone, { cleanupPhone = it }, label = { Text("Số điện thoại khách") }, enabled = !busy, singleLine = true)
                    "booking" -> OutlinedTextField(cleanupId, { cleanupId = it }, label = { Text("Mã lịch hẹn") }, enabled = !busy, singleLine = true)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bao gồm lịch sắp tới", modifier = Modifier.weight(1f))
                    Switch(cleanupFuture, { cleanupFuture = it }, enabled = !busy)
                }
                OutlinedButton(enabled = validCleanup && !busy, onClick = { confirmCleanup = true }) { Text("Xóa lịch") }
            }
        }
    }
    if (confirmCleanup) AlertDialog(
        onDismissRequest = { confirmCleanup = false },
        title = { Text("Xác nhận xóa lịch · " + cleanupModes[cleanupMode]) },
        text = { Text(if (cleanupFuture) "Phạm vi có thể bao gồm lịch sắp tới. Điểm và thẻ vẫn được giữ." else "Điểm và thẻ thành viên vẫn được giữ. Chỉ xử lý lịch đến hôm nay.") },
        confirmButton = {
            TextButton(onClick = {
                confirmCleanup = false
                val filter = JSONObject().put("mode", cleanupMode).put("includeFuture", cleanupFuture)
                when (cleanupMode) {
                    "range" -> filter.put("from", from).put("to", to)
                    "phone" -> filter.put("phone", cleanupPhone)
                    "booking" -> filter.put("bookingId", cleanupId)
                }
                run("adminCleanupDelete", JSONArray().put(filter))
            }) { Text("Xác nhận xóa", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmCleanup = false }) { Text("Quay lại") } }
    )
}

@Composable
internal fun OwnerCustomerHistory(
    phone: String,
    request: suspend (String, JSONArray) -> JSONObject,
    onLogin: () -> Unit,
    onOpenCard: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var summary by remember(phone) { mutableStateOf<JSONObject?>(null) }
    var rows by remember(phone) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var page by remember(phone) { mutableStateOf(1) }
    var hasMore by remember(phone) { mutableStateOf(false) }
    var busy by remember(phone) { mutableStateOf(false) }
    var error by remember(phone) { mutableStateOf("") }
    var note by remember(phone) { mutableStateOf("") }
    var reason by remember(phone) { mutableStateOf("") }
    var days by remember(phone) { mutableStateOf(30) }
    var confirmRestriction by remember(phone) { mutableStateOf(false) }

    suspend fun read(reset: Boolean) {
        val requestedPage = if (reset) 1 else page + 1
        val response = request("adminCustomerHistory", JSONArray().put(phone).put(requestedPage))
        val profile = response.getJSONObject("summary")
        val items = response.optJSONArray("items") ?: JSONArray()
        val next = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
        rows = (if (reset) next else rows + next).distinctBy { it.optString("id") }
        summary = profile
        page = response.optInt("page", requestedPage)
        hasMore = response.optBoolean("hasMore")
        if (reset) note = profile.optJSONObject("control")?.optString("note").orEmpty()
    }

    fun launch(reset: Boolean = true, update: JSONObject? = null) {
        if (busy) return
        error = ""
        busy = true
        scope.launch {
            try {
                if (update == null) read(reset)
                else {
                    val profile = request("adminSetCustomerControls", JSONArray().put(phone).put(update))
                    summary = profile
                    note = profile.optJSONObject("control")?.optString("note").orEmpty()
                    reason = ""
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: ApiClient.AuthException) {
                onLogin()
            } catch (e: Exception) {
                error = e.message ?: "Chưa xác nhận được dữ liệu khách hàng."
                if (update != null) {
                    try { read(reset = true) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                }
            } finally { busy = false }
        }
    }

    LaunchedEffect(phone) { launch() }
    val profile = summary
    val controls = profile?.optJSONObject("control")
    val blocked = controls?.optBoolean("blocked") == true
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Hồ sơ khách", style = MaterialTheme.typography.headlineSmall)
        Text(phone)
        TextButton(onClick = { launch() }, enabled = !busy) { Text("Làm mới hồ sơ") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (profile != null) {
            Text("${profile.optInt("arrived")} đã đến · ${profile.optInt("noShows")} không đến · ${profile.optInt("cancelled")} đã hủy")
            val card = profile.optJSONObject("card")
            val cardId = card?.optString("cardId").orEmpty()
            if (cardId.isNotBlank() && !profile.optBoolean("ambiguous")) {
                OutlinedButton(onClick = { onOpenCard(cardId) }) { Text("Mở thẻ thành viên") }
            }
            if (controls != null && controls.has("revision")) {
                OutlinedTextField(note, { note = it.take(500) }, label = { Text("Ghi chú nội bộ") },
                    enabled = !busy, modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && note != controls.optString("note"), onClick = {
                    launch(update = JSONObject().put("scope", "note").put("note", note)
                        .put("expectedRevision", controls.optString("revision")))
                }) { Text("Lưu ghi chú") }
                Text(if (blocked) "Đang chặn đặt lịch" else "Được đặt lịch")
                if (!blocked) {
                    OutlinedTextField(reason, { reason = it.take(500) }, label = { Text("Lý do chặn số") }, enabled = !busy)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(7, 30, 0).forEach { length ->
                            FilterChip(selected = days == length, onClick = { days = length },
                                label = { Text(if (length == 0) "Đến khi mở" else "$length ngày") }, enabled = !busy)
                        }
                    }
                }
                OutlinedButton(onClick = { confirmRestriction = true }, enabled = !busy && (blocked || reason.isNotBlank())) {
                    Text(if (blocked) "Mở chặn số" else "Chặn đặt lịch")
                }
            }
        }
        HorizontalDivider()
        Text("Lịch sử đặt hẹn", style = MaterialTheme.typography.titleMedium)
        rows.forEach { row ->
            Text(row.optString("date") + " · " + row.optString("time") + " · " + row.optString("service"))
        }
        if (hasMore) TextButton(onClick = { launch(reset = false) }, enabled = !busy) { Text("Tải thêm lịch") }
    }
    if (confirmRestriction && controls != null) AlertDialog(
        onDismissRequest = { confirmRestriction = false },
        title = { Text(if (blocked) "Mở chặn số?" else "Chặn số đặt lịch?") },
        text = { Text("Lịch hiện có vẫn được giữ.") },
        confirmButton = {
            TextButton(onClick = {
                confirmRestriction = false
                launch(update = JSONObject().put("scope", "phone").put("locked", !blocked)
                    .put("reason", if (blocked) "" else reason.trim())
                    .put("days", if (blocked) 0 else days)
                    .put("expectedRevision", controls.optString("revision")))
            }) { Text("Xác nhận") }
        },
        dismissButton = { TextButton(onClick = { confirmRestriction = false }) { Text("Hủy") } }
    )
}
