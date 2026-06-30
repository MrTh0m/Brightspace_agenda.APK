package fr.thomasetsandie.emmgo.agenda

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*

// ── Couleurs urgence ─────────────────────────────────────────────────
private val COLOR_RED    = Color.parseColor("#FF6B6B")
private val COLOR_ORANGE = Color.parseColor("#FFA500")
private val COLOR_YELLOW = Color.parseColor("#FFD700")
private val COLOR_BLUE   = Color.parseColor("#6C8EFF")
private val COLOR_GREEN  = Color.parseColor("#52C77A")
private val COLOR_WHITE  = Color.WHITE

// ── Helper : lire le payload JSON depuis SharedPreferences ───────────
// La clé "bsa_widget_data" est écrite par capacitor-widget-bridge
// via CapacitorWidgetBridge.sendWidgetData({ data: JSON.stringify(payload) })
private fun readPayload(context: Context): JSONObject? {
    return try {
        val prefs = context.getSharedPreferences("WidgetPreferences", Context.MODE_PRIVATE)
        val raw   = prefs.getString("bsa_widget_data", null) ?: return null
        JSONObject(raw)
    } catch (e: Exception) { null }
}

// ── Helper : créer un PendingIntent pour ouvrir l'app sur un deep link ─
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

// ── Helper : formater le temps restant ──────────────────────────────
private fun formatInMin(min: Int): String = when {
    min <= 0    -> "En cours"
    min < 60    -> "Dans ${min} min"
    min < 1440  -> "Dans ${min / 60}h${if (min % 60 > 0) "${min % 60}" else ""}"
    else        -> "Dans ${min / 1440}j"
}

// ── Helper : couleur selon urgence (jours) ──────────────────────────
private fun urgencyColor(days: Int): Int = when {
    days <= 1 -> COLOR_RED
    days <= 3 -> COLOR_ORANGE
    days <= 7 -> COLOR_YELLOW
    else      -> COLOR_WHITE
}

// ── Helper : libellé du délai (jours) ───────────────────────────────
private fun formatDays(days: Int): String = when {
    days <= 0 -> "Aujourd'hui"
    days == 1 -> "Demain"
    else      -> "J-$days"
}

