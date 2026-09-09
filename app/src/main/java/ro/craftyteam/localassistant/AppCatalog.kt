package ro.craftyteam.localassistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.text.Normalizer

class AppCatalog(private val context: Context) {
    data class AppEntry(val label: String, val packageName: String)

    fun installedApps(): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { AppEntry(it.loadLabel(pm).toString().trim(), it.activityInfo.packageName) }
            .filter { it.label.isNotBlank() }
            .distinctBy { it.packageName }
            .sortedBy { normalize(it.label) }
    }

    fun summary(maxChars: Int = 2600): String {
        return installedApps()
            .joinToString("\n") { "- ${it.label}" }
            .take(maxChars)
            .ifBlank { "No launcher apps discovered." }
    }

    fun resolve(requestedName: String): AppEntry? {
        val query = normalize(requestedName)
        if (query.isBlank()) return null

        val apps = installedApps()
        val exact = apps.firstOrNull { normalize(it.label) == query }
        if (exact != null) return exact

        val compactQuery = compact(query)
        val compactExact = apps.firstOrNull { compact(normalize(it.label)) == compactQuery }
        if (compactExact != null) return compactExact

        val contains = apps
            .filter {
                val label = normalize(it.label)
                label.contains(query) || query.contains(label)
            }
            .minByOrNull { normalize(it.label).length }
        if (contains != null) return contains

        if (compactQuery.length in 2..6) {
            return apps
                .mapNotNull { app ->
                    val label = compact(normalize(app.label))
                    if (isSubsequence(compactQuery, label)) {
                        app to (label.length - compactQuery.length)
                    } else {
                        null
                    }
                }
                .minWithOrNull(compareBy<Pair<AppEntry, Int>> { it.second }.thenBy { it.first.label.length })
                ?.first
        }

        return null
    }

    private fun isSubsequence(query: String, value: String): Boolean {
        var index = 0
        for (char in value) {
            if (index < query.length && query[index] == char) index++
            if (index == query.length) return true
        }
        return false
    }

    private fun compact(value: String): String = value.replace("[^a-z0-9]".toRegex(), "")

    private fun normalize(value: String): String {
        return Normalizer.normalize(value.lowercase().trim(), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .replace("\\s+".toRegex(), " ")
    }
}
