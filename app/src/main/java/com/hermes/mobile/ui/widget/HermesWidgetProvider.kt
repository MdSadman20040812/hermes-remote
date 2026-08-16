package com.hermes.mobile.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.hermes.mobile.R

/**
 * Home-screen widget (Phase 6): connection dot + tap-to-open. State refresh is
 * pull-based (onUpdate) — the socket owns the truth; the widget just opens
 * the app. Battery-friendly by construction.
 */
class HermesWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_hermes)
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, com.hermes.mobile.ui.MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            manager.updateAppWidget(id, views)
        }
    }
}