// ══════════════════════════════════════════════════════════════════════
// Widget 1 — Prochain événement (2×1)
// ══════════════════════════════════════════════════════════════════════
class NextEventWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(ctx, mgr, it) }
    }

    companion object {
        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val views   = RemoteViews(ctx.packageName, R.layout.widget_next_event)
            val payload = readPayload(ctx)
            val next    = payload?.optJSONObject("next_event")

            if (next == null) {
                views.setTextViewText(R.id.widget_time_remaining, "—")
                views.setTextViewText(R.id.widget_event_title,    "Aucun événement à venir")
                views.setTextViewText(R.id.widget_event_meta,     "")
                views.setOnClickPendingIntent(R.id.widget_label,
                    openAppIntent(ctx, "bsa://tab/agenda"))
            } else {
                val inMin  = next.optInt("in_min", 0)
                val type   = next.optString("type", "")
                val typeLabel = if (type == "session") "Live session" else "Atelier"
                val tapUrl = next.optString("tap_url", "bsa://tab/agenda")

                views.setTextViewText(R.id.widget_time_remaining, formatInMin(inMin))
                views.setTextViewText(R.id.widget_event_title, next.optString("title"))
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
        ids.forEach { update(ctx, mgr, it) }
    }

    companion object {
        // IDs des 5 lignes — parallèles aux @id du XML, stables
        private val ROW_IDS  = listOf(R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3,
                                      R.id.widget_row_4, R.id.widget_row_5)
        private val TIME_IDS = listOf(R.id.widget_row_1_time, R.id.widget_row_2_time,
                                      R.id.widget_row_3_time, R.id.widget_row_4_time,
                                      R.id.widget_row_5_time)
        private val TITLE_IDS= listOf(R.id.widget_row_1_title, R.id.widget_row_2_title,
                                      R.id.widget_row_3_title, R.id.widget_row_4_title,
                                      R.id.widget_row_5_title)
        private val DOT_IDS  = listOf(R.id.widget_row_1_dot, R.id.widget_row_2_dot,
                                      R.id.widget_row_3_dot, R.id.widget_row_4_dot,
                                      R.id.widget_row_5_dot)

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
                if (count > 0) "$count événement${if (count > 1) "s" else ""}" else "")

            // Masquer toutes les lignes + vide par défaut
            ROW_IDS.forEach { views.setViewVisibility(it, View.GONE) }
            views.setViewVisibility(R.id.widget_empty,
                if (count == 0) View.VISIBLE else View.GONE)

            // Peupler les lignes disponibles
            for (i in 0 until minOf(count, 5)) {
                val ev     = today.getJSONObject(i)
                val type   = ev.optString("type", "session")
                val tapUrl = ev.optString("tap_url", "bsa://tab/agenda")
                val dotColor = if (type == "session") COLOR_BLUE else COLOR_GREEN

                views.setViewVisibility(ROW_IDS[i], View.VISIBLE)
                views.setTextViewText(TIME_IDS[i], ev.optString("time"))
                views.setTextViewText(TITLE_IDS[i], ev.optString("title"))
                views.setInt(DOT_IDS[i], "setColorFilter", dotColor)
                views.setOnClickPendingIntent(ROW_IDS[i], openAppIntent(ctx, tapUrl))
            }

            // Tap global → agenda
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
        ids.forEach { update(ctx, mgr, it) }
    }

    companion object {
        private val ROW_IDS  = listOf(R.id.widget_devoir_row_1, R.id.widget_devoir_row_2,
                                      R.id.widget_devoir_row_3, R.id.widget_devoir_row_4,
                                      R.id.widget_devoir_row_5)
        private val DOT_IDS  = listOf(R.id.widget_devoir_dot_1, R.id.widget_devoir_dot_2,
                                      R.id.widget_devoir_dot_3, R.id.widget_devoir_dot_4,
                                      R.id.widget_devoir_dot_5)
        private val TITLE_IDS= listOf(R.id.widget_devoir_title_1, R.id.widget_devoir_title_2,
                                      R.id.widget_devoir_title_3, R.id.widget_devoir_title_4,
                                      R.id.widget_devoir_title_5)
        private val DATE_IDS = listOf(R.id.widget_devoir_date_1, R.id.widget_devoir_date_2,
                                      R.id.widget_devoir_date_3, R.id.widget_devoir_date_4,
                                      R.id.widget_devoir_date_5)

        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val views   = RemoteViews(ctx.packageName, R.layout.widget_devoirs)
            val payload = readPayload(ctx)
            val devoirs = payload?.optJSONObject("devoirs")
            val list    = devoirs?.optJSONArray("list") ?: JSONArray()
            val pending = devoirs?.optInt("pending", 0) ?: 0
            val done    = devoirs?.optInt("done", 0) ?: 0

            views.setTextViewText(R.id.widget_devoirs_pending,
                "$pending à rendre")
            views.setTextViewText(R.id.widget_devoirs_done,
                "$done rendus")

            // Masquer toutes les lignes
            ROW_IDS.forEach { views.setViewVisibility(it, View.GONE) }
            views.setViewVisibility(R.id.widget_devoirs_empty,
                if (pending == 0) View.VISIBLE else View.GONE)

            for (i in 0 until minOf(list.length(), 5)) {
                val d      = list.getJSONObject(i)
                val days   = d.optInt("in_days", 99)
                val tapUrl = d.optString("tap_url", "bsa://tab/devoirs")

                views.setViewVisibility(ROW_IDS[i], View.VISIBLE)
                views.setTextViewText(TITLE_IDS[i], d.optString("title"))
                views.setTextViewText(DATE_IDS[i], formatDays(days))
                views.setInt(DOT_IDS[i], "setColorFilter", urgencyColor(days))
                views.setTextColor(DATE_IDS[i], urgencyColor(days))
                views.setOnClickPendingIntent(ROW_IDS[i], openAppIntent(ctx, tapUrl))
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
        ids.forEach { update(ctx, mgr, it) }
    }

    companion object {
        private val ROW_IDS  = listOf(R.id.widget_mat_row_1, R.id.widget_mat_row_2,
                                      R.id.widget_mat_row_3, R.id.widget_mat_row_4)
        private val NAME_IDS = listOf(R.id.widget_mat_name_1, R.id.widget_mat_name_2,
                                      R.id.widget_mat_name_3, R.id.widget_mat_name_4)
        private val SCORE_IDS= listOf(R.id.widget_mat_score_1, R.id.widget_mat_score_2,
                                      R.id.widget_mat_score_3, R.id.widget_mat_score_4)
        private val BAR_IDS  = listOf(R.id.widget_mat_bar_1, R.id.widget_mat_bar_2,
                                      R.id.widget_mat_bar_3, R.id.widget_mat_bar_4)

        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val views   = RemoteViews(ctx.packageName, R.layout.widget_progression)
            val payload = readPayload(ctx)
            val prog    = payload?.optJSONObject("progression")
            val mats    = prog?.optJSONArray("matieres") ?: JSONArray()
            val percent = prog?.optInt("percent", 0) ?: 0
            val done    = prog?.optInt("done", 0) ?: 0
            val total   = prog?.optInt("total", 0) ?: 0

            views.setTextViewText(R.id.widget_prog_percent, "$percent%")
            views.setProgressBar(R.id.widget_prog_bar_global, 100, percent, false)
            views.setTextViewText(R.id.widget_prog_summary,
                "$done rendus sur $total devoirs")

            ROW_IDS.forEach { views.setViewVisibility(it, View.GONE) }

            for (i in 0 until minOf(mats.length(), 4)) {
                val m     = mats.getJSONObject(i)
                val mDone = m.optInt("done", 0)
                val mTot  = m.optInt("total", 1)
                val pct   = if (mTot > 0) mDone * 100 / mTot else 0

                views.setViewVisibility(ROW_IDS[i], View.VISIBLE)
                views.setTextViewText(NAME_IDS[i], m.optString("name"))
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
// Receiver pour mettre à jour tous les widgets depuis l'app
// Appelé quand capacitor-widget-bridge envoie un broadcast
// ══════════════════════════════════════════════════════════════════════
class WidgetUpdateReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val mgr = AppWidgetManager.getInstance(context)
        listOf(
            NextEventWidget::class.java    to "widget_next_event_info",
            AgendaWidget::class.java       to "widget_agenda_info",
            DevoirsWidget::class.java      to "widget_devoirs_info",
            ProgressionWidget::class.java  to "widget_progression_info",
        ).forEach { (cls, _) ->
            val provider = android.content.ComponentName(context, cls)
            val ids = mgr.getAppWidgetIds(provider)
            when (cls) {
                NextEventWidget::class.java   -> ids.forEach { NextEventWidget.update(context, mgr, it) }
                AgendaWidget::class.java      -> ids.forEach { AgendaWidget.update(context, mgr, it) }
                DevoirsWidget::class.java     -> ids.forEach { DevoirsWidget.update(context, mgr, it) }
                ProgressionWidget::class.java -> ids.forEach { ProgressionWidget.update(context, mgr, it) }
            }
        }
    }
}
