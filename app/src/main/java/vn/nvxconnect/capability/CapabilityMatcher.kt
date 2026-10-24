package vn.nvxconnect.capability

import vn.nvxconnect.util.MiniJson

/**
 * Kết quả đối chiếu một dòng:
 *  - CAN_INTEGRATE (1): app hỗ trợ VÀ xe cung cấp dữ liệu đã xác minh.
 *  - AWAITING_ADAPTER (2): app hỗ trợ nhưng xe chưa có giao thức được xác minh.
 *  - NEEDS_APP_WORK (3): xe có dữ liệu đã xác minh nhưng app chưa hỗ trợ → việc triển khai UI/data layer.
 *  - UNKNOWN (4): cả hai chưa có bằng chứng; không giả định khả năng.
 *  - PHONE_INDEPENDENT (5): chỉ cần điện thoại; chạy độc lập, không cần dữ liệu xe.
 *  - BLOCKED (mở rộng): xe/giao thức đã có căn cứ là KHÔNG hỗ trợ (ví dụ cầu nối TFT UNSUPPORTED) — khác UNKNOWN ở chỗ có căn cứ.
 */
enum class Integration(val label: String) {
    CAN_INTEGRATE("Có thể tích hợp"),
    AWAITING_ADAPTER("Chờ protocol adapter"),
    NEEDS_APP_WORK("Cần triển khai trong app"),
    UNKNOWN("Chưa biết"),
    PHONE_INDEPENDENT("Độc lập với xe"),
    BLOCKED("Bị chặn (chưa hỗ trợ)")
}

data class MatchRow(
    val function: String,
    val vehicleCapabilityId: String?,
    val appCapabilityIds: List<String>,
    /** Nguồn dữ liệu thực sự (ví dụ "Xe (adapter đã xác minh)", "Điện thoại/GPS"). */
    val dataSource: String,
    /** Trạng thái giao thức/bằng chứng phía xe; "—" với chức năng chỉ cần điện thoại. */
    val protocolState: String,
    val integration: Integration,
    val missing: List<String>,
    val nextStep: String
)

object CapabilityMatcher {
    fun match(vehicle: List<VehicleCapability>, app: List<AppCapability>): List<MatchRow> {
        val rows = ArrayList<MatchRow>()
        for (v in vehicle) {
            val consumers = app.filter { it.consumes == v.id }
            val implemented = consumers.filter { it.implemented }
            val ids = consumers.map { it.id }
            val src = v.source.label + if (v.verified) " (adapter đã xác minh)" else ""
            rows += when {
                v.verified && implemented.isNotEmpty() -> MatchRow(v.name, v.id, ids, src, v.status.name, Integration.CAN_INTEGRATE,
                    consumers.filter { !it.implemented }.map { "Chưa làm: ${it.name}" },
                    "Nghiệm thu trên xe thật (docs/TEST_CHECKLIST.md) rồi mới coi là hoàn tất.")
                v.verified -> MatchRow(v.name, v.id, ids, src, v.status.name, Integration.NEEDS_APP_WORK,
                    listOf("Giao diện/lớp dữ liệu trong app cho chức năng này"),
                    "Tạo công việc triển khai hiển thị '${v.name}' (giữ nhãn nguồn LIVE_VERIFIED, bỏ qua khi giá trị cũ).")
                v.status == CapabilityStatus.UNSUPPORTED -> MatchRow(v.name, v.id, ids, src, v.status.name, Integration.BLOCKED,
                    listOf("Giao thức/xe hiện không hỗ trợ: ${v.evidence}"),
                    "Cần tài liệu giao thức hoặc cơ chế tích hợp được Yamaha cho phép, rồi thử trên xe thật.")
                implemented.isNotEmpty() -> MatchRow(v.name, v.id, ids, src, v.status.name, Integration.AWAITING_ADAPTER,
                    listOf("Adapter giao thức đã xác minh cho '${v.name}'"),
                    nextStepForUnverified(v))
                else -> MatchRow(v.name, v.id, ids, src, v.status.name, Integration.UNKNOWN,
                    listOf("Bằng chứng từ xe", "Hỗ trợ trong app"),
                    "Thu thập bằng chứng trên xe thật trước; không giả định xe có chức năng này.")
            }
        }
        for (a in app.filter { it.kind == AppKind.PHONE_ONLY }) {
            val missing = ArrayList<String>()
            if (!a.worksWithoutVehicle) missing += "Vi phạm nguyên tắc: chức năng chỉ cần điện thoại nhưng khai báo cần xe"
            if (!a.implemented) missing += "Chưa triển khai"
            if (a.needsNetwork) missing += "Cần mạng"
            if (a.needsGps) missing += "Cần GPS/quyền vị trí"
            rows += MatchRow(a.name, null, listOf(a.id), "Điện thoại/GPS/người dùng (${a.dataKind.label})", "—", Integration.PHONE_INDEPENDENT, missing,
                if (a.implemented) "Không phụ thuộc xe. Mức kiểm thử: ${a.testLevel.name}." else "Triển khai (không cần dữ liệu xe).")
        }
        return rows
    }

