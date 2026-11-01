package dev.juliansaratsola.micoachfitness

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONException
import java.io.IOException
import java.text.NumberFormat
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
    private lateinit var loadingContainer: LinearLayout
    private lateinit var loadingText: TextView
    private lateinit var reviewContainer: LinearLayout
    private lateinit var historyButton: Button
    private lateinit var chatMessagesContainer: LinearLayout
    private lateinit var chatInput: EditText
    private lateinit var chatSendButton: Button
    private lateinit var pageScroll: ScrollView
    private var conversation = emptyList<CoachChatMessage>()
    private var currentHistory = emptyList<WorkoutRecord>()
    private var pendingChatMessage: String? = null
    private var busy = false
    private var chatBusy = false
    private var autoGenerate = false
    private var autoHealth = false
    private lateinit var healthButton: Button
    private val loadingHandler = Handler(Looper.getMainLooper())
    private var loadingRunnable: Runnable? = null

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

    override fun onDestroy() {
        loadingRunnable?.let(loadingHandler::removeCallbacks)
        super.onDestroy()
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        root.addView(text(getString(R.string.coach_title), 26f, bold = true, color = getColor(R.color.seed)))
        pendingText = text("", 16f).apply { setPadding(0, dp(12), 0, dp(8)) }
        root.addView(pendingText)
        notesInput = EditText(this).apply {
            hint = getString(R.string.coach_notes_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 6
            gravity = Gravity.TOP
        }
        root.addView(notesInput)
        val multiplier = String.format(Locale.getDefault(), "%.1f", EffortCalculator.TRENCH_MULTIPLIER)
        root.addView(text(getString(R.string.coach_notes_help, multiplier), 12f, color = getColor(R.color.ink_muted)).apply {
            setPadding(0, 0, 0, dp(8))
        })
        generateButton = Button(this).apply {
            text = getString(R.string.coach_generate)
            setOnClickListener { generate() }
        }
        root.addView(generateButton)
        loadingText = text("", 14f, color = Color.GRAY)
        loadingContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        loadingContainer.addView(ProgressBar(this).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(28), dp(28)).apply {
            marginEnd = dp(8)
        })
        loadingContainer.addView(loadingText)
        root.addView(loadingContainer)
        healthButton = Button(this).apply {
            text = getString(R.string.hc_button)
            setOnClickListener { chooseHealthSession() }
        }
        root.addView(healthButton)
        root.addView(Button(this).apply {
            text = getString(R.string.coach_clear)
            setOnClickListener {
                confirmClearCaptures()
            }
        })
        reviewContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(reviewContainer)
        historyButton = Button(this).apply {
            visibility = View.GONE
            setOnClickListener { showHistoryManager() }
        }
        root.addView(historyButton)
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
        pageScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        chatInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) chatInput.post {
                chatInput.requestRectangleOnScreen(android.graphics.Rect(0, 0, chatInput.width, chatInput.height), true)
            }
        }
        BottomNav.attach(this, pageScroll, BottomNav.Tab.COACH)
        pendingText.setOnClickListener { showCaptureManager() }
    }

    override fun onResume() {
        super.onResume()
        if (::pendingText.isInitialized) refresh()
    }

    private fun refresh() {
        val count = prefs.pendingCaptures().size
        pendingText.text = if (count > 0) getString(R.string.coach_pending, count) else getString(R.string.coach_pending_empty)
        pendingText.isClickable = count > 0
        generateButton.isEnabled = count > 0 && !busy && !chatBusy
        healthButton.isEnabled = !busy && !chatBusy
        if (!busy) {
            reviewContainer.removeAllViews()
            try {
                currentHistory = store.all()
                historyButton.visibility = if (currentHistory.isEmpty()) View.GONE else View.VISIBLE
                historyButton.text = getString(R.string.coach_history_count, currentHistory.size)
                if (currentHistory.isNotEmpty()) {
                    renderRecord(currentHistory.last(), currentHistory.dropLast(1).size)
                }
            } catch (error: IOException) {
                currentHistory = emptyList()
                historyButton.visibility = View.GONE
                Toast.makeText(this, R.string.coach_history_error, Toast.LENGTH_LONG).show()
            } catch (error: JSONException) {
                currentHistory = emptyList()
                historyButton.visibility = View.GONE
                Toast.makeText(this, R.string.coach_history_error, Toast.LENGTH_LONG).show()
            }
        }
        renderChatMessages()
        updateChatControls()
    }

    private fun startReviewLoading() {
        val messages = listOf(
            getString(R.string.coach_loading_cadence),
            getString(R.string.coach_loading_history),
            getString(R.string.coach_loading_metrics)
        )
        loadingContainer.visibility = View.VISIBLE
        fun updateMessage() {
            if (!busy || isDestroyed) return
            loadingText.text = messages.random()
            loadingRunnable = Runnable { updateMessage() }
            loadingHandler.postDelayed(loadingRunnable!!, 2_000L)
        }
        updateMessage()
    }

    private fun stopReviewLoading() {
        loadingRunnable?.let(loadingHandler::removeCallbacks)
        loadingRunnable = null
        if (::loadingContainer.isInitialized) loadingContainer.visibility = View.GONE
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
                healthButton.isEnabled = !busy && !chatBusy
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
        startReviewLoading()
        val notes = notesInput.text.toString()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        Thread {
            val outcome = runCatching {
                if (fromHealth) {
                    val session = chosen ?: HealthConnectReader(applicationContext).latestSession()
                        ?: throw CoachException(getString(R.string.hc_none))
                    val sessionDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(session.startMillis))
                    val raw = GeminiClient(applicationContext, key).review(
                        uris,
                        store.getHistoricalContextForAI(),
                        today,
                        notes,
                        "Fecha del entrenamiento: $sessionDate\n" + session.toPromptText()
                    )
                    raw.put("date", sessionDate)
                    store.add(raw, sessionDate, notes)
                    prefs.lastHealthSessionEnd = session.endMillis
                    prefs.clearPendingCaptures()
                } else {
                    val raw = GeminiClient(applicationContext, key)
                        .review(uris, store.getHistoricalContextForAI(), today, notes)
                    store.add(raw, today, notes)
                }
            }
            runOnUiThread {
                busy = false
                stopReviewLoading()
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
        pendingChatMessage = message
        updateChatControls()
        renderChatMessages()
        pageScroll.post { pageScroll.fullScroll(View.FOCUS_DOWN) }
        Thread {
            val outcome = runCatching {
                GeminiClient(applicationContext, key).chat(
                    store.all(),
                    pendingConversation,
                    store.latestAnalysis()
                )
            }
            runOnUiThread {
                chatBusy = false
                if (isDestroyed) return@runOnUiThread
                pendingChatMessage = null
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
                renderChatMessages()
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
                val fromUser = message.role == CoachChatRole.USER
                background = GradientDrawable().apply {
                    setColor(getColor(if (fromUser) R.color.bubble_user else R.color.cream_card))
                    if (!fromUser) setStroke(dp(1), getColor(R.color.cream_line))
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
        pendingChatMessage?.let { message ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.END
                setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
            }
            val bubble = text(message, 15f).apply {
                background = GradientDrawable().apply {
                    setColor(Color.rgb(232, 240, 254))
                    cornerRadius = 16 * density
                }
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            }
            row.addView(
                bubble,
                LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.78f).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            )
            chatMessagesContainer.addView(row)
        }
        if (chatBusy) {
            val row = LinearLayout(this).apply {
                gravity = Gravity.START
                setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
            }
            val bubble = text(getString(R.string.coach_chat_typing), 15f, color = Color.GRAY).apply {
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            }
            row.addView(bubble)
            chatMessagesContainer.addView(row)
        }
    }

    private fun updateChatControls() {
        if (!::chatSendButton.isInitialized || !::chatInput.isInitialized) return
        generateButton.isEnabled = prefs.pendingCaptures().isNotEmpty() && !busy && !chatBusy
        healthButton.isEnabled = !busy && !chatBusy
        chatSendButton.isEnabled = !busy && !chatBusy
        chatInput.isEnabled = !busy && !chatBusy
        chatSendButton.text = getString(if (chatBusy) R.string.coach_chat_sending else R.string.coach_chat_send)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Stacks a review block under the previous one. */
    private fun blockParams(topDp: Int = 10) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(topDp) }

    private fun renderRecord(record: WorkoutRecord, previousCount: Int) {
        reviewContainer.addView(EffortCardView(this).apply { bind(record) }, blockParams(0))
        if (record.hasLegacyReview()) {
            renderLegacyReview(record, previousCount)
        } else {
            renderVerdadPuraReview(record)
        }
        renderMetrics(record)
    }

    /** Effort first, speed last: the verdict and the effort reading come before consistency and technique. */
    private fun renderVerdadPuraReview(record: WorkoutRecord) {
        val review = record.verdadPura
        if (review.veredictoTrinchera.isNotBlank()) {
            val trench = record.esDiaTrinchera
            reviewContainer.addView(
                reviewCard(
                    getString(R.string.review_verdict),
                    review.veredictoTrinchera,
                    titleColor = getColor(if (trench) R.color.trench_dark else R.color.seed),
                    accent = getColor(if (trench) R.color.trench else R.color.seed)
                ),
                blockParams(16)
            )
        }
        listOf(
            R.string.review_cardio to review.cardioYEsfuerzo,
            R.string.review_comparison to review.comparativaMeritocratica,
            R.string.review_consistency to review.potenciaYConsistencia,
            R.string.review_technique to (if (record.technicalMetricsWarning) {
                getString(R.string.coach_technical_read_warning)
            } else {
                review.tecnica
            })
        ).filter { it.second.isNotBlank() }.forEach { (title, body) ->
            reviewContainer.addView(
                reviewCard(getString(title), body, titleColor = getColor(R.color.ink_muted)),
                blockParams()
            )
        }
    }

    /** One block of the review. The card that has an [accent] border is the prominent one (the verdict). */
    private fun reviewCard(title: String, body: String, titleColor: Int, accent: Int? = null): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            isScreenReaderFocusable = true
            background = if (accent == null) {
                getDrawable(R.drawable.bg_card)
            } else {
                GradientDrawable().apply {
                    setColor(getColor(R.color.cream_card))
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(2), accent)
                }
            }
        }
        card.addView(text(title, 12f, bold = true, color = titleColor).apply {
            isAllCaps = true
            letterSpacing = 0.1f
        })
        card.addView(text(body, if (accent == null) 15f else 17f).apply { setPadding(0, dp(6), 0, 0) })
        return card
    }

    /** Workouts reviewed before "Verdad Pura" keep the graded layout they were saved with. */
    private fun renderLegacyReview(record: WorkoutRecord, previousCount: Int) {
        val c = reviewContainer
        c.addView(text("${record.overallScore}/10  ${record.headline}", 22f, bold = true, color = scoreColor(record.overallScore)).apply {
            setPadding(0, dp(16), 0, 0)
        })
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
        listOf(
            "Técnica" to record.tecnica,
            "Cardio" to record.cardio,
            "Potencia y rendimiento" to record.potenciaRendimiento,
            "Comparativa poblacional" to record.comparativaPoblacional
        ).filter { it.second.isNotBlank() }.forEach { (title, review) ->
            c.addView(text(title, 17f, bold = true).apply { setPadding(0, 24, 0, 4) })
            c.addView(text(review, 15f))
        }
    }

    private fun showHistoryManager() {
        val history = currentHistory
        if (history.isEmpty()) {
            Toast.makeText(this, R.string.coach_history_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val indices = history.indices.reversed().toList()
        val labels = indices.map { index ->
            val record = history[index]
            val points = NumberFormat.getIntegerInstance().format(record.puntosEsfuerzo)
            "${record.headerText()} · ${getString(R.string.history_points, points)}"
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.coach_history)
            .setItems(labels) { _, selected -> showHistoryDetails(indices[selected], history[indices[selected]]) }
            .setNegativeButton(R.string.coach_close, null)
            .show()
    }

    private fun showHistoryDetails(index: Int, record: WorkoutRecord) {
        val details = buildString {
            append(record.detailParts().joinToString(" · "))
            append("\n").append(getString(R.string.effort_points_label)).append(": ").append(record.puntosEsfuerzo)
            if (record.esDiaTrinchera) append(" · ").append(getString(R.string.legend_trench))
            record.metricGroups().forEach { (title, items) ->
                append("\n\n").append(title).append('\n')
                items.forEach { append(it.first).append(": ").append(it.second).append('\n') }
            }
            if (record.hasLegacyReview()) {
                if (record.headline.isNotBlank()) append("\n\n").append(record.headline)
                record.sections.forEach {
                    append("\n\n").append(it.title).append(" ").append(it.score).append("/10\n").append(it.review)
                }
                listOf(
                    "Técnica" to record.tecnica,
                    "Cardio" to record.cardio,
                    "Potencia y rendimiento" to record.potenciaRendimiento,
                    "Comparativa" to record.comparativaPoblacional,
                    "Comparativa personal" to record.comparison
                ).filter { it.second.isNotBlank() }.forEach { (title, body) ->
                    append("\n\n").append(title).append('\n').append(body)
                }
                record.suggestions.forEach { append("\n\nSugerencia\n").append(it) }
                record.followUpQuestions.forEach { append("\n\nSeguimiento\n").append(it) }
            } else {
                val review = record.verdadPura
                listOf(
                    getString(R.string.review_verdict) to review.veredictoTrinchera,
                    getString(R.string.review_cardio) to review.cardioYEsfuerzo,
                    getString(R.string.review_comparison) to review.comparativaMeritocratica,
                    getString(R.string.review_consistency) to review.potenciaYConsistencia,
                    getString(R.string.review_technique) to (if (record.technicalMetricsWarning) {
                        getString(R.string.coach_technical_read_warning)
                    } else {
                        review.tecnica
                    })
                ).filter { it.second.isNotBlank() }.forEach { (title, body) ->
                    append("\n\n").append(title).append('\n').append(body)
                }
            }
            record.feelingsForPrompt()?.let { append("\n\n").append(getString(R.string.coach_notes_hint)).append(": ").append(it) }
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(record.headerText())
            .setMessage(details.trim())
            .setPositiveButton(R.string.coach_close, null)
            .setNegativeButton(R.string.coach_history_delete) { _, _ ->
                confirmDeleteHistoryRecord(index)
            }
            .show()
    }

    private fun confirmDeleteHistoryRecord(index: Int) {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.coach_history_delete_confirm_title)
            .setMessage(R.string.coach_history_delete_confirm)
            .setNegativeButton(R.string.coach_close, null)
            .setPositiveButton(R.string.coach_history_delete) { _, _ ->
                try {
                    store.deleteAt(index)
                    refresh()
                } catch (error: IOException) {
                    Toast.makeText(this, R.string.coach_history_error, Toast.LENGTH_LONG).show()
                } catch (error: JSONException) {
                    Toast.makeText(this, R.string.coach_history_error, Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun showCaptureManager() {
        val captures = prefs.pendingCaptures()
        if (captures.isEmpty()) {
            Toast.makeText(this, R.string.coach_capture_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = captures.mapIndexed { index, value -> captureLabel(index, value) }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.coach_capture_manager, captures.size))
            .setItems(labels) { _, selected ->
                android.app.AlertDialog.Builder(this)
                    .setMessage(R.string.coach_capture_remove_confirm)
                    .setNegativeButton(R.string.coach_close, null)
                    .setPositiveButton(R.string.coach_capture_remove) { _, _ ->
                        prefs.removePendingCapture(captures[selected])
                        refresh()
                    }
                    .show()
            }
            .setNegativeButton(R.string.coach_close, null)
            .show()
    }

    private fun captureLabel(index: Int, value: String): String {
        val uri = Uri.parse(value)
        val name = try {
            contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val column = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    if (column >= 0 && !cursor.isNull(column)) cursor.getString(column) else null
                } else {
                    null
                }
            }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SecurityException) {
            null
        }
        return name ?: getString(R.string.coach_capture_unknown, index + 1)
    }

    private fun confirmClearCaptures() {
        if (prefs.pendingCaptures().isEmpty()) return
        android.app.AlertDialog.Builder(this)
            .setMessage(R.string.coach_capture_clear_confirm)
            .setNegativeButton(R.string.coach_close, null)
            .setPositiveButton(R.string.coach_clear) { _, _ ->
                prefs.clearPendingCaptures()
                refresh()
            }
            .show()
    }

    private fun renderMetrics(record: WorkoutRecord) {
        val groups = record.metricGroups()
        if (groups.isEmpty()) return
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_card)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        card.addView(text(getString(R.string.review_details), 12f, bold = true, color = getColor(R.color.ink_muted)).apply {
            isAllCaps = true
            letterSpacing = 0.1f
        })
        for ((title, items) in groups) {
            card.addView(text(title, 13f, bold = true, color = getColor(R.color.seed)).apply {
                setPadding(0, dp(12), 0, dp(2))
            })
            for ((label, value) in items) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(text(label, 15f, color = getColor(R.color.ink_muted)), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(text(value, 15f, bold = true))
                card.addView(row)
            }
        }
        reviewContainer.addView(card, blockParams(16))
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
            setTextColor(color ?: getColor(R.color.ink))
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
