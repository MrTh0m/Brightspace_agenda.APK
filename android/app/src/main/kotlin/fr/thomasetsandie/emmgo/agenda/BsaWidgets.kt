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

// ── RemoteViews : texte d'attente quand aucune donnée disponible ─────
private fun showLoading(views: RemoteViews, message: String = "Ouvre l'app pour charger") {
    // Masquer toutes les lignes de données — seul le label reste visible
    // Chaque widget gère ça dans son propre update(), ce helper est juste indicatif
    Log.d(TAG, "showLoading: $message")
}

// ══════════════════════════════════════════════════════════════════════
// Widget 1 — Prochain événement (2×1)
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
                views.setTextViewText(R.id.widget_time_remaining, "—")
                views.setTextViewText(R.id.widget_type_label,    "")
                views.setTextViewText(R.id.widget_event_title,   "Ouvre l'app pour charger")
                views.setTextViewText(R.id.widget_event_meta,    "")
                views.setTextViewText(R.id.widget_event_detail,  "")
                views.setOnClickPendingIntent(R.id.widget_label,
                    openAppIntent(ctx, "bsa://tab/agenda"))
            } else {
                val inMin      = next.optInt("in_min", 0)
                val type       = next.optString("type", "")
                val typeLabel  = next.optString("type_label", if (type == "session") "Live Session" else "Atelier")
                val time       = next.optString("time", "")
                val timeEnd    = next.optString("time_end", "")
                val code       = next.optString("code", "")
                val instructor = next.optString("instructor", "")
                val tapUrl     = next.optString("tap_url", "bsa://tab/agenda")
                val metaStr    = if (timeEnd.isNotEmpty()) "$time – $timeEnd" else time
                val detailStr  = listOfNotNull(
                    code.takeIf { it.isNotEmpty() },
                    instructor.takeIf { it.isNotEmpty() }
                ).joinToString(" · ")

                views.setTextViewText(R.id.widget_time_remaining, formatInMin(inMin))
                views.setTextViewText(R.id.widget_type_label,   typeLabel)
                views.setTextViewText(R.id.widget_event_title,  next.optString("title", "—"))
                views.setTextViewText(R.id.widget_event_meta,   metaStr)
                views.setTextViewText(R.id.widget_event_detail, detailStr)
                views.setOnClickPendingIntent(R.id.widget_label,
                    openAppIntent(ctx, tapUrl))
            }

            mgr.updateAppWidget(id, views)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// Widget 2 — Agenda du jour (2×2)
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

            // Nombre de lignes adaptatif selon la hauteur du widget
            val opts    = mgr.getAppWidgetOptions(id)
            val minH    = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
            val maxRows = when {
                minH >= 280 -> 5
                minH >= 210 -> 4
                minH >= 150 -> 3
                else        -> 2
            }

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
                val tapUrl   = ev.optString("tap_url", "bsa://tab/agenda")
                val dotColor = if (type == "session") COLOR_BLUE else COLOR_GREEN
                val timeStr  = if (timeEnd.isNotEmpty()) "$time–$timeEnd" else time
                val detailParts = listOfNotNull(
                    ev.optString("type_label", "").takeIf { it.isNotEmpty() },
                    code.takeIf { it.isNotEmpty() },
                    instr.takeIf { it.isNotEmpty() }
                )

                views.setViewVisibility(ROW_IDS[i], View.VISIBLE)
                views.setInt(DOT_IDS[i], "setColorFilter", dotColor)
                views.setTextViewText(TIME_IDS[i], timeStr)
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
// Widget 3 — Devoirs à rendre (2×2)
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
        private val DATE_IDS  = listOf(R.id.widget_devoir_date_1, R.id.widget_devoir_date_2, R.id.widget_devoir_date_3, R.id.widget_devoir_date_4, R.id.widget_devoir_date_5)

        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val views   = RemoteViews(ctx.packageName, R.layout.widget_devoirs)
            val payload = readPayload(ctx)
            val devoirs = payload?.optJSONObject("devoirs")
            val list    = devoirs?.optJSONArray("list") ?: JSONArray()
            val pending = devoirs?.optInt("pending", 0) ?: 0
            val done    = devoirs?.optInt("done", 0) ?: 0

            // Nombre de lignes adaptatif selon la hauteur actuelle du widget
            // Référence : AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT (dp)
            val opts   = mgr.getAppWidgetOptions(id)
            val minH   = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
            val maxRows = when {
                minH >= 280 -> 5
                minH >= 210 -> 4
                minH >= 150 -> 3
                else        -> 2
            }

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

                views.setViewVisibility(ROW_IDS[i], View.VISIBLE)
                views.setTextViewText(TITLE_IDS[i], d.optString("title", "—"))
                views.setTextViewText(DATE_IDS[i], formatDays(days))
                // Afficher matière sous le titre
                val course = d.optString("course", "")
                val code   = d.optString("code", "")
                val courseStr = when {
                    course.isNotEmpty() -> course
                    code.isNotEmpty()   -> code
                    else                -> ""
                }
                // On réutilise DATE_IDS pour la date et on affiche la matière dans TITLE si on a de la place
                // Le layout actuel n'a pas de champ matière — on concatène sous le titre
                views.setTextViewText(TITLE_IDS[i], if (courseStr.isNotEmpty()) "${d.optString("title", "—")}\n$courseStr" else d.optString("title", "—"))
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
// Widget 4 — Progression (2×2)
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

            views.setTextViewText(R.id.widget_prog_percent,
                if (payload == null) "—" else "$percent%")
            views.setProgressBar(R.id.widget_prog_bar_global, 100, percent, false)
            views.setTextViewText(R.id.widget_prog_summary,
                if (payload == null) "Ouvre l'app pour charger"
                else "$done rendus sur $total devoirs")

            for (rowId in ROW_IDS) { views.setViewVisibility(rowId, View.GONE) }

            for (i in 0 until minOf(mats.length(), 4)) {
                val m     = mats.getJSONObject(i)
                val mDone = m.optInt("done", 0)
                val mTot  = m.optInt("total", 1).coerceAtLeast(1) // éviter division par 0
                val pct   = mDone * 100 / mTot

                views.setViewVisibility(ROW_IDS[i], View.VISIBLE)
                views.setTextViewText(NAME_IDS[i], m.optString("name", "—"))
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
// Les deux layouts ont les mêmes IDs — seul le titre et la source de données diffèrent
private fun updateWeekBlocks(
    ctx: Context,
    views: RemoteViews,
    days: JSONArray
) {
    val BLOCK_IDS  = listOf(R.id.widget_block_1, R.id.widget_block_2, R.id.widget_block_3, R.id.widget_block_4, R.id.widget_block_5)
    val HEADER_IDS = listOf(R.id.widget_day_header_1, R.id.widget_day_header_2, R.id.widget_day_header_3, R.id.widget_day_header_4, R.id.widget_day_header_5)
    val EV_IDS = listOf(
        listOf(R.id.widget_ev_1_1, R.id.widget_ev_1_2, R.id.widget_ev_1_3),
        listOf(R.id.widget_ev_2_1, R.id.widget_ev_2_2, R.id.widget_ev_2_3),
        listOf(R.id.widget_ev_3_1, R.id.widget_ev_3_2, R.id.widget_ev_3_3),
        listOf(R.id.widget_ev_4_1, R.id.widget_ev_4_2, R.id.widget_ev_4_3),
        listOf(R.id.widget_ev_5_1, R.id.widget_ev_5_2, R.id.widget_ev_5_3),
    )
    val DOT_IDS = listOf(
        listOf(R.id.widget_ev_dot_1_1, R.id.widget_ev_dot_1_2, R.id.widget_ev_dot_1_3),
        listOf(R.id.widget_ev_dot_2_1, R.id.widget_ev_dot_2_2, R.id.widget_ev_dot_2_3),
        listOf(R.id.widget_ev_dot_3_1, R.id.widget_ev_dot_3_2, R.id.widget_ev_dot_3_3),
        listOf(R.id.widget_ev_dot_4_1, R.id.widget_ev_dot_4_2, R.id.widget_ev_dot_4_3),
        listOf(R.id.widget_ev_dot_5_1, R.id.widget_ev_dot_5_2, R.id.widget_ev_dot_5_3),
    )
    val TIME_IDS = listOf(
        listOf(R.id.widget_ev_time_1_1, R.id.widget_ev_time_1_2, R.id.widget_ev_time_1_3),
        listOf(R.id.widget_ev_time_2_1, R.id.widget_ev_time_2_2, R.id.widget_ev_time_2_3),
        listOf(R.id.widget_ev_time_3_1, R.id.widget_ev_time_3_2, R.id.widget_ev_time_3_3),
        listOf(R.id.widget_ev_time_4_1, R.id.widget_ev_time_4_2, R.id.widget_ev_time_4_3),
        listOf(R.id.widget_ev_time_5_1, R.id.widget_ev_time_5_2, R.id.widget_ev_time_5_3),
    )
    val TITLE_IDS = listOf(
        listOf(R.id.widget_ev_title_1_1, R.id.widget_ev_title_1_2, R.id.widget_ev_title_1_3),
        listOf(R.id.widget_ev_title_2_1, R.id.widget_ev_title_2_2, R.id.widget_ev_title_2_3),
        listOf(R.id.widget_ev_title_3_1, R.id.widget_ev_title_3_2, R.id.widget_ev_title_3_3),
        listOf(R.id.widget_ev_title_4_1, R.id.widget_ev_title_4_2, R.id.widget_ev_title_4_3),
        listOf(R.id.widget_ev_title_5_1, R.id.widget_ev_title_5_2, R.id.widget_ev_title_5_3),
    )

    // Masquer tous les blocs par défaut
    for (blockId in BLOCK_IDS) { views.setViewVisibility(blockId, View.GONE) }
    views.setViewVisibility(R.id.widget_week_empty, View.GONE)

    // Filtrer les jours ayant des événements et prendre les 5 premiers
    val daysWithEvents: List<JSONObject> = (0 until days.length())
        .map { days.getJSONObject(it) }
        .filter { it.optInt("count", 0) > 0 }
        .take(5)

    if (daysWithEvents.isEmpty()) {
        views.setViewVisibility(R.id.widget_week_empty, View.VISIBLE)
        return
    }

    for (slot in daysWithEvents.indices) {
        val day     = daysWithEvents[slot]
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

        // Remplir les événements du jour
        for (j in 0 until minOf(events.length(), 3)) {
            val ev    = events.getJSONObject(j)
            val type  = ev.optString("type", "session")
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
            views.setTextViewText(TIME_IDS[slot][j], timeStr)
            views.setTextViewText(TITLE_IDS[slot][j], ev.optString("title", "—"))
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// Widget 5 — Semaine fixe lundi→dimanche (4×2)
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
            val views   = RemoteViews(ctx.packageName, R.layout.widget_week_fixed)
            val payload = readPayload(ctx)
            val days    = payload?.optJSONArray("week_fixed") ?: JSONArray()

            views.setTextViewText(R.id.widget_week_title, "Cette semaine")
            views.setTextViewText(R.id.widget_week_label,
                if (payload == null) "Ouvre l'app" else "")
            views.setOnClickPendingIntent(R.id.widget_week_label,
                openAppIntent(ctx, "bsa://tab/agenda"))
            updateWeekBlocks(ctx, views, days)
            mgr.updateAppWidget(id, views)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// Widget 6 — 7 jours glissants (4×2)
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
            val views   = RemoteViews(ctx.packageName, R.layout.widget_week_sliding)
            val payload = readPayload(ctx)
            val days    = payload?.optJSONArray("week_sliding") ?: JSONArray()

            views.setTextViewText(R.id.widget_week_title, "7 prochains jours")
            views.setTextViewText(R.id.widget_week_label,
                if (payload == null) "Ouvre l'app" else "")
            views.setOnClickPendingIntent(R.id.widget_week_label,
                openAppIntent(ctx, "bsa://tab/agenda"))
            updateWeekBlocks(ctx, views, days)
            mgr.updateAppWidget(id, views)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// Widget 7 — Charge par semaine (4×2)
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

            for (rowId in ROW_IDS) { views.setViewVisibility(rowId, View.GONE) }

            for (i in 0 until minOf(weeks.length(), 5)) {
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
