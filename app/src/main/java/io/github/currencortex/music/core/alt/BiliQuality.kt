// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.core.alt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Bilibili quality ids and the permissions returned by its playurl endpoint. */
object BiliQuality {
    data class Definition(
        val qn: Int,
        val label: String,
        val detail: String,
        val requiresLogin: Boolean = false,
        val requiresVip: Boolean = false,
    )

    data class Option(
        val qn: Int,
        val label: String,
        val detail: String,
        val requiresLogin: Boolean,
        val requiresVip: Boolean,
        val available: Boolean,
    )

    val definitions = listOf(
        Definition(127, "8K", "超高清", requiresLogin = true, requiresVip = true),
        Definition(126, "杜比视界", "HDR 音画", requiresLogin = true, requiresVip = true),
        Definition(125, "HDR 真彩", "高动态范围", requiresLogin = true, requiresVip = true),
        Definition(120, "4K", "超清 4K", requiresLogin = true, requiresVip = true),
        Definition(116, "1080P 60帧", "高帧率"),
        Definition(112, "1080P 高码率", "高码率", requiresLogin = true),
        Definition(80, "1080P", "高清"),
        Definition(74, "720P 60帧", "高帧率"),
        Definition(64, "720P", "准高清"),
        Definition(32, "480P", "清晰"),
        Definition(16, "360P", "流畅"),
    )

    @Volatile var preferredQn: Int = 80

    /** Set only while the Bilibili video surface is open, so ordinary playback stays audio-light. */
    @Volatile var wantsVideo: Boolean = false

    fun label(qn: Int): String = definitions.firstOrNull { it.qn == qn }?.label ?: "自动"

    /**
     * Convert playurl capabilities into the picker model. The endpoint can advertise formats
     * that the current account cannot access, so accept_quality/support_formats remains the
     * source of truth and locked rows stay visible with their actual reason.
     */
    fun optionsFromPlayurl(data: JsonObject): List<Option> {
        val accepted = data["accept_quality"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content.toIntOrNull() }
            ?.toSet()
            .orEmpty()
        val formats = data["support_formats"]?.jsonArray ?: JsonArray(emptyList())
        val byQn = formats.mapNotNull { element ->
            runCatching { element.jsonObject }.getOrNull()?.let { format ->
                val qn = format["quality"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@let null
                val label = format["new_description"]?.jsonPrimitive?.content
                    ?: format["display_desc"]?.jsonPrimitive?.content
                    ?: label(qn)
                Triple(
                    qn,
                    label,
                    ((format["need_login"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0) == 1) to
                        ((format["need_vip"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0) == 1),
                )
            }
        }.associateBy { it.first }

        return definitions.map { definition ->
            val api = byQn[definition.qn]
            val requiresLogin = definition.requiresLogin || api?.third?.first == true
            val requiresVip = definition.requiresVip || api?.third?.second == true
            Option(
                qn = definition.qn,
                label = api?.second ?: definition.label,
                detail = definition.detail,
                requiresLogin = requiresLogin,
                requiresVip = requiresVip,
                available = when {
                    accepted.isNotEmpty() -> definition.qn in accepted
                    api != null -> true
                    else -> false
                },
            )
        }
    }
}
