package vn.nvxconnect.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import vn.nvxconnect.bluetooth.*
import java.util.concurrent.atomic.AtomicLong

/**
 * Máy trạng thái phiên GATT. Thuần Kotlin: mọi tương tác với Android đi qua GattPort nên test được trên JVM.
 *
 * Quy tắc:
 *  - Chỉ MỘT phiên hoạt động. connect() lần hai cho cùng thiết bị bị bỏ qua; khác thiết bị thì đóng phiên cũ.
 *  - Trình tự trạng thái: Connecting → Connected → DiscoveringServices → Ready. Mất kết nối: Reconnecting (có giới hạn) → Error.
 *  - Mọi thao tác GATT đi qua hàng đợi tuần tự, mỗi thao tác có watchdog.
 *  - Xe đòi xác thực/mã hóa (status 5/15): DỪNG, báo lỗi, không thử lại, không tìm cách vượt qua.
 *  - Không có đường ghi dữ liệu: chỉ ghi CCCD khi người dùng bấm "Nghe".
 */
class GattSessionController(
    private val port: GattPort,
    private val scope: CoroutineScope,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    private val connectTimeoutMs: Long = 15_000,
    private val preferredMtu: Int = 247,
    private val rssiPollMs: Long = 3_000,
    private val opTimeoutMs: Long = 5_000,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Characteristic cần tự bật nghe ngay khi Ready (chỉ NOTIFY/INDICATE; mặc định không tự nghe gì). */
    private val autoListen: (List<GattServiceInfo>) -> List<String> = { emptyList() },
    /** Nhận gói ngay trên luồng callback (không chặn). null = chỉ phát qua [frames]. */
    private val sink: FrameSink? = null,
    /** Đồng hồ đơn điệu (ns) để đo khoảng cách giữa các gói và thời gian kết nối liên tục. */
    private val mono: () -> Long = System::nanoTime,
    private val random: () -> Double = Math::random,
    /** Nhận sự kiện kết nối có cấu trúc (không chặn). sessionId/deviceKey để trống: bộ ghi bên ngoài điền. null = không ghi. */
    private val eventSink: vn.nvxconnect.telemetry.ConnectionEventSink? = null
) : VehicleConnection, GattEvents {

    private val lock = Any()
    private val _state = MutableStateFlow<ConnState>(ConnState.Disconnected)
    override val state: StateFlow<ConnState> = _state.asStateFlow()
    private val _frames = MutableSharedFlow<RawFrame>(extraBufferCapacity = 256)
    override val frames: Flow<RawFrame> = _frames.asSharedFlow()
    private val _log = MutableSharedFlow<LogEntry>(extraBufferCapacity = 256)
    override val log: Flow<LogEntry> = _log.asSharedFlow()
    private val _rssi = MutableStateFlow<Int?>(null)
    override val rssi: StateFlow<Int?> = _rssi.asStateFlow()
    private val _mtu = MutableStateFlow(23)
    override val mtu: StateFlow<Int> = _mtu.asStateFlow()
    private val _subs = MutableStateFlow<Map<String, SubscriptionState>>(emptyMap())
    override val subscriptions: StateFlow<Map<String, SubscriptionState>> = _subs.asStateFlow()
    private val _info = MutableStateFlow<Map<String, String>>(emptyMap())
    override val deviceInfo: StateFlow<Map<String, String>> = _info.asStateFlow()

    private val _hint = MutableStateFlow(LinkHint.NONE)
    override val hint: StateFlow<LinkHint> = _hint.asStateFlow()

    private var target: String? = null
    private var attempt = 0
    /** Số gói notify nhận được trong phiên GATT hiện tại; 0 = xe chưa phát gì. Atomic vì onNotification không giữ khóa. */
    private val sessionFrames = AtomicLong(0)
    /** Số thứ tự phiên GATT: tăng mỗi lần mở lại GATT. Gói/sự kiện của phiên cũ không được lẫn vào phiên mới. */
    @Volatile private var epoch = 0
    private val lateEvents = AtomicLong(0)
    private val uiDropped = AtomicLong(0)
    @Volatile private var charService: Map<String, String> = emptyMap()
    @Volatile private var indicateOnly: Set<String> = emptySet()
    private val _history = MutableStateFlow<List<LinkEvent>>(emptyList())
    override val linkHistory: StateFlow<List<LinkEvent>> = _history.asStateFlow()
    private val _readySince = MutableStateFlow<Long?>(null)
    override val readySinceNs: StateFlow<Long?> = _readySince.asStateFlow()
    private val _diag = MutableStateFlow(LinkDiagnostics())
    override val diagnostics: StateFlow<LinkDiagnostics> = _diag.asStateFlow()
    private var readyAtWall = 0L
    override val address: String? get() = synchronized(lock) { if (userStopped) null else target }
    /** Đã từng Ready (thấy xe) kể từ lần connect() do người dùng yêu cầu. */
    private var everReady = false
    /** Characteristic người dùng chủ động tắt nghe: không tự bật lại. */
    private val userOff = HashSet<String>()
    @Volatile private var userStopped = false
    private var timeoutJob: Job? = null
    private var retryJob: Job? = null
    private var rssiJob: Job? = null
    private var opWatchdog: Job? = null
    private var opSeq = 0L
    private var services: List<GattServiceInfo> = emptyList()
    private val desired = LinkedHashSet<String>()
    private val pendingCccd = HashMap<String, Boolean>()
    private val queue = GattOpQueue { name -> onOpStartFailed(name) }

    init { port.events = this }

    // ---------------------------------------------------------------- nhật ký
    private fun emitLog(kind: LogKind, msg: String, uuid: String? = null, hex: String? = null) {
        _log.tryEmit(LogEntry(clock(), kind, msg, uuid, _state.value.code, hex))
    }
    private fun setState(s: ConnState, msg: String? = null) {
        _state.value = s
        _log.tryEmit(LogEntry(clock(), LogKind.STATE, msg ?: s.code, null, s.code, null))
    }
    private fun norm(u: String) = u.lowercase()

    // ---------------------------------------------------------------- API
    override fun connect(address: String) = synchronized(lock) {
        if (!AddressKind.isValid(address)) {
            setState(ConnState.Error(ErrorKind.INVALID_ADDRESS, "Địa chỉ thiết bị không hợp lệ"), "Địa chỉ không hợp lệ")
            return@synchronized
        }
        if (_state.value.isActive) {
            if (address.equals(this.target, ignoreCase = true)) {
                emitLog(LogKind.INFO, "Đã có phiên GATT tới thiết bị này, bỏ qua yêu cầu kết nối")
                return@synchronized
            }
            emitLog(LogKind.INFO, "Chuyển sang thiết bị khác: đóng phiên GATT cũ trước")
            cancelTimers(); teardown()
        }
        cancelTimers()
        userStopped = false; attempt = 0; this.target = address
        sessionFrames.set(0); everReady = false; userOff.clear(); _hint.value = LinkHint.NONE
        ev(vn.nvxconnect.telemetry.EventKinds.CONNECT_ATTEMPT, vn.nvxconnect.telemetry.EventOutcome.INFO, null, "Yêu cầu kết nối")
        sink?.onSessionStart()
        desired.clear(); _subs.value = emptyMap(); _info.value = emptyMap()
        open()
    }

    override fun disconnect() = synchronized(lock) {
        if (_state.value.isActive) pushHistory(LinkEventKind.USER_DISCONNECT, null, "Người dùng ngắt kết nối")
        userStopped = true
        cancelTimers(); teardown()
        desired.clear(); _subs.value = emptyMap(); _hint.value = LinkHint.NONE
        setState(ConnState.Disconnected, "Người dùng ngắt kết nối")
    }

    override fun subscribe(charUuid: String): Boolean = synchronized(lock) {
        if (_state.value !is ConnState.Ready) return@synchronized false
        val ch = findChar(charUuid) ?: return@synchronized false
        if (!ch.canSubscribe) return@synchronized false
        val u = norm(ch.uuid)
        userOff -= u
        desired += u; setSub(u, SubscriptionState.PENDING)
        enqueueCccd(u, true)
        true
    }

    override fun unsubscribe(charUuid: String): Boolean = synchronized(lock) {
        if (_state.value !is ConnState.Ready) return@synchronized false
        val ch = findChar(charUuid) ?: return@synchronized false
        val u = norm(ch.uuid)
        desired -= u; userOff += u
        enqueueCccd(u, false)
        true
    }

    override fun readStandardInfo(charUuid: String): Boolean = synchronized(lock) {
        if (_state.value !is ConnState.Ready) return@synchronized false
        if (!StandardInfo.isAllowed(charUuid)) {
            emitLog(LogKind.ERROR, "Từ chối đọc $charUuid: không nằm trong danh sách thông tin chuẩn được phép")
            return@synchronized false
        }
        val ch = findChar(charUuid) ?: return@synchronized false
        if (!ch.canRead) return@synchronized false
        val u = norm(ch.uuid)
        op("read $u") { port.readCharacteristic(u) }
        true
    }

    // ---------------------------------------------------------------- vòng đời phiên
    private fun open() {
        val addr = target ?: return
        epoch++; _diag.value = _diag.value.copy(epoch = epoch)
        teardown()
        setState(if (attempt == 0 && !everReady) ConnState.Connecting else ConnState.Reconnecting(attempt, policy.reportedMax),
            if (attempt == 0 && !everReady) "Bắt đầu kết nối" else "Kết nối lại lần $attempt/${if (policy.persistent) "∞" else policy.maxAttempts.toString()}")
        // Chế độ xe: sau BACKGROUND_AFTER lần không thấy xe (khóa điện tắt → xe không quảng bá) chuyển sang kết nối NỀN của Android:
        // hệ thống tự nối ngay khi xe quảng bá lại (bật khóa), không có khoảng hở giữa các lần thử, không còn hết giờ.
        val background = policy.persistent && attempt >= BACKGROUND_AFTER
        when (val r = port.open(addr, background)) {
            is PortOpen.Failed -> { failNow(r.kind, r.reason); return }
            PortOpen.Started -> {}
        }
        if (background) emitLog(LogKind.INFO, "Chờ xe ở chế độ kết nối nền (tự nối khi bật khóa điện)")
        else timeoutJob = scope.launch { delay(connectTimeoutMs); onConnectTimeout() }
    }

    private fun onConnectTimeout() = synchronized(lock) {
        if (userStopped || _state.value is ConnState.Ready) return@synchronized
        // Đã từng thấy xe trong lần này → nhiều khả năng khóa điện vừa tắt; chưa từng thấy → có thể xe ngoài tầm/đang bị app khác giữ.
        _hint.value = if (everReady) LinkHint.WAITING_IGNITION else LinkHint.NOT_IN_RANGE
        fail(-1, "Hết thời gian chờ kết nối (${connectTimeoutMs / 1000}s)", ErrorKind.TIMEOUT)
    }

    /** Lỗi không thể khắc phục bằng thử lại (Bluetooth tắt, thiếu quyền, địa chỉ sai). */
    private fun failNow(kind: ErrorKind, reason: String) {
        pushHistory(LinkEventKind.ATTEMPT_FAILED, null, reason)
        cancelTimers(); teardown()
        if (userStopped) return
        setError(kind, reason)
    }

    private fun fail(status: Int, reason: String, kind: ErrorKind) {
        if (!userStopped) pushHistory(
            if (!GattStatus.retriable(status)) LinkEventKind.AUTH_REQUIRED else if (readyAtMono != null) LinkEventKind.LINK_LOST else LinkEventKind.ATTEMPT_FAILED,
            status.takeIf { it != -1 }, reason)
        cancelTimers(); teardown()
        if (userStopped) return
        if (!GattStatus.retriable(status)) {
            setError(ErrorKind.AUTH_REQUIRED, "$reason — ${GattStatus.describe(status)}. App không ghép nối/xác thực thay Y-Connect.")
            return
        }
        // Xe tự ngắt (19) nghĩa là xe đang ở ngay đó: thử lại ngay (log thật: nối lại ~1,9 s) và không tính vào số lần thất bại.
        val remoteDrop = policy.persistent && status == GattStatus.REMOTE_TERMINATED
        if (remoteDrop) attempt = 0
        val d = if (remoteDrop) REMOTE_DROP_RETRY_MS else policy.nextDelayJitteredMs(attempt, random())
        if (d == null) {
            val finalKind = if (kind == ErrorKind.TIMEOUT || GattStatus.looksLikeOutOfRange(status)) ErrorKind.OUT_OF_RANGE else kind
            setError(finalKind, "$reason (đã thử $attempt lần). Thiết bị có thể ngoài phạm vi, đang tắt, hoặc đang được ứng dụng khác (Y-Connect) giữ kết nối.")
            return
        }
        attempt++
        setState(ConnState.Reconnecting(attempt, policy.reportedMax), "$reason; thử lại sau $d ms (lần $attempt/${if (policy.persistent) "∞" else policy.maxAttempts.toString()})")
        retryJob = scope.launch {
            delay(d)
            synchronized(lock) { if (!userStopped && _state.value is ConnState.Reconnecting) open() }
        }
    }

    private fun setError(kind: ErrorKind, reason: String) {
        _subs.value = emptyMap()
        setState(ConnState.Error(kind, reason), "${kind.title}: $reason")
    }

    /** Mốc đồng hồ đơn điệu lúc đạt Ready (null nếu chưa). Dùng để tính thời gian liên kết sống khi ghi lịch sử. */
    @Volatile private var readyAtMono: Long? = null

    private fun ev(kind: String, outcome: vn.nvxconnect.telemetry.EventOutcome, status: Int? = null, detail: String = "", attrs: Map<String, String> = emptyMap()) {
        val s = eventSink ?: return
        try { s.onEvent(vn.nvxconnect.telemetry.ConnectionEvent(clock(), mono(), "", epoch, null, kind, outcome, status, detail, attrs)) } catch (_: Exception) { /* ghi nhật ký không được làm hỏng phiên GATT */ }
    }

    private fun pushHistory(kind: LinkEventKind, status: Int?, reason: String) {
        val up = readyAtMono?.let { (mono() - it) / 1_000_000 }
        readyAtMono = null; _readySince.value = null
        val e = LinkEvent(clock(), epoch, kind, status, reason, up, attempt, sessionFrames.get())
        _history.value = (_history.value + e).takeLast(MAX_HISTORY)
        val k = vn.nvxconnect.telemetry.EventKinds
        val extra = buildMap { up?.let { put("upMs", it.toString()) }; put("attempt", attempt.toString()); put("framesInSession", e.framesInSession.toString()) }
        when (kind) {
            LinkEventKind.ATTEMPT_FAILED -> ev(k.CONNECT_ATTEMPT, vn.nvxconnect.telemetry.EventOutcome.FAILURE, status, reason, extra)
            LinkEventKind.LINK_LOST -> ev(k.DISCONNECT, vn.nvxconnect.telemetry.EventOutcome.FAILURE, status, reason, extra)
            LinkEventKind.USER_DISCONNECT -> ev(k.DISCONNECT, vn.nvxconnect.telemetry.EventOutcome.INFO, status, reason, extra)
            LinkEventKind.BLUETOOTH_OFF -> ev(k.ERROR, vn.nvxconnect.telemetry.EventOutcome.FAILURE, status, reason, extra)
            LinkEventKind.AUTH_REQUIRED -> ev(k.BOND, vn.nvxconnect.telemetry.EventOutcome.FAILURE, status, reason, extra)
            else -> ev(k.ERROR, vn.nvxconnect.telemetry.EventOutcome.INFO, status, reason, extra)
        }
    }

    private fun cancelTimers() { timeoutJob?.cancel(); retryJob?.cancel(); timeoutJob = null; retryJob = null }

    private fun teardown() {
        opWatchdog?.cancel(); rssiJob?.cancel(); opWatchdog = null; rssiJob = null
        queue.clear(); pendingCccd.clear()
        _rssi.value = null; _mtu.value = 23; services = emptyList(); charService = emptyMap(); indicateOnly = emptySet()
        if (desired.isNotEmpty()) _subs.value = desired.associateWith { SubscriptionState.PENDING }
        port.close()
    }

    // ---------------------------------------------------------------- hàng đợi + watchdog
    private fun op(name: String, start: () -> Boolean) {
        queue.enqueue(name) {
            val ok = start()
            if (ok) {
                opWatchdog?.cancel()
                val seq = ++opSeq
                opWatchdog = scope.launch { delay(opTimeoutMs); onOpTimeout(name, seq) }
            }
            ok
        }
    }

    private fun done(prefix: String) {
        if (queue.runningName()?.startsWith(prefix) == true) opWatchdog?.cancel()
        queue.complete(prefix)
    }

    private fun onOpTimeout(name: String, seq: Long) = synchronized(lock) {
        if (seq != opSeq || queue.runningName() != name) return@synchronized
        emitLog(LogKind.ERROR, "Thao tác '$name' quá $opTimeoutMs ms")
        when (name.substringBefore(' ')) {
            "discover" -> fail(-1, "Khám phá dịch vụ quá thời gian", ErrorKind.SERVICE_DISCOVERY_FAILED)
            "cccd" -> { pendingCccd.remove(name.substringAfter(' ')); setSub(name.substringAfter(' '), SubscriptionState.FAILED); queue.complete("cccd") }
            else -> queue.complete(name.substringBefore(' '))
        }
    }

    private fun onOpStartFailed(name: String) {
        emitLog(LogKind.ERROR, "Không bắt đầu được thao tác '$name'")
        when (name.substringBefore(' ')) {
            "discover" -> fail(-1, "Không bắt đầu được khám phá dịch vụ", ErrorKind.SERVICE_DISCOVERY_FAILED)
            "cccd" -> { pendingCccd.remove(name.substringAfter(' ')); setSub(name.substringAfter(' '), SubscriptionState.FAILED) }
        }
    }

    private fun setSub(uuid: String, s: SubscriptionState?) {
        if (s == SubscriptionState.ACTIVE) ev(vn.nvxconnect.telemetry.EventKinds.SUBSCRIBE, vn.nvxconnect.telemetry.EventOutcome.SUCCESS, 0, "Thiết bị xác nhận ghi CCCD", mapOf("char" to uuid))
        else if (s == SubscriptionState.FAILED) ev(vn.nvxconnect.telemetry.EventKinds.SUBSCRIBE, vn.nvxconnect.telemetry.EventOutcome.FAILURE, null, "Ghi CCCD thất bại", mapOf("char" to uuid))
        else if (s == SubscriptionState.PENDING) ev(vn.nvxconnect.telemetry.EventKinds.SUBSCRIBE, vn.nvxconnect.telemetry.EventOutcome.NOT_TESTED, null, "Đang chờ thiết bị xác nhận", mapOf("char" to uuid))
        val m = _subs.value.toMutableMap()
        if (s == null) m.remove(uuid) else m[uuid] = s
        _subs.value = m
    }

    private fun findChar(uuid: String): GattCharInfo? {
        val u = norm(uuid)
        return (_state.value as? ConnState.Ready)?.services?.flatMap { it.characteristics }?.firstOrNull { norm(it.uuid) == u }
    }

    private fun enqueueCccd(u: String, enable: Boolean) = op("cccd $u") {
        pendingCccd[u] = enable
        val ok = port.setNotification(u, enable)
        if (!ok) pendingCccd.remove(u)
        ok
    }

    private fun startRssiPoll() {
        rssiJob?.cancel()
        rssiJob = scope.launch {
            while (isActive) {
                delay(rssiPollMs)
                synchronized(lock) {
                    if (_state.value is ConnState.Ready && !queue.contains("rssi")) op("rssi") { port.readRssi() }
                }
            }
        }
    }

    // ---------------------------------------------------------------- sự kiện từ GattPort
    override fun onLinkState(connected: Boolean, status: Int) = synchronized(lock) {
        if (userStopped) return@synchronized
        if (connected && status == 0) {
            val s = _state.value
            if (s is ConnState.Connecting || s is ConnState.Reconnecting) {
                setState(ConnState.Connected, "Đã thiết lập liên kết GATT")
                op("mtu") { port.requestMtu(preferredMtu) }
                op("discover") {
                    val ok = port.discoverServices()
                    if (ok) setState(ConnState.DiscoveringServices, "Đang khám phá dịch vụ")
                    ok
                }
            }
        } else if (!connected) {
            val kind = when {
                status == GattStatus.AUTH_REQUIRED || status == GattStatus.ENCRYPTION_REQUIRED -> ErrorKind.AUTH_REQUIRED
                GattStatus.looksLikeOutOfRange(status) -> ErrorKind.TIMEOUT
                else -> ErrorKind.GATT_ERROR
            }
            val msg = "Mất kết nối: ${GattStatus.describe(status)}"
            emitLog(LogKind.GATT, msg)
            // Bằng chứng log 2026-10-25: xe tự ngắt (19) ~29,3 s sau READY khi khóa điện tắt (0 gói); khi bật khóa giữ liên kết và phát gói.
            if (status == GattStatus.REMOTE_TERMINATED || (everReady && kind == ErrorKind.TIMEOUT)) _hint.value = LinkHint.WAITING_IGNITION
            else if (!everReady && kind == ErrorKind.TIMEOUT) _hint.value = LinkHint.NOT_IN_RANGE
            fail(status, msg, kind)
            sessionFrames.set(0)
        } else {
            fail(status, "Kết nối lỗi: ${GattStatus.describe(status)}", ErrorKind.GATT_ERROR)
        }
    }

    override fun onMtu(mtu: Int, status: Int) = synchronized(lock) {
        if (!_state.value.isActive) { lateDropped(); return@synchronized }
        if (status == 0) { _mtu.value = mtu; emitLog(LogKind.GATT, "MTU = $mtu"); ev(vn.nvxconnect.telemetry.EventKinds.MTU, vn.nvxconnect.telemetry.EventOutcome.SUCCESS, status, "MTU", mapOf("mtu" to mtu.toString())) }
        else { emitLog(LogKind.GATT, "Đổi MTU thất bại: ${GattStatus.describe(status)}"); ev(vn.nvxconnect.telemetry.EventKinds.MTU, vn.nvxconnect.telemetry.EventOutcome.FAILURE, status, GattStatus.describe(status)) }
        done("mtu")
    }

    override fun onServices(services: List<GattServiceInfo>, status: Int) = synchronized(lock) {
        if (userStopped) return@synchronized
        // Chỉ nhận kết quả khám phá khi ĐANG chờ nó: callback muộn của phiên cũ không được đẩy trạng thái sang Ready.
        val st = _state.value
        if (st !is ConnState.Connected && st !is ConnState.DiscoveringServices) { lateDropped(); return@synchronized }
        done("discover")
        if (status != 0) { fail(status, "Khám phá dịch vụ lỗi: ${GattStatus.describe(status)}", ErrorKind.SERVICE_DISCOVERY_FAILED); return@synchronized }
        timeoutJob?.cancel(); attempt = 0
        sessionFrames.set(0); everReady = true
        this.services = services
        indicateOnly = services.flatMap { it.characteristics }.filter { it.canIndicate && !it.canNotify }.map { norm(it.uuid) }.toSet()
        charService = HashMap<String, String>().also { m -> services.forEach { sv -> sv.characteristics.forEach { c -> m[norm(c.uuid)] = sv.uuid } } }
        readyAtMono = mono(); readyAtWall = clock(); _readySince.value = readyAtMono
        _history.value = (_history.value + LinkEvent(readyAtWall, epoch, LinkEventKind.READY, null, "Sẵn sàng", null, attempt, 0)).takeLast(MAX_HISTORY)
        ev(vn.nvxconnect.telemetry.EventKinds.DISCOVERY, vn.nvxconnect.telemetry.EventOutcome.SUCCESS, 0, "Khám phá dịch vụ xong",
            mapOf("services" to services.size.toString(), "characteristics" to services.sumOf { it.characteristics.size }.toString(), "mtu" to _mtu.value.toString()))
        setState(ConnState.Ready(services), "Sẵn sàng: ${services.size} service, ${services.sumOf { it.characteristics.size }} characteristic")
        if (services.isEmpty()) emitLog(LogKind.INFO, "Thiết bị không công bố service nào")
        startRssiPoll()
        // Tự bật nghe kênh notify của xe: không bắt người dùng bấm "Nghe" (log thật: bấm tay làm mất 11–44 s dữ liệu đầu).
        val auto = runCatching { autoListen(services) }.getOrDefault(emptyList())
        val all = services.flatMap { it.characteristics }
        auto.map { norm(it) }.filter { it !in userOff && it !in desired }.forEach { u ->
            if (all.firstOrNull { norm(it.uuid) == u }?.canSubscribe == true) { desired += u; setSub(u, SubscriptionState.PENDING) }
        }
        desired.toList().forEach { enqueueCccd(it, true) }
    }

    override fun onDescriptorWrite(charUuid: String, status: Int) = synchronized(lock) {
        val u = norm(charUuid)
        // Chỉ nhận xác nhận cho thao tác ĐANG chờ: callback muộn (phiên cũ, hoặc sau khi đã hết giờ) không được đổi trạng thái đăng ký
        // và không được nhả nhầm hàng đợi của thao tác khác.
        if (!_state.value.isActive || u !in pendingCccd) { lateDropped(); return@synchronized }
        val enable = pendingCccd.remove(u)
        done("cccd")
        if (status == 0) {
            if (enable == true) { setSub(u, SubscriptionState.ACTIVE); emitLog(LogKind.GATT, "Thiết bị xác nhận đăng ký nghe", u) }
            else { setSub(u, null); emitLog(LogKind.GATT, "Đã ngừng nghe", u) }
        } else {
            setSub(u, SubscriptionState.FAILED)
            desired -= u
            emitLog(LogKind.ERROR, "Đăng ký nghe thất bại: ${GattStatus.describe(status)}", u)
            if (GattStatus.needsPairing(status)) emitLog(LogKind.ERROR, "Thiết bị yêu cầu ghép nối/mã hóa cho kênh này. App dừng tại đây, không vượt qua.", u)
        }
    }

    override fun onRssi(rssi: Int, status: Int) = synchronized(lock) {
        done("rssi"); if (status == 0) _rssi.value = rssi
    }

    override fun onRead(charUuid: String, value: ByteArray, status: Int) = synchronized(lock) {
        done("read")
        val u = norm(charUuid)
        if (status == 0 && StandardInfo.isAllowed(u)) {
            val label = StandardInfo.label(u) ?: u
            _info.value = _info.value + (label to StandardInfo.decode(u, value))
            emitLog(LogKind.GATT, "Đọc $label", u, HexUtil.toHex(value))
        } else emitLog(LogKind.ERROR, "Đọc thất bại: ${GattStatus.describe(status)}", u)
    }

    /**
     * Chạy trên luồng callback Bluetooth, KHÔNG giữ khóa chính (để connect/disconnect/timer không làm nghẽn việc nhận gói).
     * Thứ tự: loại gói ngoài phiên → chép dữ liệu → đưa cho [sink] (hàng đợi bền vững, không chặn) → phát cho giao diện (có đếm số gói giao diện bỏ lỡ).
     */
    override fun onNotification(charUuid: String, value: ByteArray) {
        val st = _state.value
        if (st !is ConnState.Ready) { lateDropped(); return }   // gói muộn của phiên đã đóng/đang nối lại: không xử lý
        if (sessionFrames.getAndIncrement() == 0L) {
            synchronized(lock) { _hint.value = LinkHint.STREAMING }
            ev(vn.nvxconnect.telemetry.EventKinds.DATA_FIRST, vn.nvxconnect.telemetry.EventOutcome.SUCCESS, null, "Payload đầu tiên của phiên GATT", mapOf("char" to norm(charUuid), "length" to value.size.toString()))
        }
        val wall = clock(); val m = mono(); val copy = value.copyOf(); val u = norm(charUuid)
        sink?.onFrame(epoch, u, charService[u], copy, wall, m, _rssi.value, st.code, u in indicateOnly)
        if (!_frames.tryEmit(RawFrame(wall, charUuid, copy))) { uiDropped.incrementAndGet(); publishDiag() }
    }

    private fun lateDropped() { lateEvents.incrementAndGet(); publishDiag() }
    private fun publishDiag() { _diag.value = LinkDiagnostics(lateEvents.get(), uiDropped.get(), epoch) }

    override fun onBluetoothState(enabled: Boolean) = synchronized(lock) {
        if (!enabled) {
            if (_state.value.isActive) {
                emitLog(LogKind.ERROR, "Bluetooth đã bị tắt")
                pushHistory(LinkEventKind.BLUETOOTH_OFF, null, "Bluetooth bị tắt")
                cancelTimers(); teardown()
                setError(ErrorKind.BLUETOOTH_OFF, "Bluetooth đã bị tắt khi đang kết nối. Bật lại rồi bấm kết nối.")
            }
        } else {
            emitLog(LogKind.INFO, "Bluetooth đã bật")
            // Người dùng vẫn muốn kết nối (chưa bấm ngắt) và lỗi trước đó chỉ là Bluetooth tắt → tự nối lại MỘT lần, không lặp vô hạn.
            val s = _state.value
            if (!userStopped && target != null && s is ConnState.Error && s.kind == ErrorKind.BLUETOOTH_OFF) {
                _history.value = (_history.value + LinkEvent(clock(), epoch, LinkEventKind.BLUETOOTH_RESUMED, null, "Bluetooth bật lại: tự nối lại", null, attempt, 0)).takeLast(MAX_HISTORY)
                attempt = 0; open()
            }
        }
    }

    private companion object {
        /** Sau chừng này lần thử trực tiếp không thấy xe thì chuyển sang kết nối nền (chỉ chế độ xe). */
        const val BACKGROUND_AFTER = 3
        /** Nghỉ ngắn trước khi nối lại sau khi xe tự ngắt, đủ để Android giải phóng phiên cũ. */
        const val REMOTE_DROP_RETRY_MS = 500L
        const val MAX_HISTORY = 100
    }
}
