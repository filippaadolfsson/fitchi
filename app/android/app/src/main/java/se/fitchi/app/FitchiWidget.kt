package se.fitchi.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Fitchin på hemskärmen. Appen sparar läget i SharedPreferences, widgeten räknar själv ner glöden. */
class FitchiWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, vy(context))
    }

    companion object {
        const val PREFS = "fitchi_widget"

        fun uppdateraAlla(context: Context) {
            val m = AppWidgetManager.getInstance(context)
            val ids = m.getAppWidgetIds(ComponentName(context, FitchiWidget::class.java))
            for (id in ids) m.updateAppWidget(id, vy(context))
        }

        fun vy(context: Context): RemoteViews {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val art = p.getString("art", "bjorn") ?: "bjorn"
            val namn = p.getString("namn", "Din fitchi") ?: "Din fitchi"
            var humor = p.getString("humor", "neutral") ?: "neutral"
            var text = p.getString("text", "Öppna Fitchi för att börja") ?: ""
            val niva = p.getInt("niva", 1)
            val tid = p.getLong("tid", System.currentTimeMillis())
            val sover = p.getBoolean("sover", false)
            val timmar = (System.currentTimeMillis() - tid) / 3600000.0
            val glod = (p.getFloat("glod", 70f) - timmar * 50.0 / 24.0).coerceIn(0.0, 100.0)

            if (glod <= 0.0) { humor = "sover"; text = "$namn sover i sin kristall. Väck hen!" }
            else if (sover) { humor = "sover"; text = "$namn sover gott" }
            else if (glod < 30.0) { humor = "trott"; text = "$namn saknar dig lite" }
            else if (timmar > 6 && humor != "neutral") { humor = "neutral"; text = "$namn väntar på dig" }

            val v = RemoteViews(context.packageName, R.layout.fitchi_widget)
            var bild = context.resources.getIdentifier("w_${art}_$humor", "drawable", context.packageName)
            if (bild == 0) bild = context.resources.getIdentifier("w_${art}_neutral", "drawable", context.packageName)
            if (bild != 0) v.setImageViewResource(R.id.w_bild, bild)
            v.setTextViewText(R.id.w_namn, namn)
            v.setTextViewText(R.id.w_humor, text)
            v.setProgressBar(R.id.w_glod, 100, glod.toInt(), false)
            v.setTextViewText(R.id.w_info, "Nivå $niva · glöd ${glod.toInt()}")
            val oppna = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            v.setOnClickPendingIntent(R.id.w_rot, PendingIntent.getActivity(context, 0, oppna, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            return v
        }
    }
}
