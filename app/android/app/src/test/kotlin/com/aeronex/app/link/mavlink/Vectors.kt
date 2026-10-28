package com.aeronex.app.link.mavlink

/** Loads src/test/resources/mavlink_vectors.json and builds typed messages from its recorded fields. */
@Suppress("UNCHECKED_CAST")
internal object Vectors {
    val root = MiniJson.parse(Vectors::class.java.getResource("/mavlink_vectors.json")!!.readText()) as Map<String, Any?>
    val frames = root["frames"] as List<Map<String, Any?>>
    val key = (root["signing_key_hex"] as String).hexToBytes()

    fun named(name: String) = frames.single { it["name"] == name }
    fun bytes(vector: Map<String, Any?>) = (vector["hex"] as String).hexToBytes()

    fun message(vector: Map<String, Any?>): MavlinkMessage {
        val f = vector["fields"] as Map<String, Any?>
        return when (vector["message"]) {
            "HEARTBEAT" -> Heartbeat(f.int("type"), f.int("autopilot"), f.int("base_mode"), f.long("custom_mode"),
                f.int("system_status"), f.int("mavlink_version"))
            "SYS_STATUS" -> SysStatus(f.long("onboard_control_sensors_present"), f.long("onboard_control_sensors_enabled"),
                f.long("onboard_control_sensors_health"), f.int("load"), f.int("voltage_battery"), f.int("current_battery"),
                f.int("battery_remaining"), f.int("drop_rate_comm"), f.int("errors_comm"), f.int("errors_count1"),
                f.int("errors_count2"), f.int("errors_count3"), f.int("errors_count4"),
                f.long("onboard_control_sensors_present_extended"), f.long("onboard_control_sensors_enabled_extended"),
                f.long("onboard_control_sensors_health_extended"))
            "PARAM_REQUEST_READ" -> ParamRequestRead(f.int("target_system"), f.int("target_component"),
                f["param_id"] as String, f.int("param_index"))
            "PARAM_VALUE" -> ParamValue(f["param_id"] as String, f.float("param_value"), f.int("param_type"),
                f.int("param_count"), f.int("param_index"))
            "GLOBAL_POSITION_INT" -> GlobalPositionInt(f.long("time_boot_ms"), f.int("lat"), f.int("lon"), f.int("alt"),
                f.int("relative_alt"), f.int("vx"), f.int("vy"), f.int("vz"), f.int("hdg"))
            "ATTITUDE" -> Attitude(f.long("time_boot_ms"), f.float("roll"), f.float("pitch"), f.float("yaw"),
                f.float("rollspeed"), f.float("pitchspeed"), f.float("yawspeed"))
            "LOCAL_POSITION_NED" -> LocalPositionNed(f.long("time_boot_ms"), f.float("x"), f.float("y"), f.float("z"),
                f.float("vx"), f.float("vy"), f.float("vz"))
            "COMMAND_LONG" -> CommandLong(f.int("target_system"), f.int("target_component"), f.int("command"),
                f.int("confirmation"), f.float("param1"), f.float("param2"), f.float("param3"), f.float("param4"),
                f.float("param5"), f.float("param6"), f.float("param7"))
            "COMMAND_ACK" -> CommandAck(f.int("command"), f.int("result"), f.int("progress"), f.int("result_param2"),
                f.int("target_system"), f.int("target_component"))
            "SET_POSITION_TARGET_LOCAL_NED" -> SetPositionTargetLocalNed(f.long("time_boot_ms"), f.int("target_system"),
                f.int("target_component"), f.int("coordinate_frame"), f.int("type_mask"), f.float("x"), f.float("y"),
                f.float("z"), f.float("vx"), f.float("vy"), f.float("vz"), f.float("afx"), f.float("afy"), f.float("afz"),
                f.float("yaw"), f.float("yaw_rate"))
            "POSITION_TARGET_LOCAL_NED" -> PositionTargetLocalNed(f.long("time_boot_ms"), f.int("coordinate_frame"),
                f.int("type_mask"), f.float("x"), f.float("y"), f.float("z"), f.float("vx"), f.float("vy"), f.float("vz"),
                f.float("afx"), f.float("afy"), f.float("afz"), f.float("yaw"), f.float("yaw_rate"))
            "RADIO_STATUS" -> RadioStatus(f.int("rssi"), f.int("remrssi"), f.int("txbuf"), f.int("noise"),
                f.int("remnoise"), f.int("rxerrors"), f.int("fixed"))
            "TIMESYNC" -> Timesync(f.long("tc1"), f.long("ts1"))
            "EKF_STATUS_REPORT" -> EkfStatusReport(f.int("flags"), f.float("velocity_variance"),
                f.float("pos_horiz_variance"), f.float("pos_vert_variance"), f.float("compass_variance"),
                f.float("terrain_alt_variance"), f.float("airspeed_variance"))
            "HOME_POSITION" -> HomePosition(f.int("latitude"), f.int("longitude"), f.int("altitude"), f.float("x"),
                f.float("y"), f.float("z"), (f["q"] as List<Any?>).map { float(it) }, f.float("approach_x"),
                f.float("approach_y"), f.float("approach_z"), f.long("time_usec"))
            "EXTENDED_SYS_STATE" -> ExtendedSysState(f.int("vtol_state"), f.int("landed_state"))
            "STATUSTEXT" -> StatusText(f.int("severity"), f["text"] as String, f.int("id"), f.int("chunk_seq"))
            "SETUP_SIGNING" -> SetupSigning(f.int("target_system"), f.int("target_component"),
                (f["secret_key"] as List<Any?>).map { (it as Long).toByte() }.toByteArray(), f.long("initial_timestamp"))
            else -> error("No factory for ${vector["message"]}")
        }
    }

    private fun float(value: Any?): Float = if (value == "NaN") Float.NaN else (value as Number).toDouble().toFloat()
    private fun Map<String, Any?>.float(key: String) = float(this[key])
}

internal fun Map<String, Any?>.int(key: String) = (this[key] as Number).toInt()
internal fun Map<String, Any?>.long(key: String) = (this[key] as Number).toLong()
internal fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
internal fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