    private fun nextStepForUnverified(v: VehicleCapability) = when (v.status) {
        CapabilityStatus.HYPOTHESIS -> "Kiểm chứng giả thuyết độc lập (nhiều lần chạy, đối chiếu đồng hồ xe) rồi mới đăng ký adapter có hồ sơ bằng chứng."
        else -> "Khảo sát GATT và thu gói notify; cần adapter đã xác minh (docs/PROTOCOL_EVIDENCE_TEMPLATE.md)."
    }
}

/** Báo cáo đầy đủ để xem/xuất. Không chứa địa chỉ MAC, tên thiết bị, VIN hay khóa: chỉ trạng thái và bằng chứng mô tả. */
data class CapabilityReport(
    val generatedAtMs: Long,
    val appVersion: String,
    val vehicle: List<VehicleCapability>,
    val app: List<AppCapability>,
    val matches: List<MatchRow>,
    val profiles: List<ProtocolProfile>,
    /** Hồ sơ bằng chứng của các adapter đã đăng ký (id + phiên bản); rỗng khi chưa có. */
    val registeredAdapters: List<String>
) {
    /** Dòng cần xử lý tiếp: mọi thứ KHÔNG phải CAN_INTEGRATE hoặc PHONE_INDEPENDENT hoàn chỉnh. */
    val unsupported: List<MatchRow> get() = matches.filter {
        it.integration != Integration.CAN_INTEGRATE && !(it.integration == Integration.PHONE_INDEPENDENT && it.missing.none { m -> m.startsWith("Vi phạm") || m == "Chưa triển khai" })
    }

    fun counts(): Map<Integration, Int> = matches.groupingBy { it.integration }.eachCount()

    fun toText(): String = buildString {
        appendLine("NVX Connect $appVersion — BÁO CÁO CHỨC NĂNG (${AppCapabilityRegistry.CATALOG_VERSION} / ${VehicleCapabilityRegistry.PROFILE_VERSION})")
        appendLine("Xe mục tiêu: ${ProtocolProfileCatalog.TARGET_VEHICLE}. Mọi trạng thái dưới đây dựa trên bằng chứng quan sát được; không có dữ liệu xe giả.")
        appendLine("Adapter giao thức đã xác minh: " + if (registeredAdapters.isEmpty()) "KHÔNG CÓ" else registeredAdapters.joinToString())
        appendLine()
        appendLine("== CHỨC NĂNG XE (${vehicle.size}) ==")
        vehicle.forEach { v -> appendLine("[${v.status.name}] ${v.name}"); appendLine("    ${v.evidence}") }
        appendLine()
        appendLine("== CHỨC NĂNG APP (${app.size}) ==")
        app.forEach { a ->
            appendLine("- ${a.name}: ${if (a.implemented) "đã triển khai" else "CHƯA triển khai"}; test=${a.testLevel.name}; dữ liệu=${a.dataKind.label}; " +
                "không cần xe=${if (a.worksWithoutVehicle) "có" else "không"}")
        }
        appendLine()
        appendLine("== ĐỐI CHIẾU ==")
        matches.forEach { m ->
            appendLine("${m.function} | ${m.dataSource} | ${m.protocolState} | ${m.integration.label}")
            if (m.missing.isNotEmpty()) appendLine("    Còn thiếu: ${m.missing.joinToString("; ")}")
            appendLine("    Bước tiếp: ${m.nextStep}")
        }
        appendLine()
        appendLine("== CHƯA HỖ TRỢ / CẦN LÀM (${unsupported.size}) ==")
        unsupported.forEach { appendLine("- [${it.integration.name}] ${it.function}") }
        appendLine()
        appendLine("== HỒ SƠ GIAO THỨC THAM CHIẾU (${profiles.size}) ==")
        profiles.forEach { p ->
            appendLine("- ${p.title} · license: ${p.sourceLicense} · kênh: ${p.transport} · NVX: ${p.nvxCompatibility.label}")
        }
    }

    fun toCsv(): String = buildString {
        appendLine("function,vehicle_capability,app_capabilities,data_source,protocol_state,integration,missing,next_step")
        matches.forEach { m ->
            appendLine(listOf(m.function, m.vehicleCapabilityId ?: "", m.appCapabilityIds.joinToString("|"), m.dataSource, m.protocolState,
                m.integration.name, m.missing.joinToString("; "), m.nextStep).joinToString(",") { csv(it) })
        }
    }

    fun toJson(): String = buildString {
        fun q(s: String?) = MiniJson.quoteOrNull(s)
        fun arr(l: List<String>) = l.joinToString(",", "[", "]") { MiniJson.quote(it) }
        append("{\"generatedAtMs\":").append(generatedAtMs)
        append(",\"appVersion\":").append(q(appVersion))
        append(",\"catalogVersions\":{\"app\":").append(q(AppCapabilityRegistry.CATALOG_VERSION)).append(",\"vehicle\":").append(q(VehicleCapabilityRegistry.PROFILE_VERSION)).append("}")
        append(",\"targetVehicle\":").append(q(ProtocolProfileCatalog.TARGET_VEHICLE))
        append(",\"registeredAdapters\":").append(arr(registeredAdapters))
        append(",\"vehicleCapabilities\":[")
        append(vehicle.joinToString(",") { v ->
            "{\"id\":${q(v.id)},\"name\":${q(v.name)},\"source\":${q(v.source.name)},\"status\":${q(v.status.name)},\"evidence\":${q(v.evidence)}," +
                "\"serviceRefs\":${arr(v.serviceRefs)},\"dataType\":${q(v.dataType)},\"unit\":${q(v.unit)},\"method\":${q(v.method?.name)}," +
                "\"checkedAtMs\":${v.checkedAtMs ?: "null"},\"profileVersion\":${q(v.profileVersion)},\"freshnessMs\":${v.freshnessMs ?: "null"},\"lastError\":${q(v.lastError)}}"
        })
        append("],\"appCapabilities\":[")
        append(app.joinToString(",") { a ->
            "{\"id\":${q(a.id)},\"name\":${q(a.name)},\"kind\":${q(a.kind.name)},\"implemented\":${a.implemented},\"testLevel\":${q(a.testLevel.name)}," +
                "\"testRefs\":${arr(a.testRefs)},\"testScope\":${q(a.testScope)},\"permissions\":${arr(a.permissions)},\"needsNetwork\":${a.needsNetwork}," +
                "\"needsGps\":${a.needsGps},\"needsExternalDevice\":${a.needsExternalDevice},\"dataKind\":${q(a.dataKind.name)}," +
                "\"worksWithoutVehicle\":${a.worksWithoutVehicle},\"consumes\":${q(a.consumes)},\"note\":${q(a.note)}}"
        })
        append("],\"matches\":[")
        append(matches.joinToString(",") { m ->
            "{\"function\":${q(m.function)},\"vehicleCapability\":${q(m.vehicleCapabilityId)},\"appCapabilities\":${arr(m.appCapabilityIds)}," +
                "\"dataSource\":${q(m.dataSource)},\"protocolState\":${q(m.protocolState)},\"integration\":${q(m.integration.name)}," +
                "\"missing\":${arr(m.missing)},\"nextStep\":${q(m.nextStep)}}"
        })
        append("],\"protocolProfiles\":[")
        append(profiles.joinToString(",") { p ->
            "{\"id\":${q(p.id)},\"title\":${q(p.title)},\"sourceUrl\":${q(p.sourceUrl)},\"license\":${q(p.sourceLicense)},\"transport\":${q(p.transport)}," +
                "\"vehiclesStatedBySource\":${arr(p.vehiclesStatedBySource)},\"controller\":${q(p.controllerStatedBySource)}," +
                "\"softwareVersion\":${q(p.softwareVersionStatedBySource)},\"nvxCompatibility\":${q(p.nvxCompatibility.name)}," +
                "\"compatibilityEvidence\":${q(p.compatibilityEvidence)},\"usageNote\":${q(p.usageNote)},\"retrievedOn\":${q(p.retrievedOn)}}"
        })
        append("]}")
    }

    /** Escape CSV và vô hiệu hóa công thức bảng tính (ô bắt đầu bằng = + - @). */
    private fun csv(s: String): String {
        val safe = if (s.isNotEmpty() && s[0] in "=+-@\t\r") "'$s" else s
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }

    companion object {
        fun build(vehicle: List<VehicleCapability>, nowMs: Long, appVersion: String,
                  app: List<AppCapability> = AppCapabilityRegistry.all,
                  profiles: List<ProtocolProfile> = ProtocolProfileCatalog.builtIn,
                  adapters: List<String> = emptyList()) =
            CapabilityReport(nowMs, appVersion, vehicle, app, CapabilityMatcher.match(vehicle, app), profiles, adapters)
    }
}
