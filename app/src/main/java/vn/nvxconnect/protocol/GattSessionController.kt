package vn.nvxconnect.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import vn.nvxconnect.bluetooth.*

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
    private val clock: () -> Long = System::currentTimeMillis
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

    private var address: String? = null
    private var attempt = 0
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
            if (address.equals(this.address, ignoreCase = true)) {
                emitLog(LogKind.INFO, "Đã có phiên GATT tới thiết bị này, bỏ qua yêu cầu kết nối")
                return@synchronized
            }
            emitLog(LogKind.INFO, "Chuyển sang thiết bị khác: đóng phiên GATT cũ trước")
            cancelTimers(); teardown()
        }
        cancelTimers()
        userStopped = false; attempt = 0; this.address = address
        desired.clear(); _subs.value = emptyMap(); _info.value = emptyMap()
        open()
    }

    override fun disconnect() = synchronized(lock) {
        userStopped = true
        cancelTimers(); teardown()
        desired.clear(); _subs.value = emptyMap()
        setState(ConnState.Disconnected, "Người dùng ngắt kết nối")
    }

    override fun subscribe(charUuid: String): Boolean = synchronized(lock) {
        if (_state.value !is ConnState.Ready) return@synchronized false
        val ch = findChar(charUuid) ?: return@synchronized false
        if (!ch.canSubscribe) return@synchronized false
        val u = norm(ch.uuid)
        desired += u; setSub(u, SubscriptionState.PENDING)
        enqueueCccd(u, true)
        true
    }

    override fun unsubscribe(charUuid: String): Boolean = synchronized(lock) {
        if (_state.value !is ConnState.Ready) return@synchronized false
        val ch = findChar(charUuid) ?: return@synchronized false
        val u = norm(ch.uuid)
        desired -= u
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
        val addr = address ?: return
        teardown()
        setState(if (attempt == 0) ConnState.Connecting else ConnState.Reconnecting(attempt, policy.maxAttempts),
            if (attempt == 0) "Bắt đầu kết nối" else "Kết nối lại lần $attempt/${policy.maxAttempts}")
        when (val r = port.open(addr)) {
            is PortOpen.Failed -> { failNow(r.kind, r.reason); return }
            PortOpen.Started -> {}
        }
        timeoutJob = scope.launch { delay(connectTimeoutMs); onConnectTimeout() }
    }

    private fun onConnectTimeout() = synchronized(lock) {
        if (userStopped || _state.value is ConnState.Ready) return@synchronized
        fail(-1, "Hết thời gian chờ kết nối (${connectTimeoutMs / 1000}s)", ErrorKind.TIMEOUT)
    }

    /** Lỗi không thể khắc phục bằng thử lại (Bluetooth tắt, thiếu quyền, địa chỉ sai). */
    private fun failNow(kind: ErrorKind, reason: String) {
        cancelTimers(); teardown()
        if (userStopped) return
        setError(kind, reason)
    }

    private fun fail(status: Int, reason: String, kind: ErrorKind) {
        cancelTimers(); teardown()
        if (userStopped) return
        if (!GattStatus.retriable(status)) {
            setError(ErrorKind.AUTH_REQUIRED, "$reason — ${GattStatus.describe(status)}. App không ghép nối/xác thực thay Y-Connect.")
            return
        }
        val d = policy.nextDelayMs(attempt)
        if (d == null) {
            val finalKind = if (kind == ErrorKind.TIMEOUT || GattStatus.looksLikeOutOfRange(status)) ErrorKind.OUT_OF_RANGE else kind
            setError(finalKind, "$reason (đã thử $attempt lần). Thiết bị có thể ngoài phạm vi, đang tắt, hoặc đang được ứng dụng khác (Y-Connect) giữ kết nối.")
            return
        }
        attempt++
        setState(ConnState.Reconnecting(attempt, policy.maxAttempts), "$reason; thử lại sau $d ms (lần $attempt/${policy.maxAttempts})")
        retryJob = scope.launch {
            delay(d)
            synchronized(lock) { if (!userStopped && _state.value is ConnState.Reconnecting) open() }
        }
    }

    private fun setError(kind: ErrorKind, reason: String) {
        _subs.value = emptyMap()
        setState(ConnState.Error(kind, reason), "${kind.title}: $reason")
    }

    private fun cancelTimers() { timeoutJob?.cancel(); retryJob?.cancel(); timeoutJob = null; retryJob = null }

    private fun teardown() {
        opWatchdog?.cancel(); rssiJob?.cancel(); opWatchdog = null; rssiJob = null
        queue.clear(); pendingCccd.clear()
        _rssi.value = null; _mtu.value = 23; services = emptyList()
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
            fail(status, msg, kind)
        } else {
            fail(status, "Kết nối lỗi: ${GattStatus.describe(status)}", ErrorKind.GATT_ERROR)
        }
    }

    override fun onMtu(mtu: Int, status: Int) = synchronized(lock) {
        if (status == 0) { _mtu.value = mtu; emitLog(LogKind.GATT, "MTU = $mtu") }
        else emitLog(LogKind.GATT, "Đổi MTU thất bại: ${GattStatus.describe(status)}")
        done("mtu")
    }

    override fun onServices(services: List<GattServiceInfo>, status: Int) = synchronized(lock) {
        if (userStopped) return@synchronized
        done("discover")
        if (status != 0) { fail(status, "Khám phá dịch vụ lỗi: ${GattStatus.describe(status)}", ErrorKind.SERVICE_DISCOVERY_FAILED); return@synchronized }
        timeoutJob?.cancel(); attempt = 0
        this.services = services
        setState(ConnState.Ready(services), "Sẵn sàng: ${services.size} service, ${services.sumOf { it.characteristics.size }} characteristic")
        if (services.isEmpty()) emitLog(LogKind.INFO, "Thiết bị không công bố service nào")
        startRssiPoll()
        desired.toList().forEach { enqueueCccd(it, true) }
    }

    override fun onDescriptorWrite(charUuid: String, status: Int) = synchronized(lock) {
        val u = norm(charUuid)
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

    override fun onNotification(charUuid: String, value: ByteArray) {
        _frames.tryEmit(RawFrame(clock(), charUuid, value.copyOf()))
    }

    override fun onBluetoothState(enabled: Boolean) = synchronized(lock) {
        if (!enabled) {
            if (_state.value.isActive) {
                emitLog(LogKind.ERROR, "Bluetooth đã bị tắt")
                cancelTimers(); teardown()
                setError(ErrorKind.BLUETOOTH_OFF, "Bluetooth đã bị tắt khi đang kết nối. Bật lại rồi bấm kết nối.")
            }
        } else emitLog(LogKind.INFO, "Bluetooth đã bật")
    }
}
