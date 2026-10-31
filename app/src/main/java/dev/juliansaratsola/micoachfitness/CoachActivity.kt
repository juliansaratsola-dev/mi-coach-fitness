package dev.juliansaratsola.micoachfitness

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Coach screen: collects the Garmin screenshots of one workout, asks Gemini for a review
 * that compares against the stored history, and shows scored sections and suggestions.
 */
class CoachActivity : Activity() {
    private lateinit var prefs: MonitorPreferences
    private lateinit var store: WorkoutStore
    private lateinit var pendingText: TextView
    private lateinit var notesInput: EditText
    private lateinit var generateButton: Button
    private lateinit var reviewContainer: LinearLayout
    private var busy = false
    private var autoGenerate = false
    private var autoHealth = false
    private lateinit var healthButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = MonitorPreferences(this)
        store = WorkoutStore(this)
        buildUi()
        if (savedInstanceState == null) handleIntent(intent)
        refresh()
        runPendingAutoGenerate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        refresh()
        runPendingAutoGenerate()
    }

    private fun runPendingAutoGenerate() {
        if (!autoGenerate) return
        autoGenerate = false
        val health = autoHealth
        autoHealth = false
        generate(health)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTO_GENERATE, false) == true) {
            autoGenerate = true
            autoHealth = intent.getBooleanExtra(EXTRA_FROM_HEALTH, false)
            intent.removeExtra(EXTRA_FROM_HEALTH)
            intent.removeExtra(EXTRA_AUTO_GENERATE)
        }
        val uri = intent?.data ?: return
        if (uri.scheme == "content" && uri.authority?.contains("media") == true) {
            prefs.addPendingCapture(uri.toString())
        }
        intent.data = null
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        root.addView(text(getString(R.string.coach_title), 26f, bold = true, color = getColor(R.color.seed)))
        pendingText = text("", 16f).apply { setPadding(0, dp(12), 0, dp(8)) }
        root.addView(pendingText)
        notesInput = EditText(this).apply { hint = getString(R.string.coach_notes_hint) }
        root.addView(notesInput)
        generateButton = Button(this).apply {
            text = getString(R.string.coach_generate)
            setOnClickListener { generate() }
        }
        root.addView(generateButton)
        healthButton = Button(this).apply {
            text = getString(R.string.hc_button)
            setOnClickListener { generate(fromHealth = true) }
        }
        root.addView(healthButton)
        root.addView(Button(this).apply {
            text = getString(R.string.coach_clear)
            setOnClickListener {
                prefs.clearPendingCaptures()
                refresh()
            }
        })
        reviewContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(reviewContainer)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun refresh() {
        val count = prefs.pendingCaptures().size
        pendingText.text = getString(R.string.coach_pending, count)
        generateButton.isEnabled = count > 0 && !busy
        healthButton.isEnabled = !busy
        if (!busy) {
            reviewContainer.removeAllViews()
            try {
                val history = store.all()
                if (history.isNotEmpty()) {
                    renderRecord(history.last(), history.dropLast(1).size)
                    renderHistory(history)
                }
            } catch (error: IOException) {
                Toast.makeText(this, R.string.coach_history_error, Toast.LENGTH_LONG).show()
            } catch (error: JSONException) {
                Toast.makeText(this, R.string.coach_history_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_HEALTH) return
        if (HealthConnectReader(this).hasPermissions()) {
            prefs.healthWatchEnabled = true
            generate(fromHealth = true)
        } else {
            Toast.makeText(this, R.string.hc_none, Toast.LENGTH_LONG).show()
        }
    }

    private fun generate(fromHealth: Boolean = false) {
        val key = prefs.geminiApiKey
        if (key.isBlank()) {
            Toast.makeText(this, R.string.coach_missing_key, Toast.LENGTH_LONG).show()
            return
        }
        if (fromHealth) {
            if (!HealthConnectReader.supported()) {
                Toast.makeText(this, R.string.hc_unsupported, Toast.LENGTH_LONG).show()
                return
            }
            if (!HealthConnectReader(this).hasPermissions()) {
                requestPermissions(
                    HealthConnectReader.REQUIRED_PERMISSIONS + HealthConnectReader.BACKGROUND_PERMISSION,
                    REQUEST_HEALTH
                )
                return
            }
        }
        val uris = if (fromHealth) emptyList() else prefs.pendingCaptures().map { Uri.parse(it) }
        if ((!fromHealth && uris.isEmpty()) || busy) return
        busy = true
        generateButton.isEnabled = false
        healthButton.isEnabled = false
        generateButton.text = getString(R.string.coach_generating)
        val notes = notesInput.text.toString()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        Thread {
            val outcome = runCatching {
                if (fromHealth) {
                    val session = HealthConnectReader(applicationContext).latestSession()
                        ?: throw CoachException(getString(R.string.hc_none))
                    val sessionDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(session.startMillis))
                    val raw = GeminiClient(applicationContext, key)
                        .review(emptyList(), store.all(), today, notes, "Fecha del entrenamiento: $sessionDate\n" + session.toPromptText())
                    raw.put("date", sessionDate)
                    store.add(raw, sessionDate)
                    prefs.lastHealthSessionEnd = session.endMillis
                } else {
                    val raw = GeminiClient(applicationContext, key).review(uris, store.all(), today, notes)
                    store.add(raw, today)
                }
            }
            runOnUiThread {
                busy = false
                generateButton.text = getString(R.string.coach_generate)
                outcome.onSuccess {
                    if (!fromHealth) prefs.clearPendingCaptures()
                    notesInput.setText("")
                }.onFailure {
                    Toast.makeText(
                        this,
                        getString(R.string.coach_error, it.message ?: it.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
                if (!isDestroyed) refresh()
            }
        }.start()
    }

    private fun renderRecord(record: WorkoutRecord, previousCount: Int) {
        val c = reviewContainer
        c.addView(text("${record.date} · ${record.type}", 14f, color = Color.GRAY))
        c.addView(text("${record.overallScore}/10  ${record.headline}", 22f, bold = true, color = scoreColor(record.overallScore)))
        c.addView(text(metricsLine(record), 14f).apply { setPadding(0, 8, 0, 8) })
        for (section in record.sections) {
            c.addView(text("${section.title}  ${section.score}/10", 17f, bold = true, color = scoreColor(section.score)).apply {
                setPadding(0, 20, 0, 4)
            })
            c.addView(text(section.review, 15f))
        }
        if (record.comparison.isNotBlank()) {
            val title = getString(R.string.coach_comparison, previousCount)
            c.addView(text(title, 17f, bold = true).apply { setPadding(0, 24, 0, 4) })
            c.addView(text(record.comparison, 15f))
        }
        if (record.suggestions.isNotEmpty()) {
            c.addView(text(getString(R.string.coach_suggestions), 17f, bold = true).apply { setPadding(0, 24, 0, 4) })
            record.suggestions.forEach { c.addView(text("• $it", 15f)) }
        }
        if (record.followUpQuestions.isNotEmpty()) {
            c.addView(text(getString(R.string.coach_followup), 17f, bold = true).apply { setPadding(0, 24, 0, 4) })
            record.followUpQuestions.forEach { c.addView(text("• $it", 15f)) }
        }
    }

    private fun renderHistory(history: List<WorkoutRecord>) {
        reviewContainer.addView(text(getString(R.string.coach_history), 17f, bold = true).apply {
            setPadding(0, 32, 0, 4)
        })
        history.asReversed().forEach {
            val line = "${it.date}  ${it.overallScore}/10  " +
                "${it.distanceKm?.let { km -> "%.1f km".format(Locale.US, km) } ?: "-"}  " +
                "${it.paceText()}/km  FC ${it.avgHr ?: "-"}"
            reviewContainer.addView(text(line, 14f))
        }
    }

    private fun metricsLine(r: WorkoutRecord): String {
        val parts = mutableListOf<String>()
        r.distanceKm?.let { parts += "%.2f km".format(Locale.US, it) }
        r.paceSecPerKm?.let { parts += "${r.paceText()}/km" }
        r.avgHr?.let { parts += "$it ppm" }
        r.cadence?.let { parts += "$it spm" }
        r.powerW?.let { parts += "$it W" }
        return parts.joinToString("  ·  ")
    }

    private fun scoreColor(score: Int): Int = when {
        score >= 8 -> Color.rgb(46, 125, 50)
        score >= 6 -> Color.rgb(245, 124, 0)
        else -> Color.rgb(198, 40, 40)
    }

    private fun text(value: String, size: Float, bold: Boolean = false, color: Int? = null): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (color != null) setTextColor(color)
            visibility = View.VISIBLE
        }
    }

    companion object {
        const val EXTRA_AUTO_GENERATE = "auto_generate"
        const val EXTRA_FROM_HEALTH = "from_health"
        private const val REQUEST_HEALTH = 42
    }
}
