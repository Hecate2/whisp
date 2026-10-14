// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.edit
import app.whisp.playback.DecoderEvent
import java.io.File
import kotlinx.serialization.json.*

/** Only decoder names, MIME types and offload states enter the diagnostic report. */
class PlaybackDiagnostics(private val context: Context) {
    private val preferences =
        context.getSharedPreferences("playback-diagnostics", Context.MODE_PRIVATE)

    @Synchronized
    fun record(event: DecoderEvent) {
        val previous = runCatching {
            Json.parseToJsonElement(preferences.getString("events", "[]")!!).jsonArray
        }
            .getOrDefault(JsonArray(emptyList()))
        val next =
            previous.takeLast(63) +
                buildJsonObject {
                    put("kind", event.kind)
                    event.mime?.let { put("mime", it) }
                    event.codecs?.let { put("codecs", it) }
                    event.decoder?.let { put("decoder", it) }
                    event.hardwareAccelerated?.let { put("hardwareAccelerated", it) }
                    event.offloaded?.let { put("offloaded", it) }
                }
        preferences.edit { putString("events", JsonArray(next).toString()) }
    }

    @Synchronized
    fun snapshot(): List<DecoderEvent> {
        val events = runCatching {
            Json.parseToJsonElement(preferences.getString("events", "[]")!!).jsonArray
        }
            .getOrDefault(JsonArray(emptyList()))
        return events.map { element ->
            val fields = element.jsonObject
            DecoderEvent(
                fields["kind"]!!.jsonPrimitive.content,
                fields["mime"]?.jsonPrimitive?.content,
                fields["codecs"]?.jsonPrimitive?.content,
                fields["decoder"]?.jsonPrimitive?.content,
                fields["hardwareAccelerated"]?.jsonPrimitive?.boolean,
                fields["offloaded"]?.jsonPrimitive?.boolean,
            )
        }
    }

    @Synchronized
    fun clear() {
        preferences.edit { clear() }
    }

    fun shareIntent(): Intent {
        val events = runCatching {
            Json.parseToJsonElement(preferences.getString("events", "[]")!!)
        }
            .getOrDefault(JsonArray(emptyList()))
        val report = buildJsonObject {
            put("schema", 1)
            put("androidSdk", Build.VERSION.SDK_INT)
            put(
                "version",
                context.packageManager.getPackageInfo(context.packageName, 0).versionName,
            )
            put("events", events)
        }
        val directory = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val file = File(directory, "whisp-playback.json").apply { writeText(report.toString()) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
        return Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
