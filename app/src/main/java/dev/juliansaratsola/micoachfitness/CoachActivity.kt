package dev.juliansaratsola.micoachfitness

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.text.InputType
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
 * that compares against stored history, and provides a contextual follow-up chat.
 */
class CoachActivity : Activity() {
    private lateinit var prefs: MonitorPreferences
    private lateinit var store: WorkoutStore
    private lateinit var chatStore: CoachChatStore
    private lateinit var pendingText: TextView
    private lateinit var notesInput: EditText
    private lateinit var generateButton: Button
    private lateinit var reviewContainer: LinearLayout
    private lateinit var chatMessagesContainer: LinearLayout
    private lateinit var chatInput: EditText
    private lateinit var chatSendButton: Button
    private lateinit var pageScroll: ScrollView
    private var conversation = emptyList<CoachChatMessage>()
    private var busy = false
    private var chatBusy = false
    private var autoGenerate = false
    private var autoHealth = false
    private lateinit var healthButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = MonitorPreferences(this)
        store = WorkoutStore(this)
        chatStore = CoachChatStore(this)
        try {
            conversation = chatStore.all()
        } catch (error: JSONException) {
            Toast.makeText(this, R.string.coach_chat_history_error, Toast.LENGTH_LONG).show()
        }
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
            setOnClickListener { chooseHealthSession() }
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
        root.addView(text(getString(R.string.coach_chat_title), 22f, bold = true, color = getColor(R.color.seed)).apply {
            setPadding(0, dp(32), 0, dp(8))
        })
        chatMessagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(chatMessagesContainer)
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chatInput = EditText(this).apply {
            hint = getString(R.string.coach_chat_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 1
            maxLines = 5
        }
        composer.addView(chatInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        chatSendButton = Button(this).apply {
            text = getString(R.string.coach_chat_send)
            setOnClickListener { sendChatMessage() }
        }
        composer.addView(chatSendButton)
        root.addView(composer)
        pageScroll = ScrollView(this).apply { addView(root) }
        BottomNav.attach(this, pageScroll, BottomNav.Tab.COACH)
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
        renderChatMessages()
        updateChatControls()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_HEALTH_OPTIONAL) {
            chooseHealthSession()
            return
        }
        if (requestCode != REQUEST_HEALTH) return
        if (HealthConnectReader(this).hasPermissions()) {
            prefs.healthWatchEnabled = true
            generate(fromHealth = true)
        } else {
            Toast.makeText(this, R.string.hc_none, Toast.LENGTH_LONG).show()
        }
    }

