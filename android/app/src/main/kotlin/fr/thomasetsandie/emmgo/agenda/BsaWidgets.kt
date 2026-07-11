package fr.thomasetsandie.emmgo.agenda

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "BsaWidgets"

// ── Couleurs urgence ─────────────────────────────────────────────────
private val COLOR_RED    = Color.parseColor("#FF6B6B")
private val COLOR_ORANGE = Color.parseColor("#FFA500")
private val COLOR_YELLOW = Color.parseColor("#FFD700")
private val COLOR_BLUE   = Color.parseColor("#6C8EFF")
private val COLOR_GREEN  = Color.parseColor("#52C77A")
private val COLOR_WHITE  = Color.WHITE

// ── Nom du fichier SharedPreferences — DOIT matcher le "group" côté JS ──
// JS : bridge.setItem({ key: 'bsa_widget_data', group: 'bsa_widget_prefs', ... })
private const val PREFS_NAME = "bsa_widget_prefs"
private const val PREFS_KEY  = "bsa_widget_data"

// ── Lire le payload JSON depuis SharedPreferences ────────────────────
private fun readPayload(context: Context): JSONObject? {
    return try {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw   = prefs.getString(PREFS_KEY, null) ?: return null
        JSONObject(raw)
    } catch (e: Exception) {
        Log.e(TAG, "readPayload() failed: ${e.message}")
        null
    }
}

