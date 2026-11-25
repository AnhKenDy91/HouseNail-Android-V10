package sk.housenail.manager

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun StaffManagementScreen(api: ApiClient, onLogin: () -> Unit) {
    val scope = rememberCoroutineScope()
    var staff by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var audits by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var reset by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var success by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<JSONObject?>(null) }
    fun rows(obj: JSONObject): List<JSONObject> {
        val array = obj.optJSONArray("items") ?: throw IllegalStateException("Thiếu danh sách từ máy chủ.")
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }
    suspend fun refresh() {
        val result = withContext(Dispatchers.IO) {
            rows(api.staffAuthAction("staffList", JSONObject())) to rows(api.staffAuthAction("staffAudit", JSONObject()))
        }
        staff = result.first; audits = result.second
        if (staff.none { it.optString("id") == selected }) selected = ""
    }
    fun execute(action: String = "") {
        if (busy) return
        busy = true; error = ""; success = ""
        val person = pending
        val account = staff.firstOrNull { it.optString("id") == selected }
        scope.launch {
            try {
                if (action.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        val fields = when(action) {
                            "staffCreate" -> {
                                val normalized = email.trim().lowercase(Locale.ROOT)
                                JSONObject().put("name", name.trim()).put("email", normalized)
                                    .put("password", ApiClient.makePasswordProof(normalized, password))
                            }
                            "staffSetDisabled" -> JSONObject().put("id", requireNotNull(person).optString("id"))
                                .put("disabled", person.optInt("disabled") == 0)
                            "staffResetPassword" -> JSONObject().put("id", requireNotNull(account).optString("id"))
                                .put("password", ApiClient.makePasswordProof(account.optString("email"), reset))
                            else -> error("Unsupported action")
                        }
                        api.staffAuthAction(action, fields)
                    }
                    success = "Máy chủ đã xác nhận thay đổi."
                    if (action == "staffCreate") { name = ""; email = "" }
                }
                refresh()
            } catch (e: CancellationException) { throw e }
            catch (_: ApiClient.AuthException) { onLogin() }
            catch (e: Exception) { error = if (action.isEmpty()) e.message.orEmpty() else "Kiểm tra lại danh sách trước khi thử lại: " + e.message.orEmpty() }
            finally { password = ""; reset = ""; pending = null; busy = false }
        }
    }
    LaunchedEffect(Unit) { execute() }
    if (confirm.isNotBlank()) AlertDialog(onDismissRequest = { confirm = ""; pending = null },
        title = { Text(when(confirm) { "staffCreate" -> "Xác nhận tạo tài khoản"; "staffResetPassword" -> "Xác nhận đặt lại mật khẩu"; else -> "Xác nhận thay đổi quyền" }) },
        text = { Text(if (confirm == "staffCreate") "Chỉ tạo tài khoản khi tên và email chính xác." else "Khóa hoặc đổi mật khẩu sẽ thu hồi các phiên đăng nhập cũ.") },
        confirmButton = { TextButton(onClick = { val action = confirm; confirm = ""; execute(action) }) { Text("Xác nhận") } },
        dismissButton = { TextButton(onClick = { confirm = ""; pending = null }) { Text("Bỏ qua") } })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Tài khoản nhân viên", fontFamily = FontFamily.Serif, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text("Mọi quyền được xác minh bởi máy chủ HouseNail.", fontSize = 14.sp)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (success.isNotBlank()) Text(success, color = MaterialTheme.colorScheme.primary)
        StaffSection("Thêm nhân viên") {
            OutlinedTextField(name, { name = it }, label = { Text("Tên nhân viên") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(email, { email = it }, label = { Text("Email đăng nhập") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("Mật khẩu riêng (12–128 ký tự)") }, enabled = !busy, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(onClick = { confirm = "staffCreate" }, enabled = !busy && name.trim().length >= 2 && email.contains("@") && password.length in 12..128, modifier = Modifier.fillMaxWidth()) { Text("Tạo tài khoản nhân viên") }
        }
        StaffSection("Danh sách nhân viên") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${staff.size} tài khoản", modifier = Modifier.weight(1f))
                TextButton(onClick = { execute() }, enabled = !busy) { Text("Làm mới") }
            }
            if (staff.isEmpty() && !busy) Text("Chưa có nhân viên hoặc chưa tải được danh sách.", fontSize = 12.sp)
            staff.forEach { person ->
                val locked = person.optInt("disabled") != 0
                Text(person.optString("name"), fontWeight = FontWeight.SemiBold)
                Text(person.optString("email"), fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (locked) "Đã khóa" else "Đang hoạt động", color = if (locked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { pending = person; confirm = "staffSetDisabled" }, enabled = !busy) { Text(if (locked) "Mở khóa" else "Khóa") }
                }
                HorizontalDivider()
            }
        }
        StaffSection("Đổi mật khẩu nhân viên") {
            staff.forEach { person ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == person.optString("id"), onClick = { selected = person.optString("id") }, enabled = !busy)
                    Text(person.optString("name") + " · " + person.optString("email"), fontSize = 13.sp)
                }
            }
            OutlinedTextField(reset, { reset = it }, label = { Text("Mật khẩu mới (12–128 ký tự)") }, enabled = !busy, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Text("Đổi mật khẩu sẽ thu hồi tất cả phiên đăng nhập cũ.", fontSize = 12.sp)
            OutlinedButton(onClick = { confirm = "staffResetPassword" }, enabled = !busy && selected.isNotBlank() && reset.length in 12..128) { Text("Đổi mật khẩu và đăng xuất thiết bị") }
        }
        StaffSection("Nhật ký thao tác") {
            if (audits.isEmpty()) Text("Chưa có thao tác hoặc chưa tải được nhật ký.", fontSize = 12.sp)
            audits.forEach { event ->
                Text(event.optString("name").ifBlank { event.optString("email", "Nhân viên") }, fontWeight = FontWeight.SemiBold)
                Text(event.optString("action") + event.optString("bookingId").takeIf { it.isNotBlank() && it != "null" }?.let { " · " + it }.orEmpty(), fontSize = 12.sp)
                val time = runCatching { Instant.ofEpochSecond(event.optLong("at")).atZone(ZoneId.of("Europe/Bratislava")).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) }.getOrDefault("")
                Text(time, fontSize = 11.sp)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun StaffSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(17.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