    /** Lists recent Health Connect workouts so the user picks the right one instead of guessing. */
    private fun chooseHealthSession() {
        if (busy || chatBusy) return
        if (!HealthConnectReader.supported()) {
            Toast.makeText(this, R.string.hc_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        if (!HealthConnectReader(this).hasPermissions()) {
            generate(fromHealth = true)
            return
        }
        if (!prefs.healthOptionalAsked) {
            prefs.healthOptionalAsked = true
            val missing = HealthConnectReader.OPTIONAL_PERMISSIONS.filter {
                checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                requestPermissions(missing.toTypedArray(), REQUEST_HEALTH_OPTIONAL)
                return
            }
        }
        healthButton.isEnabled = false
        Thread {
            val sessions = runCatching { HealthConnectReader(applicationContext).recentSessions() }.getOrDefault(emptyList())
            runOnUiThread {
                healthButton.isEnabled = !busy
                if (isDestroyed) return@runOnUiThread
                if (sessions.isEmpty()) {
                    Toast.makeText(this, R.string.hc_none, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val format = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
                val labels = sessions.map { it.chooserLabel(format.format(Date(it.startMillis))) }.toTypedArray()
                android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.hc_choose)
                    .setItems(labels) { _, which -> generate(fromHealth = true, chosen = sessions[which]) }
                    .show()
            }
        }.start()
    }

    private fun generate(fromHealth: Boolean = false, chosen: HealthSession? = null) {
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
                    HealthConnectReader.REQUIRED_PERMISSIONS + HealthConnectReader.OPTIONAL_PERMISSIONS +
                        HealthConnectReader.BACKGROUND_PERMISSION,
                    REQUEST_HEALTH
                )
                return
            }
        }
        val uris = prefs.pendingCaptures().map { Uri.parse(it) }
        if ((!fromHealth && uris.isEmpty()) || busy || chatBusy) return
        busy = true
        generateButton.isEnabled = false
        healthButton.isEnabled = false
        updateChatControls()
        generateButton.text = getString(R.string.coach_generating)
        val notes = notesInput.text.toString()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        Thread {
            val outcome = runCatching {
                if (fromHealth) {
                    val session = chosen ?: HealthConnectReader(applicationContext).latestSession()
                        ?: throw CoachException(getString(R.string.hc_none))
                    val sessionDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(session.startMillis))
                    val raw = GeminiClient(applicationContext, key)
                        .review(uris, store.all(), today, notes, "Fecha del entrenamiento: $sessionDate\n" + session.toPromptText())
                    raw.put("date", sessionDate)
                    store.add(raw, sessionDate)
                    prefs.lastHealthSessionEnd = session.endMillis
                    prefs.clearPendingCaptures()
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

    private fun sendChatMessage() {
        val message = chatInput.text.toString().trim()
        if (message.isBlank() || busy || chatBusy) return
        val key = prefs.geminiApiKey
        if (key.isBlank()) {
            Toast.makeText(this, R.string.coach_missing_key, Toast.LENGTH_LONG).show()
            return
        }
        val pendingConversation = conversation + CoachChatMessage(CoachChatRole.USER, message)
        chatBusy = true
        updateChatControls()
        Thread {
            val outcome = runCatching {
                GeminiClient(applicationContext, key).chat(store.all(), pendingConversation)
            }
            runOnUiThread {
                chatBusy = false
                if (isDestroyed) return@runOnUiThread
                outcome.onSuccess { answer ->
                    conversation = (pendingConversation + CoachChatMessage(CoachChatRole.COACH, answer)).takeLast(40)
                    chatStore.save(conversation)
                    chatInput.setText("")
                    renderChatMessages()
                    pageScroll.post { pageScroll.fullScroll(View.FOCUS_DOWN) }
                }.onFailure {
                    Toast.makeText(
                        this,
                        getString(R.string.coach_chat_error, it.message ?: it.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
                updateChatControls()
            }
        }.start()
    }

    private fun renderChatMessages() {
        chatMessagesContainer.removeAllViews()
        val density = resources.displayMetrics.density
        conversation.forEach { message ->
            val row = LinearLayout(this).apply {
                gravity = if (message.role == CoachChatRole.USER) Gravity.END else Gravity.START
                setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
            }
            val bubble = text(message.text, 15f).apply {
                val color = if (message.role == CoachChatRole.USER) Color.rgb(232, 240, 254) else Color.rgb(242, 242, 242)
                background = GradientDrawable().apply {
                    setColor(color)
                    cornerRadius = 16 * density
                }
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                contentDescription = if (message.role == CoachChatRole.USER) {
                    getString(R.string.coach_chat_user_message, message.text)
                } else {
                    getString(R.string.coach_chat_reply_message, message.text)
                }
            }
            row.addView(bubble, LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.78f).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
            chatMessagesContainer.addView(row)
        }
    }

    private fun updateChatControls() {
        if (!::chatSendButton.isInitialized || !::chatInput.isInitialized) return
        chatSendButton.isEnabled = !busy && !chatBusy
        chatInput.isEnabled = !busy && !chatBusy
        chatSendButton.text = getString(if (chatBusy) R.string.coach_chat_sending else R.string.coach_chat_send)
    }

    private fun renderRecord(record: WorkoutRecord, previousCount: Int) {
        val c = reviewContainer
        c.addView(text("${record.date} · ${record.type}", 14f, color = Color.GRAY))
        c.addView(text("${record.overallScore}/10  ${record.headline}", 22f, bold = true, color = scoreColor(record.overallScore)))
        renderMetrics(record)
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

    private fun renderMetrics(record: WorkoutRecord) {
        val density = resources.displayMetrics.density
        for ((title, items) in record.metricGroups()) {
            reviewContainer.addView(text(title, 13f, bold = true, color = getColor(R.color.seed)).apply {
                setPadding(0, (12 * density).toInt(), 0, (2 * density).toInt())
            })
            for ((label, value) in items) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(text(label, 15f, color = Color.GRAY), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(text(value, 15f, bold = true))
                reviewContainer.addView(row)
            }
        }
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
        private const val REQUEST_HEALTH_OPTIONAL = 43
    }
}
