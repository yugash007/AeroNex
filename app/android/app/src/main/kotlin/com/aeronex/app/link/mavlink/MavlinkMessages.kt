package com.aeronex.app.link.mavlink

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Typed MAVLink 2 messages (ardupilotmega dialect as shipped in pymavlink 2.4.50).
 * Fields are written in wire order: base fields sorted by type size, then extensions in XML order.
 * Unsigned 8/16-bit and signed 8/16/32-bit fields are Int, uint32 is Long, uint64/int64 are Long bit patterns.
 */
sealed interface MavlinkMessage {
    val spec: MessageSpec<*>
    fun write(buffer: ByteBuffer)
}

abstract class MessageSpec<T : MavlinkMessage>(
    val messageId: Int,
    val name: String,
    val crcExtra: Int,
    /** Payload length without extension fields (MAVLink 1 length). */
    val minLength: Int,
    /** Untruncated MAVLink 2 payload length. */
    val maxLength: Int,
) {
    protected abstract fun read(buffer: ByteBuffer): T

    /** Decodes a received payload: truncated (MAVLink 2) bytes read as zero, bytes past [maxLength] are ignored. */
    fun decode(payload: ByteArray, offset: Int = 0, length: Int = payload.size - offset): T {
        val bytes = ByteArray(maxLength)
        System.arraycopy(payload, offset, bytes, 0, minOf(length, maxLength))
        return read(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))
    }
}

object MavlinkMessages {
    val specs: Map<Int, MessageSpec<*>> = listOf(
        Heartbeat, SysStatus, ParamRequestRead, ParamValue, Attitude, LocalPositionNed, GlobalPositionInt,
        CommandLong, CommandAck, SetPositionTargetLocalNed, PositionTargetLocalNed, RadioStatus, Timesync,
        EkfStatusReport, HomePosition, ExtendedSysState, StatusText, SetupSigning,
    ).associateBy { it.messageId }
}

/** The untruncated payload; the encoder strips trailing zero bytes. */
internal fun MavlinkMessage.payload(): ByteArray {
    val buffer = ByteBuffer.allocate(spec.maxLength).order(ByteOrder.LITTLE_ENDIAN)
    write(buffer)
    check(buffer.position() == spec.maxLength) { "${spec.name} wrote ${buffer.position()} bytes" }
    return buffer.array()
}

data class Heartbeat(
    val type: Int, val autopilot: Int, val baseMode: Int, val customMode: Long, val systemStatus: Int,
    val mavlinkVersion: Int = 3,
) : MavlinkMessage {
    override val spec get() = Companion
    override fun write(buffer: ByteBuffer) {
        buffer.putU32(customMode); buffer.putU8(type); buffer.putU8(autopilot); buffer.putU8(baseMode)
        buffer.putU8(systemStatus); buffer.putU8(mavlinkVersion)
    }
    companion object : MessageSpec<Heartbeat>(0, "HEARTBEAT", 50, 9, 9) {
        override fun read(buffer: ByteBuffer) = with(buffer) {
            val customMode = u32()
            Heartbeat(type = u8(), autopilot = u8(), baseMode = u8(), customMode = customMode, systemStatus = u8(),
                mavlinkVersion = u8())
        }
    }
}

data class SysStatus(
    val onboardControlSensorsPresent: Long, val onboardControlSensorsEnabled: Long,
    val onboardControlSensorsHealth: Long, val load: Int, val voltageBattery: Int, val currentBattery: Int,
    val batteryRemaining: Int, val dropRateComm: Int = 0, val errorsComm: Int = 0, val errorsCount1: Int = 0,
    val errorsCount2: Int = 0, val errorsCount3: Int = 0, val errorsCount4: Int = 0,
    val onboardControlSensorsPresentExtended: Long = 0, val onboardControlSensorsEnabledExtended: Long = 0,
    val onboardControlSensorsHealthExtended: Long = 0,
) : MavlinkMessage {
    override val spec get() = Companion
    override fun write(buffer: ByteBuffer) {
        buffer.putU32(onboardControlSensorsPresent); buffer.putU32(onboardControlSensorsEnabled)
        buffer.putU32(onboardControlSensorsHealth); buffer.putU16(load); buffer.putU16(voltageBattery)
        buffer.putU16(currentBattery); buffer.putU16(dropRateComm); buffer.putU16(errorsComm)
        buffer.putU16(errorsCount1); buffer.putU16(errorsCount2); buffer.putU16(errorsCount3)
        buffer.putU16(errorsCount4); buffer.putU8(batteryRemaining)
        buffer.putU32(onboardControlSensorsPresentExtended); buffer.putU32(onboardControlSensorsEnabledExtended)
        buffer.putU32(onboardControlSensorsHealthExtended)
    }
    companion object : MessageSpec<SysStatus>(1, "SYS_STATUS", 124, 31, 43) {
        override fun read(buffer: ByteBuffer) = with(buffer) {
            SysStatus(onboardControlSensorsPresent = u32(), onboardControlSensorsEnabled = u32(),
                onboardControlSensorsHealth = u32(), load = u16(), voltageBattery = u16(), currentBattery = i16(),
                dropRateComm = u16(), errorsComm = u16(), errorsCount1 = u16(), errorsCount2 = u16(),
                errorsCount3 = u16(), errorsCount4 = u16(), batteryRemaining = i8(),
                onboardControlSensorsPresentExtended = u32(), onboardControlSensorsEnabledExtended = u32(),
                onboardControlSensorsHealthExtended = u32())
        }
    }
}

