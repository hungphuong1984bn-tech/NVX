package vn.nvxconnect.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import vn.nvxconnect.AppInfo
import vn.nvxconnect.NvxApp
import vn.nvxconnect.auto.*
import vn.nvxconnect.bluetooth.*
import vn.nvxconnect.capability.*
import vn.nvxconnect.protocol.*
import vn.nvxconnect.telemetry.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Kết nối BLE ở chế độ khảo sát thụ động. Không có đường ghi dữ liệu vào xe. */
class BleViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = getApplication<NvxApp>().settingsRepo
    private val scanner = BleScanner(app)
    private val conn = BleVehicleConnection.create(app, viewModelScope)
    private val registry = getApplication<NvxApp>().adapters // dùng chung toàn app; rỗng cho tới khi có bằng chứng giao thức
    private val telemetry = getApplication<NvxApp>().telemetry
    private val vehicleData = getApplication<NvxApp>().vehicleData
    private val yccMonitor = YccLinkMonitor()
    val yccCandidates = getApplication<NvxApp>().yccCandidates.state
    val yccStats = MutableStateFlow(YccLinkMonitor.Stats())
    private val calibrator = YccCalibrationController(vn.nvxconnect.data.PrefsCalibrationStore(app))
    val calStatus = MutableStateFlow(calibrator.status())
    val calMessage = MutableStateFlow<String?>(null)
    /** Ghi điểm tham chiếu/kiểm chứng cho kênh ứng viên [id]. Chỉ đọc dữ liệu đã nhận, không ghi gì vào xe. */
    fun recordCalibration(id: String, ref: Double, validate: Boolean) {
        calMessage.value = when (val r = calibrator.record(id, ref, validate, System.currentTimeMillis())) { is YccCalibrationController.Result.Ok -> r.message; is YccCalibrationController.Result.Refused -> "Không ghi: " + r.message }
        calStatus.value = calibrator.status()
    }
    fun undoCalibration(id: String) { calibrator.undo(id); calStatus.value = calibrator.status() }
    fun resetCalibration(id: String) { calibrator.reset(id); calStatus.value = calibrator.status() }
    private val salt = UUID.randomUUID().toString()
    private val table = DeviceTable()
    // ---- thu thập/ xác minh telemetry (telemetry/): ghi payload thật, KHÔNG có đường ghi xuống xe ----
    private val recorder = RawFrameRecorder(anonymize = { SurveyRedactor.address(it, salt) }, anonymizeName = { SurveyRedactor.name(it) }).also { it.start("tự động") }
    private val evidence = ProtocolEvidenceRepository()
    val recording = MutableStateFlow(true)
    val recStats = MutableStateFlow(RecSummary())
    val fieldVerdicts = MutableStateFlow<List<FieldVerdict>>(emptyList())
    val toolMessage = MutableStateFlow<String?>(null)
    val importedAnalysis = MutableStateFlow<String?>(null)
    data class RecSummary(val payloads: Long = 0, val lastMs: Long? = null, val lastHex: String? = null, val lastChar: String? = null, val dropped: Long = 0, val ignored: Long = 0, val session: String = "")
    private val logBuf = SessionLogBuffer()
    private val rawFrames = ArrayList<RawFrame>()
    private val surveyServices = mutableMapOf<String, List<GattServiceInfo>>()
    private val actionTags = ArrayList<CorrelationEngine.Tag>()
    private val gpsSpeeds = ArrayList<SpeedSample>()
    private var currentAddr: String? = null
    private var scanJob: Job? = null
    private var autoTried = false

    // ---- tự tìm và kết nối (auto/): dùng CHUNG một phiên GATT `conn`, không mở phiên thứ hai ----
    private val bond = AndroidBondObserver(app).also { it.start() }
    private val autoAllowed = repo.settings.map { it.autoConnect }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val autoController = AutoConnectController(viewModelScope, conn, { t -> scanner.scan(timeoutMs = t) }, log = { k, m -> note(k, m) })
    private val sessionList = ArrayList<ScanSession>()
    private var sessionSeq = 0
    private val segments = LinkedHashMap<String, MutableList<RawFrame>>()
    private var currentSegment: String? = null
    private val valueSeen = MutableStateFlow(false)
    private val lastBondEvent = MutableStateFlow<BondEvent?>(null)
    private val authSeen = MutableStateFlow(false)
    /** Đã nhận ít nhất một giá trị xe ĐÃ XÁC MINH trong phiên (dùng cho quy trình kiểm tra và màn hình tích hợp TFT). */
    val verifiedValueSeen: StateFlow<Boolean> get() = valueSeen

    /** Danh mục chức năng xe/app + đối chiếu, dựng từ bằng chứng hiện có. null tới lần [refreshCapabilities] đầu tiên. */
    val capabilityReport = MutableStateFlow<CapabilityReport?>(null)

    val connState = conn.state
    val rssi = conn.rssi
    val mtu = conn.mtu
    val subscriptions = conn.subscriptions
    val deviceInfo = conn.deviceInfo
    val linkStatus: StateFlow<VehicleLinkStatus> = conn.state.map { VehicleLinkStatus.resolve(it, registry) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, VehicleLinkStatus.resolve(ConnState.Disconnected, registry))

    private val rows = MutableStateFlow<List<DeviceTable.Row>>(emptyList())
    val query = MutableStateFlow(DeviceQuery())
    val saved: StateFlow<List<SavedDevice>> = repo.savedDevices.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val visible: StateFlow<List<DeviceTable.Row>> = combine(rows, query, saved) { r, q, s -> DeviceFilter.apply(r, q, s) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val totalSeen: StateFlow<Int> = rows.map { it.size }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val scanning = MutableStateFlow(false)
    /** Lỗi gần nhất của lần quét/kết nối kèm loại, để giao diện hiện đúng nút xử lý. */
    val problem = MutableStateFlow<Pair<ErrorKind, String>?>(null)
    val events = MutableStateFlow<List<String>>(emptyList())
    val frameLines = MutableStateFlow<List<String>>(emptyList())
    val frameCount = MutableStateFlow(0)
    val tagCounts = MutableStateFlow<Map<String, Int>>(emptyMap())

    // ---- trạng thái cho màn hình "Tự tìm và kết nối xe" ----
    val scanSessions = MutableStateFlow<List<ScanSession>>(emptyList())
    val candidates: StateFlow<List<Candidate>> = combine(scanSessions, saved) { s, sv -> CandidateRanker.rank(s, sv) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val autoRun = autoController.run
    val surveyLabel = MutableStateFlow(SessionLabel.VEHICLE_OFF)
    val segmentNames = MutableStateFlow<List<String>>(emptyList())
    val activeSegment = MutableStateFlow<String?>(null)
    val findings = MutableStateFlow<List<Finding>>(emptyList())
    val inspector: StateFlow<InspectorSummary?> = conn.state.map { (it as? ConnState.Ready)?.let { r -> GattInspector.summarize(r.services) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val bondStates = bond.states
    val auth: StateFlow<AuthAssessment> = combine(conn.state, bond.states, lastBondEvent) { st, b, ev ->
        val addr = currentAddr?.uppercase()
        AuthAssessor.assess(st, addr?.let { b[it] } ?: BondState.NONE, ev?.takeIf { it.address.uppercase() == addr })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AuthAssessor.assess(ConnState.Disconnected, BondState.NONE, null))
    private val runInputs = combine(scanning, autoController.run, conn.state) { sc, run, st -> Triple(sc, run, st) }
    val autoStatus: StateFlow<AutoStatus> = combine(runInputs, linkStatus, bond.states, valueSeen) { (sc, run, st), link, b, valueSeenNow ->
        val addr = currentAddr?.uppercase()
        AutoStatusResolver.resolve(AutoInputs(
            scanning = sc, candidateFound = run.phase == RunPhase.CANDIDATE_FOUND || run.candidate != null && run.phase == RunPhase.CONNECTING,
            conn = st, bond = addr?.let { b[it] } ?: BondState.NONE, link = link, verifiedValueSeen = valueSeenNow,
            runFailure = run.failure, runMessage = run.message))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AutoStatus(AutoState.IDLE, "CHƯA BẮT ĐẦU"))
    val autoConnectAllowed: StateFlow<Boolean> = autoAllowed

    private fun time(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))
    private fun note(kind: LogKind, msg: String) = record(LogEntry(System.currentTimeMillis(), kind, msg, state = conn.state.value.code))
    private fun record(e: LogEntry) {
        logBuf.add(e)
        if (e.kind != LogKind.FRAME) events.value = (events.value + "${time(e.timeMs)} ${LogExporter.redactText(e.message, salt)}").takeLast(80)
    }

    init {
        // GPS điện thoại làm sự thật nền để tự đối chiếu tốc độ khi xuất báo cáo (chỉ đọc, không ghi gì vào xe).
        viewModelScope.launch {
            getApplication<NvxApp>().tripEngine.lastFix.collect { p ->
                if (p != null && p.accuracyM <= 30f && p.speedMps.isFinite() && gpsSpeeds.size < 5000) gpsSpeeds += SpeedSample(p.timeMs, p.speedMps.toDouble())
            }
        }
        viewModelScope.launch { linkStatus.collect { telemetry.linkVerified = it.protocolVerified; if (!it.protocolVerified) telemetry.reset() } }
        viewModelScope.launch { conn.log.collect { record(it); recordReadFromLog(it) } }
        viewModelScope.launch { conn.state.collect { st -> recordStateEvent(st) } }
        viewModelScope.launch { conn.mtu.collect { m -> currentAddr?.let { recorder.event(it, ConnEventType.MTU, null, "mtu=$m") } } }
        viewModelScope.launch { var last = emptyMap<String, SubscriptionState>(); conn.subscriptions.collect { m -> recordSubEvents(last, m); last = m } }
        viewModelScope.launch {
            conn.frames.collect { f ->
                if (rawFrames.size < 5000) rawFrames += f
                recordPayload(f)
                val hex = HexUtil.toHex(f.bytes)
                record(LogEntry(f.timeMs, LogKind.FRAME, "notify ${f.bytes.size} byte", f.charUuid, conn.state.value.code, hex))
                val services = (conn.state.value as? ConnState.Ready)?.services ?: emptyList()
                val decoded = registry.decode(services, f)
                val label = if (decoded.all { it is DecodedItem.Unknown }) "RAW/UNKNOWN" else "DECODED"
                // Chỉ khi kho dữ liệu NHẬN ít nhất một giá trị (liên kết đã xác minh, nguồn LIVE_VERIFIED, trong khoảng hợp lệ) mới coi là đã có dữ liệu.
                if (vehicleData.ingest(decoded, linkStatus.value.protocolVerified) > 0) valueSeen.value = true
                if (f.charUuid.equals(YccFrames.CHAR_NOTIFY, ignoreCase = true) && services.any { it.uuid.equals(YccFrames.SERVICE_NUS, ignoreCase = true) }) {
                    // Chỉ ĐỌC/giải mã khung, không ghi gì vào xe. Giá trị ứng viên đi kênh riêng, không vào TelemetryHub.
                    yccMonitor.accept(f.bytes, f.timeMs)?.let { fr -> getApplication<NvxApp>().yccCandidates.ingest(calibrator.process(YccCandidates.decode(fr, f.timeMs))) }
                    yccStats.value = yccMonitor.stats
                }
                currentSegment?.let { seg -> segments.getOrPut(seg) { ArrayList() }.let { if (it.size < 2000) it += f } }
                frameCount.value = rawFrames.size
                if (rawFrames.size % 50 == 0) refreshReport()
                frameLines.value = (frameLines.value + "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(f.timeMs))} ${f.charUuid.take(8)} ${FrameRedactor.hexRedacted(f.bytes)}  [$label]").takeLast(60)
            }
        }
        viewModelScope.launch {
            conn.state.collect { st ->
                if (st !is ConnState.Ready) { yccMonitor.onDisconnect(); calibrator.onDisconnect() }
                if (st is ConnState.Ready) currentAddr?.let { surveyServices[it] = st.services }
                problem.value = (st as? ConnState.Error)?.let { it.kind to it.reason }
                if (st is ConnState.Error && st.kind == ErrorKind.AUTH_REQUIRED) authSeen.value = true
                if (st is ConnState.Ready) { currentAddr?.let { bond.current(it) }; refreshReport() }
            }
        }
        // Vòng tự kết nối tự gọi conn.connect: ghi nhớ địa chỉ đang dùng để ghép đôi/xác thực/xuất log nhắm đúng thiết bị.
        viewModelScope.launch { autoController.run.collect { r -> r.candidate?.let { currentAddr = it.address } } }
        viewModelScope.launch {
            bond.events.collect { e ->
                lastBondEvent.value = e
                note(LogKind.GATT, "Ghép đôi ${SurveyRedactor.address(e.address, salt)}: ${e.previous} → ${e.now}" + (e.reason?.let { " (mã $it)" } ?: ""))
            }
        }
    }

    // ---------------------------------------------------------------- thu thập payload có cấu trúc
    private fun servicesNow(): List<GattServiceInfo> = (conn.state.value as? ConnState.Ready)?.services ?: emptyList()
    private fun serviceOf(char: String) = servicesNow().firstOrNull { s -> s.characteristics.any { it.uuid.equals(char, true) } }?.uuid
    private fun recordPayload(f: RawFrame) {
        val addr = currentAddr ?: return
        val ch = servicesNow().flatMap { it.characteristics }.firstOrNull { it.uuid.equals(f.charUuid, true) }
        val src = if (ch != null && ch.canIndicate && !ch.canNotify) PayloadSource.INDICATION else PayloadSource.NOTIFICATION
        recorder.record(addr, src, serviceOf(f.charUuid), f.charUuid, f.bytes, f.timeMs, rssi.value)?.let { refreshRecStats(it) }
    }
    private fun recordReadFromLog(e: LogEntry) {
        val addr = currentAddr ?: return
        if (e.kind == LogKind.GATT && e.hex != null && e.uuid != null && e.message.startsWith("Đọc")) {
            val b = HexUtil.fromHex(e.hex) ?: return
            recorder.record(addr, PayloadSource.READ, serviceOf(e.uuid), e.uuid, b, e.timeMs)?.let { refreshRecStats(it) }
        }
        if (e.kind == LogKind.ERROR && e.message.contains("quá")) recorder.event(addr, ConnEventType.TIMEOUT, e.uuid, e.message, e.timeMs)
        else if (e.kind == LogKind.ERROR && e.message.startsWith("Đọc thất bại")) recorder.event(addr, ConnEventType.READ_FAILED, e.uuid, e.message, e.timeMs)
    }
    private fun recordStateEvent(st: ConnState) {
        val addr = currentAddr ?: return
        val t = when (st) {
            is ConnState.Connecting -> ConnEventType.CONNECT_START
            is ConnState.Connected -> ConnEventType.CONNECTED
            is ConnState.Ready -> ConnEventType.DISCOVERY_DONE
            is ConnState.Reconnecting -> ConnEventType.RECONNECTING
            is ConnState.Disconnected -> ConnEventType.DISCONNECTED
            is ConnState.Error -> ConnEventType.ERROR
            else -> return
        }
        recorder.event(addr, t, null, (st as? ConnState.Error)?.reason ?: st.code)
    }
    private fun recordSubEvents(old: Map<String, SubscriptionState>, new: Map<String, SubscriptionState>) {
        val addr = currentAddr ?: return
        for ((u, st) in new) if (old[u] != st) recorder.event(addr, when (st) { SubscriptionState.PENDING -> ConnEventType.SUBSCRIBE_PENDING; SubscriptionState.ACTIVE -> ConnEventType.SUBSCRIBE_OK; SubscriptionState.FAILED -> ConnEventType.SUBSCRIBE_FAILED }, u)
        for (u in old.keys - new.keys) recorder.event(addr, ConnEventType.UNSUBSCRIBED, u)
    }
    private fun refreshRecStats(r: PayloadRecord?) {
        val last = r ?: recorder.snapshot().lastOrNull { it.source != PayloadSource.ADVERTISEMENT }
        recStats.value = RecSummary(recorder.totalPayloads(), recorder.lastPayloadMs, last?.hex?.let { FrameRedactor.hexRedacted(HexUtil.fromHex(it) ?: ByteArray(0)) }, last?.charUuid,
            recorder.droppedOldest, recorder.ignoredWhileStopped, recorder.sessionId)
        if (recorder.totalPayloads() % 50L == 0L) evaluateFields()
    }
    fun startRecording(label: String) { recorder.start(label.ifBlank { "thủ công" }); recording.value = true; refreshRecStats(null) }
    fun stopRecording() { recorder.stop(); recording.value = false; refreshRecStats(null) }
    fun evaluateFields() { fieldVerdicts.value = evidence.evaluateAll(recorder.snapshot(), System.currentTimeMillis()) }
    private fun anonGatt(): Map<String, List<GattServiceInfo>> = surveyServices.mapKeys { SurveyRedactor.address(it.key, salt) }
    fun telemetryJson() = TelemetryLogExporter.toJson(recorder, anonGatt(), AppInfo.VERSION, System.currentTimeMillis(), redact = true, salt = salt)
    fun telemetryCsv() = TelemetryLogExporter.toCsv(recorder, redact = true)
    fun evidenceMarkdown(): String { evaluateFields(); return TelemetryEvidenceReport.toMarkdown(fieldVerdicts.value) }
    fun evidenceCsv(): String { evaluateFields(); return TelemetryEvidenceReport.toCsv(fieldVerdicts.value) }
    /** Nạp log đã xuất (JSON/CSV) để phân tích ngoại tuyến. Chỉ đọc văn bản, không chạm vào kết nối. */
    fun importLog(text: String) {
        val log = TelemetryLogImport.parse(text)
        importedAnalysis.value = OfflineLogAnalyzer.toMarkdown(OfflineLogAnalyzer.analyze(listOf(log), evidence.verifiedCharUuids()))
        toolMessage.value = "Đã nạp ${log.records.size} payload" + if (log.warnings.isNotEmpty()) " (${log.warnings.size} cảnh báo)" else ""
    }
    fun importReference(text: String) {
        val d = evidence.importReference(text, System.currentTimeMillis()); evaluateFields()
        toolMessage.value = "Tham chiếu: nhận ${d.samples.size} mẫu, bỏ ${d.warnings.size} dòng" + (d.warnings.firstOrNull()?.let { " — $it" } ?: "")
    }

    // ---------------------------------------------------------------- quét
    /**
     * Quét quảng bá. label != null: đây là một PHIÊN KHẢO SÁT (xe tắt/bật), kết quả được lưu để xếp hạng ứng viên.
     * Chỉ một lượt quét tại một thời điểm, và không quét khi đang có phiên GATT hoặc vòng tự kết nối đang chạy.
     */
    fun startScan(label: SessionLabel? = null, timeoutMs: Long = 60_000) {
        if (scanJob != null) return
        if (autoController.isRunning || conn.state.value.isActive) { note(LogKind.INFO, "Bỏ qua yêu cầu quét: đang kết nối hoặc đang tự kết nối"); return }
        problem.value = null; scanning.value = true
        val startedMs = System.currentTimeMillis(); val seen = ArrayList<AdvertRecord>()
        note(LogKind.SCAN, if (label == null) "Bắt đầu quét" else "Bắt đầu phiên khảo sát: ${label.title}")
        scanJob = viewModelScope.launch {
            scanner.scan(timeoutMs = timeoutMs).catch { e ->
                val k = (e as? BleException)?.kind ?: ErrorKind.SCAN_FAILED
                problem.value = k to (e.message ?: k.title); note(LogKind.ERROR, "Lỗi quét: ${e.message}")
            }.onCompletion {
                scanning.value = false; scanJob = null
                if (label != null && seen.isNotEmpty()) {
                    sessionList += ScanSession(++sessionSeq, label, startedMs, seen.toList()); scanSessions.value = sessionList.toList()
                }
                note(LogKind.SCAN, "Đã dừng quét (${table.size()} thiết bị)")
            }.collect { recorder.recordAdvert(it); table.update(it); rows.value = table.rows(System.currentTimeMillis()); if (label != null && seen.size < 20_000) seen += it }
        }
    }
    fun stopScan() { scanJob?.cancel() }
    fun clearDevices() { table.clear(); rows.value = emptyList() }
    fun setQuery(q: DeviceQuery) { query.value = q }
    fun reportPermissionDenied() { problem.value = ErrorKind.PERMISSION_DENIED to BleBlocker.PERMISSION_MISSING.message; note(LogKind.ERROR, "Người dùng từ chối quyền Bluetooth/vị trí") }

    // ---------------------------------------------------------------- kết nối
    fun connect(addr: String) {
        stopScan(); autoController.cancel(); currentAddr = addr; problem.value = null; authSeen.value = false
        note(LogKind.INFO, "Kết nối ${SurveyRedactor.address(addr, salt)}")
        conn.connect(addr)
    }
    fun retry() { currentAddr?.let { connect(it) } }
    fun disconnect() { conn.disconnect() }
    fun subscribe(uuid: String) { if (!conn.subscribe(uuid)) note(LogKind.ERROR, "Không đăng ký nghe được $uuid") }
    fun unsubscribe(uuid: String) { if (!conn.unsubscribe(uuid)) note(LogKind.ERROR, "Không ngừng nghe được $uuid") }
    fun readInfo(uuid: String) { if (!conn.readStandardInfo(uuid)) note(LogKind.ERROR, "Không đọc được $uuid") }

    // ---------------------------------------------------------------- thiết bị đã lưu
    fun save(r: AdvertRecord, alias: String? = null, preferred: Boolean = false) = viewModelScope.launch {
        repo.saveDevices(SavedDeviceCodec.upsert(saved.value, SavedDevice(r.address, r.name, alias?.takeIf { it.isNotBlank() }, r.advertisedServiceUuids,
            System.currentTimeMillis(), preferred, r.manufacturerData.keys.sorted())))
    }
    fun togglePreferred(d: SavedDevice) = viewModelScope.launch { repo.saveDevices(SavedDeviceCodec.upsert(saved.value, d.copy(preferred = !d.preferred))) }
    fun removeSaved(address: String) = viewModelScope.launch { repo.saveDevices(SavedDeviceCodec.remove(saved.value, address)) }
    fun isSaved(r: AdvertRecord) = saved.value.any { SavedDeviceMatcher.matches(it, r) }

    /**
     * Tự kết nối tới thiết bị ưu tiên đã lưu (AutoConnectController: nhiều tín hiệu nhận diện, có backoff, dừng khi nghi Y-Connect giữ kết nối).
     * Chỉ tự chạy MỘT lần mỗi lần mở app và chỉ khi cài đặt cho phép; không tự xin quyền.
     */
    fun autoConnectOnce(enabled: Boolean) {
        if (autoTried || !enabled) return
        if (saved.value.none { it.preferred }) return
        if (BlePreflight.check(getApplication()).isNotEmpty()) return   // thiếu quyền/Bluetooth tắt: im lặng, không tự xin quyền
        if (startAutoConnect()) autoTried = true
    }

    /** Người dùng bấm "Tự tìm xe đã lưu": đặt lại bộ đếm và chạy lại (nguồn duy nhất để thử lại sau khi nghi Y-Connect giữ kết nối). */
    fun startAutoConnect(): Boolean {
        if (scanJob != null) return false
        val profile = saved.value.firstOrNull { it.preferred } ?: saved.value.firstOrNull() ?: return false
        currentAddr = null; problem.value = null; authSeen.value = false
        note(LogKind.INFO, "Tự kết nối: đang tìm hồ sơ xe đã lưu")
        return autoController.start(profile) { autoAllowed.value }
    }

    fun cancelAuto() { autoController.cancel() }

    // ---------------------------------------------------------------- phiên khảo sát, ghép đôi, dừng khẩn cấp
    fun setSurveyLabel(l: SessionLabel) { surveyLabel.value = l }
    fun startSurvey() = startScan(surveyLabel.value, 20_000)
    fun clearSessions() { sessionList.clear(); sessionSeq = 0; scanSessions.value = emptyList() }

    /** Người dùng chọn một ứng viên để kết nối thử (cho phép rõ ràng). Chỉ nối GATT và liệt kê dịch vụ, không ghi gì. */
    fun connectCandidate(c: Candidate) { connect(c.latest.address) }

    /** Lưu ứng viên làm hồ sơ xe (người dùng xác nhận). Đặt ưu tiên để tự kết nối nếu cài đặt cho phép. */
    fun saveCandidate(c: Candidate) { save(c.latest, preferred = true) }

    /** Bắt đầu ghép đôi theo quy trình Android cho thiết bị đang chọn. Chỉ gọi từ nút do người dùng bấm. */
    fun requestPairing() {
        val a = currentAddr ?: run { note(LogKind.ERROR, "Chưa chọn thiết bị để ghép đôi"); return }
        val ok = bond.requestPairing(a)
        note(if (ok) LogKind.INFO else LogKind.ERROR, if (ok) "Đã yêu cầu hệ thống Android ghép đôi (người dùng xác nhận trên hộp thoại)" else "Không bắt đầu được ghép đôi (thiếu quyền, Bluetooth tắt hoặc hệ thống từ chối)")
    }

    /** Dừng khẩn cấp: kết thúc quét, hủy tự kết nối và đóng phiên GATT. */
    fun emergencyStop() {
        scanJob?.cancel()
        autoController.emergencyStop()
        conn.disconnect()
        note(LogKind.INFO, "DỪNG KHẨN CẤP: đã kết thúc quét và đóng GATT")
    }

    // ---------------------------------------------------------------- phiên thử nghiệm theo sự kiện + báo cáo giao thức
    /** Bắt đầu một phiên thử nghiệm có nhãn (ví dụ "Ga nhẹ", "Đèn pha bật"): gói nhận được sau đó được gom theo nhãn để so sánh. */
    fun beginSegment(name: String) {
        val n = name.trim(); if (n.isEmpty()) return
        currentSegment = n; segments.getOrPut(n) { ArrayList() }
        segmentNames.value = segments.keys.toList(); activeSegment.value = n
        note(LogKind.INFO, "Bắt đầu phiên thử nghiệm: $n")
    }
    fun endSegment() { currentSegment = null; activeSegment.value = null; refreshReport() }

    fun refreshReport() {
        val services = (conn.state.value as? ConnState.Ready)?.services ?: surveyServices[currentAddr ?: ""] ?: emptyList()
        findings.value = ProtocolReport.build(ReportInput(rawFrames.toList(), actionTags.toList(), services, registry, authSeen.value,
            segments.mapValues { it.value.toList() }, gpsSpeeds.toList()))
        refreshCapabilities()
    }

    /**
     * Dựng lại danh mục chức năng từ bằng chứng THẬT: adapter đã xác minh khớp thiết bị đang nối, dữ liệu xe đã giải mã,
     * trường khớp tốc độ GPS (nếu có), trạng thái cầu nối TFT. Không có bằng chứng thì mọi chức năng xe là UNKNOWN/UNSUPPORTED.
     */
    fun refreshCapabilities() {
        val st = conn.state.value
        val ready = st as? ConnState.Ready
        val adapter = ready?.let { registry.find(it.services) }
        val bridge = getApplication<NvxApp>().navAdapters.resolve()
        val now = System.currentTimeMillis()
        val veh = VehicleCapabilityRegistry.evaluate(VehicleEvidenceInputs(
            adapter = adapter, telemetry = telemetry.state.value, nowMs = now,
            speedMatch = SpeedCrossCheck.find(rawFrames, gpsSpeeds).firstOrNull(),
            gattCharacteristics = ready?.let { GattInspector.summarize(it.services).characteristics },
            tftStatus = bridge.status, tftLimitation = bridge.limitation, lastError = (st as? ConnState.Error)?.reason))
        capabilityReport.value = CapabilityReport.build(veh, now, AppInfo.VERSION, adapters = registry.evidences().map { "${it.id} (${it.protocolVersion})" })
    }

    // Xuất báo cáo chức năng: không chứa MAC/tên thiết bị; vẫn chạy qua bộ che địa chỉ để phòng lỗi nhập từ thông báo lỗi hệ thống.
    private fun capabilityOrBuild(): CapabilityReport { refreshCapabilities(); return capabilityReport.value!! }
    fun capabilityJson() = LogExporter.redactText(capabilityOrBuild().toJson(), salt)
    fun capabilityCsv() = LogExporter.redactText(capabilityOrBuild().toCsv(), salt)
    fun capabilityText() = LogExporter.redactText(capabilityOrBuild().toText(), salt)

    /** Báo cáo + ứng viên + GATT dạng văn bản, đã che địa chỉ MAC. */
    fun reportText(): String {
        refreshReport()
        val sb = StringBuilder()
        sb.appendLine("NVX Connect ${AppInfo.VERSION} — báo cáo tự tìm/kết nối (đã che thông tin nhạy cảm)")
        sb.appendLine()
        sb.appendLine("ỨNG VIÊN (${candidates.value.size}) — không ứng viên nào được xác nhận là Yamaha chỉ từ quảng bá")
        candidates.value.take(10).forEach { c ->
            sb.appendLine("- ${SurveyRedactor.name(c.displayName)} ${SurveyRedactor.address(c.latest.address, salt)} · ${c.confidence.title} · điểm ${c.score}")
            c.evidence.forEach { e -> sb.appendLine("    ${e.text}") }
            c.nextStep?.let { sb.appendLine("    → $it") }
        }
        sb.appendLine()
        inspector.value?.let { sb.appendLine(it.render()) }
        sb.appendLine("Xác thực: ${auth.value.headline}")
        sb.appendLine("Trạng thái: ${autoStatus.value.headline}")
        sb.appendLine()
        sb.append(ProtocolReport.render(findings.value))
        return LogExporter.redactText(sb.toString(), salt)
    }

    // ---------------------------------------------------------------- xuất log (đã che theo mặc định)
    /** Mỗi lần xuất JSON, app TỰ chạy phân tích và đính kèm kết quả (không cần bấm thêm). */
    private fun snapshotJson(redact: Boolean): String {
        refreshReport()
        return LogExporter.toJson(logBuf.snapshot(), table.records(), surveyServices, salt, System.currentTimeMillis(), AppInfo.VERSION, redact,
            findings.value.map { it.level.name to (it.title + (it.detail?.let { d -> " — $d" } ?: "")) })
    }
    fun exportJson() = snapshotJson(redact = true)
    fun exportCsv() = LogExporter.toCsv(logBuf.snapshot(), salt, redact = true)
    fun analysisText() = FrameAnalyzer.render(FrameAnalyzer.analyze(rawFrames))
    fun clearLog() {
        recorder.clear(); recStats.value = RecSummary(); fieldVerdicts.value = emptyList()
        logBuf.clear(); rawFrames.clear(); events.value = emptyList(); frameLines.value = emptyList(); frameCount.value = 0
        actionTags.clear(); tagCounts.value = emptyMap(); segments.clear(); currentSegment = null; segmentNames.value = emptyList(); activeSegment.value = null
        findings.value = emptyList(); valueSeen.value = false; gpsSpeeds.clear()
    }

    // ---------------------------------------------------------------- khảo sát có hướng dẫn (tương quan hành động)
    /** Gắn nhãn: bạn VỪA làm hành động này trên xe thật (ví dụ vặn ga). Chỉ ghi lại thời điểm, không đọc/ghi gì vào xe. */
    fun tagAction(label: String) {
        val clean = label.trim(); if (clean.isEmpty()) return
        actionTags += CorrelationEngine.Tag(clean, System.currentTimeMillis())
        tagCounts.value = actionTags.groupingBy { it.label }.eachCount()
        note(LogKind.INFO, "Gắn nhãn hành động: $clean")
    }
    fun clearTags() { actionTags.clear(); tagCounts.value = emptyMap() }
    /** Chỉ là gợi ý để người khảo sát tự đối chiếu; không tự gán nhãn trường, không tự chuyển sang VERIFIED. */
    fun correlationText() = CorrelationEngine.render(CorrelationEngine.correlate(rawFrames, actionTags), tagCounts.value)

    override fun onCleared() { autoController.cancel(); bond.stop(); conn.dispose() }
}
