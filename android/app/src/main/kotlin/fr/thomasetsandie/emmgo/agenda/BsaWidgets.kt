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

            // Tap global → onglet agenda
            val globalIntent = openAppIntent(ctx, "bsa://tab/agenda")
            views.setOnClickPendingIntent(R.id.widget_label, globalIntent)

            if (next == null) {
                views.setTextViewText(R.id.widget_time_remaining, "—")
                views.setTextViewText(R.id.widget_event_title,
                    if (payload == null) "Ouvre l'app pour charger" else "Aucun événement à venir")
                views.setTextViewText(R.id.widget_event_meta, "")
            } else {
                val inMin    = next.optInt("in_min", 0)
                val type     = next.optString("type", "")
                val tapUrl   = next.optString("tap_url", "bsa://tab/agenda")
                val typeLabel = if (type == "session") "Live session" else "Atelier"

                views.setTextViewText(R.id.widget_time_remaining, formatInMin(inMin))
                views.setTextViewText(R.id.widget_event_title, next.optString("title", "—"))
                views.setTextViewText(R.id.widget_event_meta,
                    "$typeLabel · ${next.optString("time")}")
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
        private val TIME_IDS  = listOf(R.id.widget_row_1_time, R.id.widget_row_2_time, R.id.widget_row_3_time, R.id.widget_row_4_time, R.id.widget_row_5_time)
        private val TITLE_IDS = listOf(R.id.widget_row_1_title, R.id.widget_row_2_title, R.id.widget_row_3_title, R.id.widget_row_4_title, R.id.widget_row_5_title)
        private val DOT_IDS   = listOf(R.id.widget_row_1_dot, R.id.widget_row_2_dot, R.id.widget_row_3_dot, R.id.widget_row_4_dot, R.id.widget_row_5_dot)

        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val views   = RemoteViews(ctx.packageName, R.layout.widget_agenda)
            val payload = readPayload(ctx)
            val today   = payload?.optJSONArray("today") ?: JSONArray()
            val count   = today.length()

            // Date du jour
            val sdf = SimpleDateFormat("EEEE d MMM", Locale.FRENCH)
            views.setTextViewText(R.id.widget_date,
                sdf.format(Date()).replaceFirstChar { it.uppercase() })
            views.setTextViewText(R.id.widget_event_count,
                when {
                    payload == null -> "Ouvre l'app"
                    count == 0 -> ""
                    else -> "$count événement${if (count > 1) "s" else ""}"
                })

            // Masquer toutes les lignes + vide par défaut
            ROW_IDS.forEach { views.setViewVisibility(it, View.GONE) }

            val showEmpty = payload != null && count == 0
            views.setViewVisibility(R.id.widget_empty,
                if (showEmpty) View.VISIBLE else View.GONE)

            // Peupler les lignes
            for (i in 0 until minOf(count, 5)) {
                val ev     = today.getJSONObject(i)
                val type   = ev.optString("type", "session")
                val tapUrl = ev.optString("tap_url", "bsa://tab/agenda")
                val dotColor = if (type == "session") COLOR_BLUE else COLOR_GREEN

                views.setViewVisibility(ROW_IDS[i], View.VISIBLE)
                views.setTextViewText(TIME_IDS[i], ev.optString("time", ""))
                views.setTextViewText(TITLE_IDS[i], ev.optString("title", "—"))
                views.setInt(DOT_IDS[i], "setColorFilter", dotColor)
                views.setOnClickPendingIntent(ROW_IDS[i],
                    openAppIntent(ctx, tapUrl))
            }

            views.setOnClickPendingIntent(R.id.widget_date,
                openAppIntent(ctx, "bsa://tab/agenda"))

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

            ROW_IDS.forEach { views.setViewVisibility(it, View.GONE) }

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

            ROW_IDS.forEach { views.setViewVisibility(it, View.GONE) }

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
// ══════════════════════════════════════════════════════════════════════
// Widget 5 — Semaine fixe lundi→dimanche (4×2)
// ══════════════════════════════════════════════════════════════════════
class WeekFixedWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            try { update(ctx, mgr, id) }
            catch (e: Exception) { Log.e(TAG, "WeekFixedWidget.update() failed: ${e.message}") }
        }
    }

    companion object {
        private val COL_IDS   = listOf(R.id.widget_day_col_1, R.id.widget_day_col_2, R.id.widget_day_col_3,
                                       R.id.widget_day_col_4, R.id.widget_day_col_5, R.id.widget_day_col_6,
                                       R.id.widget_day_col_7)
        private val LABEL_IDS = listOf(R.id.widget_day_label_1, R.id.widget_day_label_2, R.id.widget_day_label_3,
                                       R.id.widget_day_label_4, R.id.widget_day_label_5, R.id.widget_day_label_6,
                                       R.id.widget_day_label_7)
        private val DATE_IDS  = listOf(R.id.widget_day_date_1, R.id.widget_day_date_2, R.id.widget_day_date_3,
                                       R.id.widget_day_date_4, R.id.widget_day_date_5, R.id.widget_day_date_6,
                                       R.id.widget_day_date_7)
        private val COUNT_IDS = listOf(R.id.widget_day_count_1, R.id.widget_day_count_2, R.id.widget_day_count_3,
                                       R.id.widget_day_count_4, R.id.widget_day_count_5, R.id.widget_day_count_6,
                                       R.id.widget_day_count_7)
        private val SUB_IDS   = listOf(R.id.widget_day_sub_1, R.id.widget_day_sub_2, R.id.widget_day_sub_3,
                                       R.id.widget_day_sub_4, R.id.widget_day_sub_5, R.id.widget_day_sub_6,
                                       R.id.widget_day_sub_7)

        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val views   = RemoteViews(ctx.packageName, R.layout.widget_week_fixed)
            val payload = readPayload(ctx)
            val days    = payload?.optJSONArray("week_fixed") ?: JSONArray()

            views.setTextViewText(R.id.widget_week_label,
                if (payload == null) "Ouvre l'app" else "")
            views.setOnClickPendingIntent(R.id.widget_week_label,
                openAppIntent(ctx, "bsa://tab/agenda"))

            for (i in 0 until 7) {
                val day = if (i < days.length()) days.getJSONObject(i) else null
                val isToday = day?.optBoolean("is_today", false) ?: false
                val count   = day?.optInt("count", 0) ?: 0
                val textColor = if (isToday) Color.parseColor("#FFD700") else Color.WHITE
                val countColor = when {
                    count == 0 -> Color.parseColor("#44FFFFFF")
                    count >= 3 -> Color.parseColor("#FF6B6B")
                    else       -> Color.parseColor("#6C8EFF")
                }

                views.setTextViewText(LABEL_IDS[i], day?.optString("label", "-") ?: "-")
                views.setTextColor(LABEL_IDS[i], if (isToday) Color.parseColor("#FFD700") else Color.parseColor("#99FFFFFF"))
                views.setTextViewText(DATE_IDS[i], day?.optString("date", "") ?: "")
                views.setTextColor(DATE_IDS[i], textColor)
                views.setTextViewText(COUNT_IDS[i], if (count > 0) "$count" else "·")
                views.setTextColor(COUNT_IDS[i], countColor)

                // Afficher les titres des 2 premiers événements du jour
                val events = day?.optJSONArray("events")
                val subText = if (events != null && events.length() > 0) {
                    (0 until minOf(events.length(), 2)).joinToString("\n") { j ->
                        events.getJSONObject(j).optString("title", "").take(8)
                    }
                } else ""
                views.setTextViewText(SUB_IDS[i], subText)
                views.setOnClickPendingIntent(COL_IDS[i], openAppIntent(ctx, "bsa://tab/agenda"))
            }

            mgr.updateAppWidget(id, views)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// Widget 6 — 7 jours glissants (4×2)
// ══════════════════════════════════════════════════════════════════════
class WeekSlidingWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            try { update(ctx, mgr, id) }
            catch (e: Exception) { Log.e(TAG, "WeekSlidingWidget.update() failed: ${e.message}") }
        }
    }

    companion object {
        private val COL_IDS   = listOf(R.id.widget_sl_col_1, R.id.widget_sl_col_2, R.id.widget_sl_col_3,
                                       R.id.widget_sl_col_4, R.id.widget_sl_col_5, R.id.widget_sl_col_6,
                                       R.id.widget_sl_col_7)
        private val LABEL_IDS = listOf(R.id.widget_sl_label_1, R.id.widget_sl_label_2, R.id.widget_sl_label_3,
                                       R.id.widget_sl_label_4, R.id.widget_sl_label_5, R.id.widget_sl_label_6,
                                       R.id.widget_sl_label_7)
        private val DATE_IDS  = listOf(R.id.widget_sl_date_1, R.id.widget_sl_date_2, R.id.widget_sl_date_3,
                                       R.id.widget_sl_date_4, R.id.widget_sl_date_5, R.id.widget_sl_date_6,
                                       R.id.widget_sl_date_7)
        private val COUNT_IDS = listOf(R.id.widget_sl_count_1, R.id.widget_sl_count_2, R.id.widget_sl_count_3,
                                       R.id.widget_sl_count_4, R.id.widget_sl_count_5, R.id.widget_sl_count_6,
                                       R.id.widget_sl_count_7)
        private val FIRST_IDS = listOf(R.id.widget_sl_first_1, R.id.widget_sl_first_2, R.id.widget_sl_first_3,
                                       R.id.widget_sl_first_4, R.id.widget_sl_first_5, R.id.widget_sl_first_6,
                                       R.id.widget_sl_first_7)

        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val views   = RemoteViews(ctx.packageName, R.layout.widget_week_sliding)
            val payload = readPayload(ctx)
            val days    = payload?.optJSONArray("week_sliding") ?: JSONArray()

            views.setTextViewText(R.id.widget_sliding_label,
                if (payload == null) "Ouvre l'app" else "")
            views.setOnClickPendingIntent(R.id.widget_sliding_label,
                openAppIntent(ctx, "bsa://tab/agenda"))

            for (i in 0 until 7) {
                val day     = if (i < days.length()) days.getJSONObject(i) else null
                val isToday = day?.optBoolean("is_today", false) ?: false
                val count   = day?.optInt("count", 0) ?: 0
                val first   = day?.optString("first_event") ?: ""
                val countColor = when {
                    count == 0 -> Color.parseColor("#44FFFFFF")
                    count >= 3 -> Color.parseColor("#FF6B6B")
                    else       -> Color.parseColor("#6C8EFF")
                }

                views.setTextViewText(LABEL_IDS[i], day?.optString("label", "-") ?: "-")
                views.setTextColor(LABEL_IDS[i], if (isToday) Color.parseColor("#FFD700") else Color.parseColor("#99FFFFFF"))
                views.setTextViewText(DATE_IDS[i], day?.optString("date", "") ?: "")
                views.setTextColor(DATE_IDS[i], if (isToday) Color.parseColor("#FFD700") else Color.WHITE)
                views.setTextViewText(COUNT_IDS[i], if (count > 0) "$count" else "·")
                views.setTextColor(COUNT_IDS[i], countColor)
                views.setTextViewText(FIRST_IDS[i], first)
                views.setOnClickPendingIntent(COL_IDS[i], openAppIntent(ctx, "bsa://tab/agenda"))
            }

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
        private val ROW_IDS      = listOf(R.id.widget_charge_row_1, R.id.widget_charge_row_2, R.id.widget_charge_row_3, R.id.widget_charge_row_4, R.id.widget_charge_row_5)
        private val WEEK_IDS     = listOf(R.id.widget_charge_week_1, R.id.widget_charge_week_2, R.id.widget_charge_week_3, R.id.widget_charge_week_4, R.id.widget_charge_week_5)
        private val BAR_DONE_IDS = listOf(R.id.widget_charge_bar_done_1, R.id.widget_charge_bar_done_2, R.id.widget_charge_bar_done_3, R.id.widget_charge_bar_done_4, R.id.widget_charge_bar_done_5)
        private val BAR_PEND_IDS = listOf(R.id.widget_charge_bar_pending_1, R.id.widget_charge_bar_pending_2, R.id.widget_charge_bar_pending_3, R.id.widget_charge_bar_pending_4, R.id.widget_charge_bar_pending_5)
        private val COUNT_IDS    = listOf(R.id.widget_charge_count_1, R.id.widget_charge_count_2, R.id.widget_charge_count_3, R.id.widget_charge_count_4, R.id.widget_charge_count_5)

        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val views   = RemoteViews(ctx.packageName, R.layout.widget_charge)
            val payload = readPayload(ctx)
            val weeks   = payload?.optJSONArray("charge_weeks") ?: JSONArray()

            ROW_IDS.forEach { views.setViewVisibility(it, View.GONE) }

            for (i in 0 until minOf(weeks.length(), 5)) {
                val w       = weeks.getJSONObject(i)
                val isCurr  = w.optBoolean("is_current", false)
                val total   = w.optInt("total", 0)
                val done    = w.optInt("done", 0)
                val pending = w.optInt("pending", 0)
                val pctDone = w.optInt("pct_done", 0)
                val pctTot  = w.optInt("pct_total", 0)
                val pctPend = pctTot - (pctTot * pctDone / 100)
                val weekLabel = if (isCurr) "→ ${w.optString("week")}" else w.optString("week", "")

                views.setViewVisibility(ROW_IDS[i], View.VISIBLE)
                views.setTextViewText(WEEK_IDS[i], weekLabel)
                views.setTextColor(WEEK_IDS[i], if (isCurr) Color.parseColor("#FFD700") else Color.parseColor("#99FFFFFF"))
                views.setProgressBar(BAR_DONE_IDS[i], 100, pctTot * pctDone / 100, false)
                views.setProgressBar(BAR_PEND_IDS[i], 100, pctPend, false)
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
            arrayOf(
                NextEventWidget::class.java,
                AgendaWidget::class.java,
                DevoirsWidget::class.java,
                ProgressionWidget::class.java,
                WeekFixedWidget::class.java,
                WeekSlidingWidget::class.java,
                ChargeWidget::class.java
            ).forEach { cls ->
                val provider = android.content.ComponentName(context, cls)
                val ids = mgr.getAppWidgetIds(provider)
                when (cls) {
                    NextEventWidget::class.java    -> ids.forEach { NextEventWidget.update(context, mgr, it) }
                    AgendaWidget::class.java       -> ids.forEach { AgendaWidget.update(context, mgr, it) }
                    DevoirsWidget::class.java      -> ids.forEach { DevoirsWidget.update(context, mgr, it) }
                    ProgressionWidget::class.java  -> ids.forEach { ProgressionWidget.update(context, mgr, it) }
                    WeekFixedWidget::class.java    -> ids.forEach { WeekFixedWidget.update(context, mgr, it) }
                    WeekSlidingWidget::class.java  -> ids.forEach { WeekSlidingWidget.update(context, mgr, it) }
                    ChargeWidget::class.java       -> ids.forEach { ChargeWidget.update(context, mgr, it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "WidgetUpdateReceiver.onReceive() failed: ${e.message}")
        }
    }
}