data class ParamRequestRead(
    val targetSystem: Int, val targetComponent: Int, val paramId: String, val paramIndex: Int = -1,
) : MavlinkMessage {
    override val spec get() = Companion
    override fun write(buffer: ByteBuffer) {
        buffer.putU16(paramIndex); buffer.putU8(targetSystem); buffer.putU8(targetComponent)
        buffer.putChars(paramId, 16)
    }
    companion object : MessageSpec<ParamRequestRead>(20, "PARAM_REQUEST_READ", 214, 20, 20) {
        override fun read(buffer: ByteBuffer) = with(buffer) {
            val paramIndex = i16()
            ParamRequestRead(targetSystem = u8(), targetComponent = u8(), paramId = chars(16), paramIndex = paramIndex)
        }
    }
}

data class ParamValue(
    val paramId: String, val paramValue: Float, val paramType: Int, val paramCount: Int, val paramIndex: Int,
) : MavlinkMessage {
    override val spec get() = Companion
    override fun write(buffer: ByteBuffer) {
        buffer.putFloat(paramValue); buffer.putU16(paramCount); buffer.putU16(paramIndex)
        buffer.putChars(paramId, 16); buffer.putU8(paramType)
    }
    companion object : MessageSpec<ParamValue>(22, "PARAM_VALUE", 220, 25, 25) {
        override fun read(buffer: ByteBuffer) = with(buffer) {
            val paramValue = float
            val paramCount = u16()
            val paramIndex = u16()
            ParamValue(paramId = chars(16), paramValue = paramValue, paramType = u8(), paramCount = paramCount,
                paramIndex = paramIndex)
        }
    }
}

data class Attitude(
    val timeBootMs: Long, val roll: Float, val pitch: Float, val yaw: Float,
    val rollspeed: Float, val pitchspeed: Float, val yawspeed: Float,
) : MavlinkMessage {
    override val spec get() = Companion
    override fun write(buffer: ByteBuffer) {
        buffer.putU32(timeBootMs); buffer.putFloat(roll); buffer.putFloat(pitch); buffer.putFloat(yaw)
        buffer.putFloat(rollspeed); buffer.putFloat(pitchspeed); buffer.putFloat(yawspeed)
    }
    companion object : MessageSpec<Attitude>(30, "ATTITUDE", 39, 28, 28) {
        override fun read(buffer: ByteBuffer) = with(buffer) {
            Attitude(u32(), float, float, float, float, float, float)
        }
    }
}

data class LocalPositionNed(
    val timeBootMs: Long, val x: Float, val y: Float, val z: Float, val vx: Float, val vy: Float, val vz: Float,
) : MavlinkMessage {
    override val spec get() = Companion
    override fun write(buffer: ByteBuffer) {
        buffer.putU32(timeBootMs); buffer.putFloat(x); buffer.putFloat(y); buffer.putFloat(z)
        buffer.putFloat(vx); buffer.putFloat(vy); buffer.putFloat(vz)
    }
    companion object : MessageSpec<LocalPositionNed>(32, "LOCAL_POSITION_NED", 185, 28, 28) {
        override fun read(buffer: ByteBuffer) = with(buffer) {
            LocalPositionNed(u32(), float, float, float, float, float, float)
        }
    }
}

