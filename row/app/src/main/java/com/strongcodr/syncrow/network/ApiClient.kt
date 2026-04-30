package com.strongcodr.syncrow.network

import com.strongcodr.syncrow.BuildConfig
import com.strongcodr.syncrow.model.IntervalUpload
import com.strongcodr.syncrow.model.LocationSample
import com.strongcodr.syncrow.model.LocationUpload
import com.strongcodr.syncrow.model.SensorDiagnostic
import com.strongcodr.syncrow.model.SensorSample
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

object ApiClient {

    private val INFLUX_BASE_URL = BuildConfig.INFLUX_URL
    private val INFLUX_ORG = BuildConfig.INFLUX_ORG
    private val INFLUX_BUCKET = BuildConfig.INFLUX_BUCKET
    private val INFLUX_TOKEN = BuildConfig.INFLUX_TOKEN

    // Avoid logging full bodies to prevent OOM on large payloads.
    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(logging)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.HOURS)
        .writeTimeout(3, TimeUnit.HOURS)
        .callTimeout(3, TimeUnit.HOURS)
        .build()

    private fun escapeTag(value: String): String =
        value.replace(" ", "\\ ")
            .replace(",", "\\,")
            .replace("=", "\\=")

    // InfluxDB write batch size, applied uniformly to IMU samples, location samples,
    // and diagnostic rows. Chosen to stay well under InfluxDB Cloud's line-protocol
    // payload limit while keeping connection count low.
    private const val MAX_ROWS_PER_BATCH = 2000

    private fun buildLineProtocol(payload: IntervalUpload, intervalLabel: String): String {
        return buildLineProtocol(payload.samples, payload, intervalLabel)
    }

    private fun buildLineProtocol(
        samples: List<SensorSample>,
        payload: IntervalUpload,
        intervalLabel: String
    ): String {
        val sb = StringBuilder()
        samples.forEach { s ->
            sb.append("imu")
                .append(",intervalId=").append(escapeTag(intervalLabel))
                .append(",sensorId=").append(escapeTag(payload.sensor_id))
            payload.seat?.let { seat ->
                sb.append(",seat=").append(escapeTag(seat))
            }
            sb.append(" ax=").append(s.ax)
                .append(",ay=").append(s.ay)
                .append(",az=").append(s.az)
                .append(",intervalNumeric=").append(payload.interval_id).append('i')
            s.wx?.let { sb.append(",wx=").append(it) }
            s.wy?.let { sb.append(",wy=").append(it) }
            s.wz?.let { sb.append(",wz=").append(it) }
            s.roll?.let { sb.append(",roll=").append(it) }
            s.pitch?.let { sb.append(",pitch=").append(it) }
            s.yaw?.let { sb.append(",yaw=").append(it) }
            sb.append(" ").append(s.timestampMs).append("\n")
        }
        return sb.toString()
    }

    private fun buildLocationLineProtocol(payload: LocationUpload, intervalLabel: String): String {
        return buildLocationLineProtocol(payload.samples, payload, intervalLabel)
    }

    private fun buildLocationLineProtocol(
        samples: List<LocationSample>,
        payload: LocationUpload,
        intervalLabel: String
    ): String {
        val sb = StringBuilder()
        samples.forEach { s ->
            sb.append("phone_location")
                .append(",intervalId=").append(escapeTag(intervalLabel))
                .append(" lat=").append(s.latitude)
                .append(",lon=").append(s.longitude)
                .append(",intervalNumeric=").append(payload.interval_id).append('i')
            s.altitude?.let { sb.append(",altitude=").append(it) }
            s.accuracy?.let { sb.append(",accuracy=").append(it) }
            s.speed?.let { sb.append(",speed=").append(it) }
            s.bearing?.let { sb.append(",bearing=").append(it) }
            sb.append(" ").append(s.timestampMs).append("\n")
        }
        return sb.toString()
    }

    fun uploadInterval(payload: IntervalUpload, intervalLabel: String): Pair<Boolean, Int> {
        var lastCode = -1
        payload.samples.chunked(MAX_ROWS_PER_BATCH).forEach { chunk ->
            if (chunk.isEmpty()) return@forEach
            val lineProtocol = buildLineProtocol(chunk, payload, intervalLabel)
            val body = lineProtocol.toRequestBody("text/plain; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url("$INFLUX_BASE_URL/api/v2/write?org=$INFLUX_ORG&bucket=$INFLUX_BUCKET&precision=ms")
                .addHeader("Authorization", "Token $INFLUX_TOKEN")
                .post(body)
                .build()

            client.newCall(request).execute().use { resp ->
                lastCode = resp.code
                if (!resp.isSuccessful) {
                    return Pair(false, resp.code)
                }
            }
        }
        return Pair(true, lastCode)
    }

    private fun buildDiagnosticsLineProtocol(
        rows: List<SensorDiagnostic>,
        intervalLabel: String
    ): String {
        val sb = StringBuilder()
        rows.forEach { r ->
            sb.append("imu_diag")
                .append(",intervalId=").append(escapeTag(intervalLabel))
            r.sensorId?.let { sb.append(",sensorId=").append(escapeTag(it)) }
            r.seat?.let { sb.append(",seat=").append(escapeTag(it)) }
            sb.append(" received=").append(r.received).append('i')
                .append(",expected=").append(r.expected).append('i')
                .append(",dropPct=").append(r.dropPct)
                .append(",maxGapMs=").append(r.maxGapMs).append('i')
                .append(",jitterMs=").append(r.jitterMs)
                .append(",malformed=").append(r.malformed).append('i')
                .append(",connected=").append(if (r.connected) "t" else "f")
                .append(",configApplied=").append(if (r.configApplied) "t" else "f")
                .append(",configFailed=").append(if (r.configFailed) "t" else "f")
                .append(",reconnects=").append(r.reconnectsThisWindow).append('i')
                .append(",windowDurationMs=").append(r.windowDurationMs).append('i')
                .append(",intervalNumeric=").append(r.intervalId).append('i')
            r.rssi?.let { sb.append(",rssi=").append(it).append('i') }
            r.lastGattStatus?.let { sb.append(",lastGattStatus=").append(it).append('i') }
            r.connectionIntervalMs?.let { sb.append(",connIntervalMs=").append(it) }
            if (r.timeReceived > 0) sb.append(",timeReceived=").append(r.timeReceived).append('i')
            sb.append(' ').append(r.timestampMs).append('\n')
        }
        return sb.toString()
    }

    fun uploadDiagnostics(rows: List<SensorDiagnostic>, intervalLabel: String): Pair<Boolean, Int> {
        if (rows.isEmpty()) return Pair(true, 200)
        var lastCode = -1
        rows.chunked(MAX_ROWS_PER_BATCH).forEach { chunk ->
            if (chunk.isEmpty()) return@forEach
            val lineProtocol = buildDiagnosticsLineProtocol(chunk, intervalLabel)
            val body = lineProtocol.toRequestBody("text/plain; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url("$INFLUX_BASE_URL/api/v2/write?org=$INFLUX_ORG&bucket=$INFLUX_BUCKET&precision=ms")
                .addHeader("Authorization", "Token $INFLUX_TOKEN")
                .post(body)
                .build()

            client.newCall(request).execute().use { resp ->
                lastCode = resp.code
                if (!resp.isSuccessful) return Pair(false, resp.code)
            }
        }
        return Pair(true, lastCode)
    }

    fun uploadLocation(payload: LocationUpload, intervalLabel: String): Pair<Boolean, Int> {
        var lastCode = -1
        payload.samples.chunked(MAX_ROWS_PER_BATCH).forEach { chunk ->
            if (chunk.isEmpty()) return@forEach
            val lineProtocol = buildLocationLineProtocol(chunk, payload, intervalLabel)
            val body = lineProtocol.toRequestBody("text/plain; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url("$INFLUX_BASE_URL/api/v2/write?org=$INFLUX_ORG&bucket=$INFLUX_BUCKET&precision=ms")
                .addHeader("Authorization", "Token $INFLUX_TOKEN")
                .post(body)
                .build()

            client.newCall(request).execute().use { resp ->
                lastCode = resp.code
                if (!resp.isSuccessful) {
                    return Pair(false, resp.code)
                }
            }
        }
        return Pair(true, lastCode)
    }
}