// ── PendingIntent pour ouvrir l'app sur un deep link bsa:// ─────────
private fun openAppIntent(context: Context, url: String): PendingIntent {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        setPackage(context.packageName)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    return PendingIntent.getActivity(
        context, url.hashCode(), intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

// ── Formatage ────────────────────────────────────────────────────────
private fun formatInMin(min: Int): String = when {
    min <= 0   -> "En cours"
    min < 60   -> "Dans ${min} min"
    min < 1440 -> "Dans ${min / 60}h${if (min % 60 > 0) "${min % 60}" else ""}"
    else       -> "Dans ${min / 1440}j"
}

private fun urgencyColor(days: Int): Int = when {
    days <= 1 -> COLOR_RED
    days <= 3 -> COLOR_ORANGE
    days <= 7 -> COLOR_YELLOW
    else      -> COLOR_WHITE
}

private fun formatDays(days: Int): String = when {
    days <= 0 -> "Aujourd'hui"
    days == 1 -> "Demain"
    else      -> "J-$days"
}

// Couleur d'urgence pour le prochain événement (bleu=session, vert=atelier,
// orange si aujourd'hui/demain, rouge si imminent <60min ou en cours) —
// même logique que renderNextSessionChip() côté web.
private fun nextEventColor(type: String, inMin: Int): Int {
    val days = inMin / 1440
    return when {
        inMin < 60        -> COLOR_RED
        days <= 1          -> COLOR_ORANGE
        type == "atelier"  -> COLOR_GREEN
        else               -> COLOR_BLUE
    }
}

private fun nextEventPillRes(type: String, inMin: Int): Int {
    val days = inMin / 1440
    return when {
        inMin < 60        -> R.drawable.widget_pill_red
        days <= 1          -> R.drawable.widget_pill_orange
        type == "atelier"  -> R.drawable.widget_pill_green
        else               -> R.drawable.widget_pill_blue
    }
}

// Remarque : les widgets tentent toujours d'afficher l'intégralité des
// données disponibles (bornée uniquement par le nombre de lignes/blocs prévus
// dans chaque mise en page), quelle que soit la taille actuelle du widget.
// Une précédente version limitait le nombre de lignes selon la hauteur
// (OPTION_APPWIDGET_MIN_HEIGHT) : le contenu changeait donc selon la taille,
// et certaines lignes valides (ex. jours de «Cette semaine», semaines de
// «Charge par semaine») restaient masquées même en agrandissant le widget.

// ══════════════════════════════════════════════════════════════════════
// Widget 1 — Prochain événement (3×1, redimensionnable)
// ══════════════════════════════════════════════════════════════════════
class NextEventWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            try { update(ctx, mgr, id) }
            catch (e: Exception) { Log.e(TAG, "NextEventWidget.update() failed: ${e.message}") }
        }
    }

    companion object {
        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val views   = RemoteViews(ctx.packageName, R.layout.widget_next_event)
            val payload = readPayload(ctx)
            val next    = payload?.optJSONObject("next_event")

            if (next == null) {
                views.setImageViewResource(R.id.widget_type_icon, R.drawable.ic_widget_video)
                views.setInt(R.id.widget_type_icon, "setColorFilter", COLOR_WHITE)
                views.setTextViewText(R.id.widget_type_label, "")
                views.setTextViewText(R.id.widget_time_remaining, "—")
                views.setInt(R.id.widget_time_remaining, "setBackgroundResource", R.drawable.widget_pill_blue)
                views.setTextViewText(R.id.widget_event_title, "Ouvre l'app pour charger")
                views.setTextViewText(R.id.widget_event_meta, "")
                views.setTextViewText(R.id.widget_event_detail, "")
                views.setViewVisibility(R.id.widget_join_btn, View.GONE)
                views.setOnClickPendingIntent(R.id.widget_next_root,
                    openAppIntent(ctx, "bsa://tab/agenda"))
            } else {
                val inMin      = next.optInt("in_min", 0)
                val type       = next.optString("type", "session")
                val isAtelier  = type == "atelier"
                val time       = next.optString("time", "")
                val timeEnd    = next.optString("time_end", "")
                val code       = next.optString("code", "")
                val instructor = next.optString("instructor", "")
                val tapUrl     = next.optString("tap_url", "bsa://tab/agenda")
                val joinUrl    = next.optString("join_url", "")
                val metaStr    = if (timeEnd.isNotEmpty()) "$time – $timeEnd" else time
                val detailStr  = listOfNotNull(
                    code.takeIf { it.isNotEmpty() },
                    instructor.takeIf { it.isNotEmpty() }
                ).joinToString(" · ")

                val tint    = nextEventColor(type, inMin)
                val pillRes = nextEventPillRes(type, inMin)

                views.setImageViewResource(R.id.widget_type_icon,
                    if (isAtelier) R.drawable.ic_widget_group else R.drawable.ic_widget_video)
                views.setInt(R.id.widget_type_icon, "setColorFilter", tint)
                views.setTextViewText(R.id.widget_type_label,
                    if (isAtelier) "Prochain atelier" else "Prochaine live session")
                views.setTextViewText(R.id.widget_time_remaining, formatInMin(inMin))
                views.setInt(R.id.widget_time_remaining, "setBackgroundResource", pillRes)
                views.setTextViewText(R.id.widget_event_title, next.optString("title", "—"))
                views.setTextViewText(R.id.widget_event_meta, metaStr)
                views.setTextViewText(R.id.widget_event_detail, detailStr)
                views.setOnClickPendingIntent(R.id.widget_next_root, openAppIntent(ctx, tapUrl))

                if (joinUrl.isNotEmpty()) {
                    views.setViewVisibility(R.id.widget_join_btn, View.VISIBLE)
                    views.setInt(R.id.widget_join_btn, "setBackgroundResource", pillRes)
                    views.setOnClickPendingIntent(R.id.widget_join_btn, openAppIntent(ctx, joinUrl))
                } else {
                    views.setViewVisibility(R.id.widget_join_btn, View.GONE)
                }
            }

            mgr.updateAppWidget(id, views)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// Widget 2 — Agenda du jour (3×1, redimensionnable)
// ══════════════════════════════════════════════════════════════════════
class AgendaWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            try { update(ctx, mgr, id) }
            catch (e: Exception) { Log.e(TAG, "AgendaWidget.update() failed: ${e.message}") }
        }
    }

    companion object {
        private val ROW_IDS   = listOf(R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3, R.id.widget_row_4, R.id.widget_row_5)
        private val DOT_IDS   = listOf(R.id.widget_row_1_dot, R.id.widget_row_2_dot, R.id.widget_row_3_dot, R.id.widget_row_4_dot, R.id.widget_row_5_dot)
        private val TIME_IDS  = listOf(R.id.widget_row_1_time, R.id.widget_row_2_time, R.id.widget_row_3_time, R.id.widget_row_4_time, R.id.widget_row_5_time)
        private val TITLE_IDS = listOf(R.id.widget_row_1_title, R.id.widget_row_2_title, R.id.widget_row_3_title, R.id.widget_row_4_title, R.id.widget_row_5_title)
        private val DETAIL_IDS= listOf(R.id.widget_row_1_detail, R.id.widget_row_2_detail, R.id.widget_row_3_detail, R.id.widget_row_4_detail, R.id.widget_row_5_detail)

        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val views   = RemoteViews(ctx.packageName, R.layout.widget_agenda)
            val payload = readPayload(ctx)
            val today   = payload?.optJSONArray("today") ?: JSONArray()
            val count   = today.length()
            val maxRows = ROW_IDS.size

            val sdf = SimpleDateFormat("EEEE d MMM", Locale.FRENCH)
            views.setTextViewText(R.id.widget_date,
                sdf.format(Date()).replaceFirstChar { it.uppercase() })
            views.setTextViewText(R.id.widget_event_count,
                when {
                    payload == null -> "Ouvre l'app"
                    count == 0     -> ""
                    else           -> "$count événement${if (count > 1) "s" else ""}"
                })

            for (rowId in ROW_IDS) { views.setViewVisibility(rowId, View.GONE) }
            views.setViewVisibility(R.id.widget_empty, if (payload != null && count == 0) View.VISIBLE else View.GONE)

            for (i in 0 until minOf(count, maxRows)) {
                val ev       = today.getJSONObject(i)
                val type     = ev.optString("type", "session")
                val time     = ev.optString("time", "")
                val timeEnd  = ev.optString("time_end", "")
                val code     = ev.optString("code", "")
                val instr    = ev.optString("instructor", "")
                val isNow    = ev.optBoolean("is_now", false)
                val tapUrl   = ev.optString("tap_url", "bsa://tab/agenda")
                val dotColor = if (type == "session") COLOR_BLUE else COLOR_GREEN
                val timeStr  = if (timeEnd.isNotEmpty()) "$time–$timeEnd" else time
                val detailParts = listOfNotNull(
                    ev.optString("type_label", "").takeIf { it.isNotEmpty() },
                    code.takeIf { it.isNotEmpty() },
                    instr.takeIf { it.isNotEmpty() }
                )

                views.setViewVisibility(ROW_IDS[i], View.VISIBLE)
                // Un seul indicateur "en cours" : le point coloré passe au rouge —
                // pas de puce texte en plus, pour éviter le doublon visuel.
                views.setInt(DOT_IDS[i], "setColorFilter", if (isNow) COLOR_RED else dotColor)
                views.setTextViewText(TIME_IDS[i], timeStr)
                if (isNow) views.setTextColor(TIME_IDS[i], COLOR_RED)
                views.setTextViewText(TITLE_IDS[i], ev.optString("title", "—"))
                views.setTextViewText(DETAIL_IDS[i], detailParts.joinToString(" · "))
                views.setOnClickPendingIntent(ROW_IDS[i], openAppIntent(ctx, tapUrl))
            }

            views.setOnClickPendingIntent(R.id.widget_date, openAppIntent(ctx, "bsa://tab/agenda"))
            mgr.updateAppWidget(id, views)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// Widget 3 — Devoirs à rendre (3×1, redimensionnable)
// ══════════════════════════════════════════════════════════════════════
class DevoirsWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            try { update(ctx, mgr, id) }
            catch (e: Exception) { Log.e(TAG, "DevoirsWidget.update() failed: ${e.message}") }
        }
    }

    companion object {
        private val ROW_IDS   = listOf(R.id.widget_devoir_row_1, R.id.widget_devoir_row_2, R.id.widget_devoir_row_3, R.id.widget_devoir_row_4, R.id.widget_devoir_row_5)
        private val DOT_IDS   = listOf(R.id.widget_devoir_dot_1, R.id.widget_devoir_dot_2, R.id.widget_devoir_dot_3, R.id.widget_devoir_dot_4, R.id.widget_devoir_dot_5)
        private val TITLE_IDS = listOf(R.id.widget_devoir_title_1, R.id.widget_devoir_title_2, R.id.widget_devoir_title_3, R.id.widget_devoir_title_4, R.id.widget_devoir_title_5)
        private val CODE_IDS  = listOf(R.id.widget_devoir_code_1, R.id.widget_devoir_code_2, R.id.widget_devoir_code_3, R.id.widget_devoir_code_4, R.id.widget_devoir_code_5)
        private val DATE_IDS  = listOf(R.id.widget_devoir_date_1, R.id.widget_devoir_date_2, R.id.widget_devoir_date_3, R.id.widget_devoir_date_4, R.id.widget_devoir_date_5)

        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val views   = RemoteViews(ctx.packageName, R.layout.widget_devoirs)
            val payload = readPayload(ctx)
            val devoirs = payload?.optJSONObject("devoirs")
            val list    = devoirs?.optJSONArray("list") ?: JSONArray()
            val pending = devoirs?.optInt("pending", 0) ?: 0
            val done    = devoirs?.optInt("done", 0) ?: 0
            val maxRows = ROW_IDS.size

            views.setTextViewText(R.id.widget_devoirs_pending,
                if (payload == null) "—" else "$pending à rendre")
            views.setTextViewText(R.id.widget_devoirs_done,
                if (payload == null) "Ouvre l'app" else "$done rendus")

            for (rowId in ROW_IDS) { views.setViewVisibility(rowId, View.GONE) }

            val showEmpty = payload != null && pending == 0
            views.setViewVisibility(R.id.widget_devoirs_empty,
                if (showEmpty) View.VISIBLE else View.GONE)

            for (i in 0 until minOf(list.length(), maxRows)) {
                val d      = list.getJSONObject(i)
                val days   = d.optInt("in_days", 99)
                val tapUrl = d.optString("tap_url", "bsa://tab/devoirs")
                val course = d.optString("course", "")
                val code   = d.optString("code", "")
                val courseStr = when {
                    code.isNotEmpty() && course.isNotEmpty() -> "$code · $course"
                    code.isNotEmpty()   -> code
                    course.isNotEmpty() -> course
                    else                -> ""
                }

                views.setViewVisibility(ROW_IDS[i], View.VISIBLE)
                views.setTextViewText(TITLE_IDS[i], d.optString("title", "—"))
                views.setViewVisibility(CODE_IDS[i], if (courseStr.isNotEmpty()) View.VISIBLE else View.GONE)
                views.setTextViewText(CODE_IDS[i], courseStr)
                views.setTextViewText(DATE_IDS[i], formatDays(days))
                views.setInt(DOT_IDS[i], "setColorFilter", urgencyColor(days))
                views.setTextColor(DATE_IDS[i], urgencyColor(days))
                views.setOnClickPendingIntent(ROW_IDS[i],
                    openAppIntent(ctx, tapUrl))
            }

            views.setOnClickPendingIntent(R.id.widget_devoirs_pending,
                openAppIntent(ctx, "bsa://tab/devoirs"))

            mgr.updateAppWidget(id, views)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// Widget 4 — Progression (3×1, redimensionnable)
// ══════════════════════════════════════════════════════════════════════
class ProgressionWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            try { update(ctx, mgr, id) }
            catch (e: Exception) { Log.e(TAG, "ProgressionWidget.update() failed: ${e.message}") }
        }
    }

    companion object {
        private val ROW_IDS   = listOf(R.id.widget_mat_row_1, R.id.widget_mat_row_2, R.id.widget_mat_row_3, R.id.widget_mat_row_4)
        private val NAME_IDS  = listOf(R.id.widget_mat_name_1, R.id.widget_mat_name_2, R.id.widget_mat_name_3, R.id.widget_mat_name_4)
        private val SCORE_IDS = listOf(R.id.widget_mat_score_1, R.id.widget_mat_score_2, R.id.widget_mat_score_3, R.id.widget_mat_score_4)
        private val BAR_IDS   = listOf(R.id.widget_mat_bar_1, R.id.widget_mat_bar_2, R.id.widget_mat_bar_3, R.id.widget_mat_bar_4)

        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val views   = RemoteViews(ctx.packageName, R.layout.widget_progression)
            val payload = readPayload(ctx)
            val prog    = payload?.optJSONObject("progression")
            val mats    = prog?.optJSONArray("matieres") ?: JSONArray()
            val percent = prog?.optInt("percent", 0) ?: 0
            val done    = prog?.optInt("done", 0) ?: 0
            val total   = prog?.optInt("total", 0) ?: 0
            val maxRows = ROW_IDS.size

            views.setTextViewText(R.id.widget_prog_percent,
                if (payload == null) "—" else "$percent%")
            views.setProgressBar(R.id.widget_prog_bar_global, 100, percent, false)
            views.setTextViewText(R.id.widget_prog_summary,
                if (payload == null) "Ouvre l'app pour charger"
                else "$done rendus sur $total devoirs")

            for (rowId in ROW_IDS) { views.setViewVisibility(rowId, View.GONE) }

            for (i in 0 until minOf(mats.length(), maxRows)) {
                val m     = mats.getJSONObject(i)
                val mDone = m.optInt("done", 0)
                val mTot  = m.optInt("total", 1).coerceAtLeast(1) // éviter division par 0
                val pct   = mDone * 100 / mTot
                val code  = m.optString("code", "")
                val name  = m.optString("name", "—")
                val displayName = if (code.isNotEmpty()) "$code · $name" else name

                views.setViewVisibility(ROW_IDS[i], View.VISIBLE)
                views.setTextViewText(NAME_IDS[i], displayName)
                views.setTextViewText(SCORE_IDS[i], "$mDone/$mTot")
                views.setProgressBar(BAR_IDS[i], 100, pct, false)
            }

            views.setOnClickPendingIntent(R.id.widget_prog_percent,
                openAppIntent(ctx, "bsa://tab/prog"))

            mgr.updateAppWidget(id, views)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// Receiver pour mise à jour depuis l'app (broadcast interne)
// ══════════════════════════════════════════════════════════════════════
// ── Fonction partagée pour remplir les blocs semaine (fixed et sliding) ──────
// Les deux layouts ont les mêmes IDs — seul le titre et la source de données diffèrent.
// Couvre toujours les 7 jours (semaine complète lundi→dimanche ou 7 jours glissants),
// `maxSlots` valant 7 dans les deux appelants (borné par BLOCK_IDS.size par sécurité).
// Les jours sans événement sont quand même affichés (placeholder "Aucun cours")
// pour que le widget représente toujours la semaine entière, pas seulement les
// jours actifs.
private fun updateWeekBlocks(
    ctx: Context,
    views: RemoteViews,
    days: JSONArray,
    maxSlots: Int
) {
    val BLOCK_IDS  = listOf(R.id.widget_block_1, R.id.widget_block_2, R.id.widget_block_3, R.id.widget_block_4, R.id.widget_block_5, R.id.widget_block_6, R.id.widget_block_7)
    val HEADER_IDS = listOf(R.id.widget_day_header_1, R.id.widget_day_header_2, R.id.widget_day_header_3, R.id.widget_day_header_4, R.id.widget_day_header_5, R.id.widget_day_header_6, R.id.widget_day_header_7)
    val EV_IDS = listOf(
        listOf(R.id.widget_ev_1_1, R.id.widget_ev_1_2, R.id.widget_ev_1_3),
        listOf(R.id.widget_ev_2_1, R.id.widget_ev_2_2, R.id.widget_ev_2_3),
        listOf(R.id.widget_ev_3_1, R.id.widget_ev_3_2, R.id.widget_ev_3_3),
        listOf(R.id.widget_ev_4_1, R.id.widget_ev_4_2, R.id.widget_ev_4_3),
        listOf(R.id.widget_ev_5_1, R.id.widget_ev_5_2, R.id.widget_ev_5_3),
        listOf(R.id.widget_ev_6_1, R.id.widget_ev_6_2, R.id.widget_ev_6_3),
        listOf(R.id.widget_ev_7_1, R.id.widget_ev_7_2, R.id.widget_ev_7_3),
    )
    val DOT_IDS = listOf(
        listOf(R.id.widget_ev_dot_1_1, R.id.widget_ev_dot_1_2, R.id.widget_ev_dot_1_3),
        listOf(R.id.widget_ev_dot_2_1, R.id.widget_ev_dot_2_2, R.id.widget_ev_dot_2_3),
        listOf(R.id.widget_ev_dot_3_1, R.id.widget_ev_dot_3_2, R.id.widget_ev_dot_3_3),
        listOf(R.id.widget_ev_dot_4_1, R.id.widget_ev_dot_4_2, R.id.widget_ev_dot_4_3),
        listOf(R.id.widget_ev_dot_5_1, R.id.widget_ev_dot_5_2, R.id.widget_ev_dot_5_3),
        listOf(R.id.widget_ev_dot_6_1, R.id.widget_ev_dot_6_2, R.id.widget_ev_dot_6_3),
        listOf(R.id.widget_ev_dot_7_1, R.id.widget_ev_dot_7_2, R.id.widget_ev_dot_7_3),
    )
    val TIME_IDS = listOf(
        listOf(R.id.widget_ev_time_1_1, R.id.widget_ev_time_1_2, R.id.widget_ev_time_1_3),
        listOf(R.id.widget_ev_time_2_1, R.id.widget_ev_time_2_2, R.id.widget_ev_time_2_3),
        listOf(R.id.widget_ev_time_3_1, R.id.widget_ev_time_3_2, R.id.widget_ev_time_3_3),
        listOf(R.id.widget_ev_time_4_1, R.id.widget_ev_time_4_2, R.id.widget_ev_time_4_3),
        listOf(R.id.widget_ev_time_5_1, R.id.widget_ev_time_5_2, R.id.widget_ev_time_5_3),
        listOf(R.id.widget_ev_time_6_1, R.id.widget_ev_time_6_2, R.id.widget_ev_time_6_3),
        listOf(R.id.widget_ev_time_7_1, R.id.widget_ev_time_7_2, R.id.widget_ev_time_7_3),
    )
    val TITLE_IDS = listOf(
        listOf(R.id.widget_ev_title_1_1, R.id.widget_ev_title_1_2, R.id.widget_ev_title_1_3),
        listOf(R.id.widget_ev_title_2_1, R.id.widget_ev_title_2_2, R.id.widget_ev_title_2_3),
        listOf(R.id.widget_ev_title_3_1, R.id.widget_ev_title_3_2, R.id.widget_ev_title_3_3),
        listOf(R.id.widget_ev_title_4_1, R.id.widget_ev_title_4_2, R.id.widget_ev_title_4_3),
        listOf(R.id.widget_ev_title_5_1, R.id.widget_ev_title_5_2, R.id.widget_ev_title_5_3),
        listOf(R.id.widget_ev_title_6_1, R.id.widget_ev_title_6_2, R.id.widget_ev_title_6_3),
        listOf(R.id.widget_ev_title_7_1, R.id.widget_ev_title_7_2, R.id.widget_ev_title_7_3),
    )

    // Masquer tous les blocs par défaut
    for (blockId in BLOCK_IDS) { views.setViewVisibility(blockId, View.GONE) }
    views.setViewVisibility(R.id.widget_week_empty, View.GONE)

    if (days.length() == 0) {
        views.setViewVisibility(R.id.widget_week_empty, View.VISIBLE)
        return
    }

    // On affiche toujours les jours dans l'ordre (y compris ceux sans événement,
    // avec un placeholder) afin que le widget représente la semaine entière et
    // pas seulement les jours actifs.
    val slotCount = minOf(days.length(), maxSlots, BLOCK_IDS.size)

    for (slot in 0 until slotCount) {
        val day     = days.getJSONObject(slot)
        val isToday = day.optBoolean("is_today", false)
        val label   = day.optString("label", "")
        val date    = day.optString("date", "")
        val events  = day.optJSONArray("events") ?: JSONArray()
        val header  = if (isToday) "▶ $label $date" else "$label $date"

        views.setViewVisibility(BLOCK_IDS[slot], View.VISIBLE)
        views.setTextViewText(HEADER_IDS[slot], header)
        views.setTextColor(HEADER_IDS[slot],
            if (isToday) Color.parseColor("#FFD700") else Color.parseColor("#AAAACC"))
        views.setOnClickPendingIntent(BLOCK_IDS[slot],
            openAppIntent(ctx, "bsa://tab/agenda"))

        // Masquer toutes les lignes événements du slot
        for (ev_id in EV_IDS[slot]) { views.setViewVisibility(ev_id, View.GONE) }

        if (events.length() == 0) {
            // Jour sans événement : placeholder discret sur la première ligne
            views.setViewVisibility(EV_IDS[slot][0], View.VISIBLE)
            views.setInt(DOT_IDS[slot][0], "setColorFilter", Color.parseColor("#44FFFFFF"))
            views.setTextViewText(TIME_IDS[slot][0], "")
            views.setTextViewText(TITLE_IDS[slot][0], "Aucun cours")
        } else {
            for (j in 0 until minOf(events.length(), 3)) {
                val ev    = events.getJSONObject(j)
                val type  = ev.optString("type", "session")
                val code  = ev.optString("code", "")
                val color = when (type) {
                    "session" -> COLOR_BLUE
                    "atelier" -> COLOR_GREEN
                    "devoir"  -> Color.parseColor("#A78BFA")
                    else      -> Color.WHITE
                }
                views.setViewVisibility(EV_IDS[slot][j], View.VISIBLE)
                views.setInt(DOT_IDS[slot][j], "setColorFilter", color)
                val evTime    = ev.optString("time", "")
                val evTimeEnd = ev.optString("time_end", "")
                val timeStr   = if (evTimeEnd.isNotEmpty()) "$evTime–$evTimeEnd" else evTime
                val title     = ev.optString("title", "—")
                val titleStr  = if (code.isNotEmpty()) "$code · $title" else title
                views.setTextViewText(TIME_IDS[slot][j], timeStr)
                views.setTextViewText(TITLE_IDS[slot][j], titleStr)
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// Widget 5 — Semaine fixe lundi→dimanche (3×1, redimensionnable jusqu'à 7 jours)
// ══════════════════════════════════════════════════════════════════════
class WeekFixedWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            try { update(ctx, mgr, id) }
            catch (e: Exception) { Log.e(TAG, "WeekFixedWidget.update() failed: ${e.message}") }
        }
    }

    companion object {
        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val views    = RemoteViews(ctx.packageName, R.layout.widget_week_fixed)
            val payload  = readPayload(ctx)
            val days     = payload?.optJSONArray("week_fixed") ?: JSONArray()
            val maxSlots = 7

            views.setTextViewText(R.id.widget_week_title, "Cette semaine")
            views.setTextViewText(R.id.widget_week_label,
                if (payload == null) "Ouvre l'app" else "")
            views.setOnClickPendingIntent(R.id.widget_week_label,
                openAppIntent(ctx, "bsa://tab/agenda"))
            updateWeekBlocks(ctx, views, days, maxSlots)
            mgr.updateAppWidget(id, views)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// Widget 6 — 7 jours glissants (3×1, redimensionnable jusqu'à 7 jours)
// ══════════════════════════════════════════════════════════════════════
class WeekSlidingWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            try { update(ctx, mgr, id) }
            catch (e: Exception) { Log.e(TAG, "WeekSlidingWidget.update() failed: ${e.message}") }
        }
    }

    companion object {
        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val views    = RemoteViews(ctx.packageName, R.layout.widget_week_sliding)
            val payload  = readPayload(ctx)
            val days     = payload?.optJSONArray("week_sliding") ?: JSONArray()
            val maxSlots = 7

            views.setTextViewText(R.id.widget_week_title, "7 prochains jours")
            views.setTextViewText(R.id.widget_week_label,
                if (payload == null) "Ouvre l'app" else "")
            views.setOnClickPendingIntent(R.id.widget_week_label,
                openAppIntent(ctx, "bsa://tab/agenda"))
            updateWeekBlocks(ctx, views, days, maxSlots)
            mgr.updateAppWidget(id, views)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// Widget 7 — Charge par semaine (3×1, redimensionnable)
// ══════════════════════════════════════════════════════════════════════
class ChargeWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            try { update(ctx, mgr, id) }
            catch (e: Exception) { Log.e(TAG, "ChargeWidget.update() failed: ${e.message}") }
        }
    }

    companion object {
        private val ROW_IDS   = listOf(R.id.widget_charge_row_1, R.id.widget_charge_row_2, R.id.widget_charge_row_3, R.id.widget_charge_row_4, R.id.widget_charge_row_5)
        private val WEEK_IDS  = listOf(R.id.widget_charge_week_1, R.id.widget_charge_week_2, R.id.widget_charge_week_3, R.id.widget_charge_week_4, R.id.widget_charge_week_5)
        private val BAR_IDS   = listOf(R.id.widget_charge_bar_1, R.id.widget_charge_bar_2, R.id.widget_charge_bar_3, R.id.widget_charge_bar_4, R.id.widget_charge_bar_5)
        private val COUNT_IDS = listOf(R.id.widget_charge_count_1, R.id.widget_charge_count_2, R.id.widget_charge_count_3, R.id.widget_charge_count_4, R.id.widget_charge_count_5)

        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val views   = RemoteViews(ctx.packageName, R.layout.widget_charge)
            val payload = readPayload(ctx)
            val weeks   = payload?.optJSONArray("charge_weeks") ?: JSONArray()
            val maxRows = ROW_IDS.size

            for (rowId in ROW_IDS) { views.setViewVisibility(rowId, View.GONE) }

            for (i in 0 until minOf(weeks.length(), maxRows)) {
                val w       = weeks.getJSONObject(i)
                val isCurr  = w.optBoolean("is_current", false)
                val total   = w.optInt("total", 0)
                val done    = w.optInt("done", 0)
                val pending = w.optInt("pending", 0)
                val pctDone = w.optInt("pct_done", 0)
                val pctTot  = w.optInt("pct_total", 0)
                val weekLabel = if (isCurr) "-> ${w.optString("week")}" else w.optString("week", "")

                views.setViewVisibility(ROW_IDS[i], View.VISIBLE)
                views.setTextViewText(WEEK_IDS[i], weekLabel)
                views.setTextColor(WEEK_IDS[i], if (isCurr) Color.parseColor("#FFD700") else Color.parseColor("#99FFFFFF"))
                // Barre unique : secondaryProgress = total (rouge fond), progress = rendus (vert)
                views.setProgressBar(BAR_IDS[i], 100, pctTot * pctDone / 100, false)
                views.setInt(BAR_IDS[i], "setSecondaryProgress", pctTot)
                views.setTextViewText(COUNT_IDS[i], "$total")
                views.setTextColor(COUNT_IDS[i], when {
                    total >= 3 -> Color.parseColor("#FF6B6B")
                    total >= 2 -> Color.parseColor("#FFA500")
                    else       -> Color.WHITE
                })
                views.setOnClickPendingIntent(ROW_IDS[i], openAppIntent(ctx, "bsa://tab/devoirs"))
            }

            views.setOnClickPendingIntent(R.id.widget_charge_row_1,
                openAppIntent(ctx, "bsa://tab/agenda"))

            mgr.updateAppWidget(id, views)
        }
    }
}

class WidgetUpdateReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val mgr = AppWidgetManager.getInstance(context)
        try {
            fun updateAll(cls: Class<*>) {
                val provider = android.content.ComponentName(context, cls)
                val ids = mgr.getAppWidgetIds(provider)
                for (id in ids) {
                    when (cls) {
                        NextEventWidget::class.java    -> NextEventWidget.update(context, mgr, id)
                        AgendaWidget::class.java       -> AgendaWidget.update(context, mgr, id)
                        DevoirsWidget::class.java      -> DevoirsWidget.update(context, mgr, id)
                        ProgressionWidget::class.java  -> ProgressionWidget.update(context, mgr, id)
                        WeekFixedWidget::class.java    -> WeekFixedWidget.update(context, mgr, id)
                        WeekSlidingWidget::class.java  -> WeekSlidingWidget.update(context, mgr, id)
                        ChargeWidget::class.java       -> ChargeWidget.update(context, mgr, id)
                    }
                }
            }
            updateAll(NextEventWidget::class.java)
            updateAll(AgendaWidget::class.java)
            updateAll(DevoirsWidget::class.java)
            updateAll(ProgressionWidget::class.java)
            updateAll(WeekFixedWidget::class.java)
            updateAll(WeekSlidingWidget::class.java)
            updateAll(ChargeWidget::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "WidgetUpdateReceiver.onReceive() failed: ${e.message}")
        }
    }
}