data class GlobalPositionInt(
    val timeBootMs: Long, val lat: Int, val lon: Int, val alt: Int, val relativeAlt: Int,
    val vx: Int, val vy: Int, val vz: Int, val hdg: Int,
) : MavlinkMessage {
    override val spec get() = Companion
    override fun write(buffer: ByteBuffer) {
        buffer.putU32(timeBootMs); buffer.putInt(lat); buffer.putInt(lon); buffer.putInt(alt)
        buffer.putInt(relativeAlt); buffer.putU16(vx); buffer.putU16(vy); buffer.putU16(vz); buffer.putU16(hdg)
    }
    companion object : MessageSpec<GlobalPositionInt>(33, "GLOBAL_POSITION_INT", 104, 28, 28) {
        override fun read(buffer: ByteBuffer) = with(buffer) {
            GlobalPositionInt(u32(), int, int, int, int, i16(), i16(), i16(), u16())
        }
    }
}

data class CommandLong(
    val targetSystem: Int, val targetComponent: Int, val command: Int, val confirmation: Int = 0,
    val param1: Float = 0f, val param2: Float = 0f, val param3: Float = 0f, val param4: Float = 0f,
    val param5: Float = 0f, val param6: Float = 0f, val param7: Float = 0f,
) : MavlinkMessage {
    override val spec get() = Companion
    override fun write(buffer: ByteBuffer) {
        buffer.putFloat(param1); buffer.putFloat(param2); buffer.putFloat(param3); buffer.putFloat(param4)
        buffer.putFloat(param5); buffer.putFloat(param6); buffer.putFloat(param7); buffer.putU16(command)
        buffer.putU8(targetSystem); buffer.putU8(targetComponent); buffer.putU8(confirmation)
    }
    companion object : MessageSpec<CommandLong>(76, "COMMAND_LONG", 152, 33, 33) {
        override fun read(buffer: ByteBuffer) = with(buffer) {
            val p = FloatArray(7) { float }
            CommandLong(command = u16(), targetSystem = u8(), targetComponent = u8(), confirmation = u8(),
                param1 = p[0], param2 = p[1], param3 = p[2], param4 = p[3], param5 = p[4], param6 = p[5], param7 = p[6])
        }
    }
}

data class CommandAck(
    val command: Int, val result: Int, val progress: Int = 0, val resultParam2: Int = 0,
    val targetSystem: Int = 0, val targetComponent: Int = 0,
) : MavlinkMessage {
    override val spec get() = Companion
    override fun write(buffer: ByteBuffer) {
        buffer.putU16(command); buffer.putU8(result); buffer.putU8(progress); buffer.putInt(resultParam2)
        buffer.putU8(targetSystem); buffer.putU8(targetComponent)
    }
    companion object : MessageSpec<CommandAck>(77, "COMMAND_ACK", 143, 3, 10) {
        override fun read(buffer: ByteBuffer) = with(buffer) {
            CommandAck(u16(), u8(), u8(), int, u8(), u8())
        }
    }
}

data class SetPositionTargetLocalNed(
    val timeBootMs: Long, val targetSystem: Int, val targetComponent: Int, val coordinateFrame: Int, val typeMask: Int,
    val x: Float, val y: Float, val z: Float, val vx: Float = 0f, val vy: Float = 0f, val vz: Float = 0f,
    val afx: Float = 0f, val afy: Float = 0f, val afz: Float = 0f, val yaw: Float = 0f, val yawRate: Float = 0f,
) : MavlinkMessage {
    override val spec get() = Companion
    override fun write(buffer: ByteBuffer) {
        buffer.putU32(timeBootMs)
        for (value in floatArrayOf(x, y, z, vx, vy, vz, afx, afy, afz, yaw, yawRate)) buffer.putFloat(value)
        buffer.putU16(typeMask); buffer.putU8(targetSystem); buffer.putU8(targetComponent); buffer.putU8(coordinateFrame)
    }
    companion object : MessageSpec<SetPositionTargetLocalNed>(84, "SET_POSITION_TARGET_LOCAL_NED", 143, 53, 53) {
        override fun read(buffer: ByteBuffer) = with(buffer) {
            val timeBootMs = u32()
            val f = FloatArray(11) { float }
            SetPositionTargetLocalNed(typeMask = u16(), targetSystem = u8(), targetComponent = u8(),
                coordinateFrame = u8(), timeBootMs = timeBootMs, x = f[0], y = f[1], z = f[2], vx = f[3], vy = f[4],
                vz = f[5], afx = f[6], afy = f[7], afz = f[8], yaw = f[9], yawRate = f[10])
        }
    }
}

data class PositionTargetLocalNed(
    val timeBootMs: Long, val coordinateFrame: Int, val typeMask: Int, val x: Float, val y: Float, val z: Float,
    val vx: Float, val vy: Float, val vz: Float, val afx: Float, val afy: Float, val afz: Float,
    val yaw: Float, val yawRate: Float,
) : MavlinkMessage {
    override val spec get() = Companion
    override fun write(buffer: ByteBuffer) {
        buffer.putU32(timeBootMs)
        for (value in floatArrayOf(x, y, z, vx, vy, vz, afx, afy, afz, yaw, yawRate)) buffer.putFloat(value)
        buffer.putU16(typeMask); buffer.putU8(coordinateFrame)
    }
    companion object : MessageSpec<PositionTargetLocalNed>(85, "POSITION_TARGET_LOCAL_NED", 140, 51, 51) {
        override fun read(buffer: ByteBuffer) = with(buffer) {
            val timeBootMs = u32()
            val f = FloatArray(11) { float }
            PositionTargetLocalNed(typeMask = u16(), coordinateFrame = u8(), timeBootMs = timeBootMs, x = f[0],
                y = f[1], z = f[2], vx = f[3], vy = f[4], vz = f[5], afx = f[6], afy = f[7], afz = f[8], yaw = f[9],
                yawRate = f[10])
        }
    }
}

data class RadioStatus(
    val rssi: Int, val remrssi: Int, val txbuf: Int, val noise: Int, val remnoise: Int, val rxerrors: Int, val fixed: Int,
) : MavlinkMessage {
    override val spec get() = Companion
    override fun write(buffer: ByteBuffer) {
        buffer.putU16(rxerrors); buffer.putU16(fixed); buffer.putU8(rssi); buffer.putU8(remrssi); buffer.putU8(txbuf)
        buffer.putU8(noise); buffer.putU8(remnoise)
    }
    companion object : MessageSpec<RadioStatus>(109, "RADIO_STATUS", 185, 9, 9) {
        override fun read(buffer: ByteBuffer) = with(buffer) {
            RadioStatus(rxerrors = u16(), fixed = u16(), rssi = u8(), remrssi = u8(), txbuf = u8(), noise = u8(),
                remnoise = u8())
        }
    }
}

data class Timesync(val tc1: Long, val ts1: Long) : MavlinkMessage {
    override val spec get() = Companion
    override fun write(buffer: ByteBuffer) {
        buffer.putLong(tc1); buffer.putLong(ts1)
    }
    companion object : MessageSpec<Timesync>(111, "TIMESYNC", 34, 16, 16) {
        override fun read(buffer: ByteBuffer) = with(buffer) { Timesync(long, long) }
    }
}

data class EkfStatusReport(
    val flags: Int, val velocityVariance: Float, val posHorizVariance: Float, val posVertVariance: Float,
    val compassVariance: Float, val terrainAltVariance: Float, val airspeedVariance: Float = 0f,
) : MavlinkMessage {
    override val spec get() = Companion
    override fun write(buffer: ByteBuffer) {
        buffer.putFloat(velocityVariance); buffer.putFloat(posHorizVariance); buffer.putFloat(posVertVariance)
        buffer.putFloat(compassVariance); buffer.putFloat(terrainAltVariance); buffer.putU16(flags)
        buffer.putFloat(airspeedVariance)
    }
    companion object : MessageSpec<EkfStatusReport>(193, "EKF_STATUS_REPORT", 71, 22, 26) {
        override fun read(buffer: ByteBuffer) = with(buffer) {
            EkfStatusReport(velocityVariance = float, posHorizVariance = float, posVertVariance = float,
                compassVariance = float, terrainAltVariance = float, flags = u16(), airspeedVariance = float)
        }
    }
}

data class HomePosition(
    val latitude: Int, val longitude: Int, val altitude: Int, val x: Float, val y: Float, val z: Float,
    /** Quaternion w, x, y, z. */
    val q: List<Float>, val approachX: Float, val approachY: Float, val approachZ: Float, val timeUsec: Long = 0,
) : MavlinkMessage {
    init { require(q.size == 4) { "HOME_POSITION q needs 4 values" } }
    override val spec get() = Companion
    override fun write(buffer: ByteBuffer) {
        buffer.putInt(latitude); buffer.putInt(longitude); buffer.putInt(altitude)
        buffer.putFloat(x); buffer.putFloat(y); buffer.putFloat(z)
        q.forEach { buffer.putFloat(it) }
        buffer.putFloat(approachX); buffer.putFloat(approachY); buffer.putFloat(approachZ); buffer.putLong(timeUsec)
    }
    companion object : MessageSpec<HomePosition>(242, "HOME_POSITION", 104, 52, 60) {
        override fun read(buffer: ByteBuffer) = with(buffer) {
            HomePosition(int, int, int, float, float, float, List(4) { float }, float, float, float, long)
        }
    }
}

data class ExtendedSysState(val vtolState: Int, val landedState: Int) : MavlinkMessage {
    override val spec get() = Companion
    override fun write(buffer: ByteBuffer) {
        buffer.putU8(vtolState); buffer.putU8(landedState)
    }
    companion object : MessageSpec<ExtendedSysState>(245, "EXTENDED_SYS_STATE", 130, 2, 2) {
        override fun read(buffer: ByteBuffer) = with(buffer) { ExtendedSysState(u8(), u8()) }
    }
}

data class StatusText(val severity: Int, val text: String, val id: Int = 0, val chunkSeq: Int = 0) : MavlinkMessage {
    override val spec get() = Companion
    override fun write(buffer: ByteBuffer) {
        buffer.putU8(severity); buffer.putChars(text, 50); buffer.putU16(id); buffer.putU8(chunkSeq)
    }
    companion object : MessageSpec<StatusText>(253, "STATUSTEXT", 83, 51, 54) {
        override fun read(buffer: ByteBuffer) = with(buffer) { StatusText(u8(), chars(50), u16(), u8()) }
    }
}

/** Key provisioning. Only ever sent over a direct USB cable to the FC (enforced by the link layer, spec 4.13). */
class SetupSigning(
    val targetSystem: Int, val targetComponent: Int, secretKey: ByteArray, val initialTimestamp: Long,
) : MavlinkMessage {
    private val key = secretKey.copyOf()
    init { require(key.size == 32) { "SETUP_SIGNING key must be 32 bytes" } }
    val secretKey: ByteArray get() = key.copyOf()

    override val spec get() = Companion
    override fun write(buffer: ByteBuffer) {
        buffer.putLong(initialTimestamp); buffer.putU8(targetSystem); buffer.putU8(targetComponent); buffer.put(key)
    }
    override fun equals(other: Any?) = other is SetupSigning && other.targetSystem == targetSystem &&
        other.targetComponent == targetComponent && other.initialTimestamp == initialTimestamp && other.key.contentEquals(key)
    override fun hashCode() = 31 * (31 * (31 * targetSystem + targetComponent) + initialTimestamp.hashCode()) + key.contentHashCode()
    override fun toString() =
        "SetupSigning(targetSystem=$targetSystem, targetComponent=$targetComponent, secretKey=<redacted>, initialTimestamp=$initialTimestamp)"

    companion object : MessageSpec<SetupSigning>(256, "SETUP_SIGNING", 71, 42, 42) {
        override fun read(buffer: ByteBuffer) = with(buffer) {
            val initialTimestamp = long
            val targetSystem = u8()
            val targetComponent = u8()
            SetupSigning(targetSystem, targetComponent, ByteArray(32).also { get(it) }, initialTimestamp)
        }
    }
}

private fun ByteBuffer.u8() = get().toInt() and 0xFF
private fun ByteBuffer.i8() = get().toInt()
private fun ByteBuffer.u16() = short.toInt() and 0xFFFF
private fun ByteBuffer.i16() = short.toInt()
private fun ByteBuffer.u32() = int.toLong() and 0xFFFFFFFFL
private fun ByteBuffer.chars(size: Int): String {
    val bytes = ByteArray(size).also { get(it) }
    val end = bytes.indexOf(0).let { if (it < 0) size else it }
    return String(bytes, 0, end, Charsets.US_ASCII)
}

private fun ByteBuffer.putU8(value: Int) { put(value.toByte()) }
private fun ByteBuffer.putU16(value: Int) { putShort(value.toShort()) }
private fun ByteBuffer.putU32(value: Long) { putInt(value.toInt()) }
private fun ByteBuffer.putChars(value: String, size: Int) {
    val bytes = value.toByteArray(Charsets.US_ASCII)
    put(bytes, 0, minOf(bytes.size, size))
    repeat(size - minOf(bytes.size, size)) { put(0) }
}
