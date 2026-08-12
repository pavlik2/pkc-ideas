package com.example.cancerapp

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.cancerapp.data.JsonFileStore
import com.example.cancerapp.data.JsonFileStore.DataFile
import com.example.cancerapp.documents.DocumentTextExtractor
import com.example.cancerapp.llm.PromptTemplates
import com.example.cancerapp.llm.RemoteLlmClient
import com.example.cancerapp.network.EUClinicalTrialsApi
import com.example.cancerapp.network.PubMedApi
import com.example.cancerapp.ui.QuestionChartView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var host: FrameLayout
    private lateinit var store: JsonFileStore
    private val executor = Executors.newSingleThreadExecutor()
    private var currentScreen = ""

    private val documentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            processDocument(uri)
        }
    }

    private val extractionSchemaPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Could not read the selected schema")
                require(raw.length <= 500_000) { "The schema is too large (maximum 500 KB)" }
                val schema = JSONObject(raw)
                require(schema.optJSONObject("properties") != null || schema.optString("type") == "object") {
                    "The schema must describe a JSON object and contain type: object or properties"
                }
                store.write(DataFile.EXTRACTION_SCHEMA, schema.toString(2))
                toast("Medical extraction schema saved for this profile")
                showImport()
            } catch (error: Exception) {
                showError("Invalid JSON schema", error)
            }
        }
    }

    private val profileExporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            runBusy("Exporting profile…", {
                contentResolver.openOutputStream(uri)?.use(store::exportActiveProfile) ?: error("Could not create the ZIP file")
                true
            }) { toast("Profile exported") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        host = findViewById(R.id.screen_host)
        store = JsonFileStore(this)
        if (store.hasActiveProfile()) showMain() else showSetup(firstLaunch = true)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (currentScreen) {
            "setup-first" -> super.onBackPressed()
            "main" -> super.onBackPressed()
            else -> showMain()
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun showSetup(firstLaunch: Boolean) {
        currentScreen = if (firstLaunch) "setup-first" else "setup"
        val existing = store.activeProfile()
        val content = verticalScreen(
            if (firstLaunch) "Setup LLM Access" else "LLM settings",
            "Choose any OpenAI-compatible service. Your access key stays in this app's private storage."
        )
        val providers = arrayOf("LM Studio", "Ollama", "llama.cpp", "Groq", "OpenAI", "Custom")
        val provider = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, providers)
        }
        content.addView(label("Provider preset")); content.addView(provider, matchWrap())
        content.addView(paragraph("Local providers are listed first because they can run privately without an API fee. 10.0.2.2 reaches the host computer from the Android emulator."), matchWrap(top = 5))
        val name = field("Profile name", existing?.optString("name", "") ?: "")
        val url = field("LLM URL", existing?.optString("baseUrl", "") ?: "http://10.0.2.2:1234")
        val model = field("Model", existing?.optString("model", "") ?: "local-model")
        val key = field("Access key (optional for local servers)", existing?.optString("apiKey", "") ?: "", password = true)
        val temperature = field("Temperature (0–2)", existing?.optDouble("temperature", 0.2)?.toString() ?: "0.2", decimal = true)
        val maxTokens = field("Maximum response tokens", existing?.optInt("maxTokens", 1200)?.toString() ?: "1200", number = true)
        val timeout = field("Timeout in seconds", existing?.optInt("timeoutSeconds", 60)?.toString() ?: "60", number = true)
        val headers = field("Extra headers as JSON", existing?.optString("extraHeaders", "{}") ?: "{}", multiline = true)
        val systemPrompt = field("Additional system instructions (optional)", existing?.optString("systemPrompt", "") ?: "", multiline = true)
        listOf(name, url, model, key, temperature, maxTokens, timeout, headers, systemPrompt).forEach { content.addView(it, matchWrap()) }

        var applyingPreset = false
        provider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (applyingPreset) return
                val values = when (providers[position]) {
                    "OpenAI" -> Triple("OpenAI", "https://api.openai.com", "gpt-4.1-mini")
                    "Groq" -> Triple("Groq", "https://api.groq.com/openai", "llama-3.3-70b-versatile")
                    "LM Studio" -> Triple("LM Studio", "http://10.0.2.2:1234", "local-model")
                    "Ollama" -> Triple("Ollama", "http://10.0.2.2:11434", "llama3.2")
                    "llama.cpp" -> Triple("llama.cpp", "http://10.0.2.2:8080", "local-model")
                    else -> Triple("Custom", "https://", "")
                }
                name.setText(values.first); url.setText(values.second); model.setText(values.third)
            }
        }
        if (existing != null) {
            applyingPreset = true
            val position = providers.indexOf(existing.optString("provider")).coerceAtLeast(providers.lastIndex)
            provider.setSelection(position)
            applyingPreset = false
        }

        val save = primaryButton(if (firstLaunch) "Save and continue" else "Save profile") {
            try {
                JSONObject(headers.text.toString().ifBlank { "{}" })
                val profile = JSONObject()
                    .put("id", existing?.optString("id")?.ifBlank { UUID.randomUUID().toString() } ?: UUID.randomUUID().toString())
                    .put("provider", providers[provider.selectedItemPosition])
                    .put("name", required(name, "Profile name"))
                    .put("baseUrl", required(url, "LLM URL"))
                    .put("apiKey", key.text.toString().trim())
                    .put("model", required(model, "Model"))
                    .put("temperature", temperature.text.toString().toDouble().also { require(it in 0.0..2.0) })
                    .put("maxTokens", maxTokens.text.toString().toInt().coerceIn(64, 32_000))
                    .put("timeoutSeconds", timeout.text.toString().toInt().coerceIn(5, 300))
                    .put("extraHeaders", headers.text.toString().ifBlank { "{}" })
                    .put("systemPrompt", systemPrompt.text.toString().trim())
                val root = JSONObject().put("profiles", JSONArray().put(profile)).put("activeProfileId", profile.getString("id"))
                store.write(DataFile.LLM_PROFILES, root.toString(2))
                toast("Profile saved")
                if (firstLaunch) showImport() else showMain()
            } catch (error: Exception) { showError("Check the profile values", error) }
        }
        content.addView(save, matchWrap(top = 18))
        content.addView(secondaryButton("Test connection") {
            try {
                val temp = JSONObject().put("baseUrl", required(url, "LLM URL")).put("apiKey", key.text.toString())
                    .put("model", required(model, "Model")).put("temperature", temperature.text.toString().toDouble())
                    .put("maxTokens", 32).put("timeoutSeconds", timeout.text.toString().toInt()).put("extraHeaders", headers.text.toString())
                runBusy("Testing connection…", {
                    RemoteLlmClient().complete(RemoteLlmClient.Config.fromJson(temp), "Reply briefly.", "Reply with the word connected.")
                }) { toast("Connection successful: ${it.take(60)}") }
            } catch (error: Exception) { showError("Check the profile values", error) }
        }, matchWrap(top = 10))
        content.addView(disclaimer(), matchWrap(top = 24, bottom = 24))
        render(content)
    }

    private fun showMain() {
        currentScreen = "main"
        val patient = store.activePatientProfile()
        if (patient == null) { showProfileManager(); return }
        val content = verticalScreen("Care Companion", "${patient.optString("name")} • Private health workspace")
        content.addView(secondaryButton("Switch profile") { showProfileManager() }, matchWrap(top = 8, bottom = 5))
        val items = listOf(
            Triple("Info", "Review your structured medical summary", ::showInfo),
            Triple("Chat", "Ask questions grounded in your stored records", ::showChat),
            Triple("Medical tracker", "Complete or update your daily questionnaire", ::showTracker),
            Triple("Clinical trials", "Search the EU clinical-trials register", ::showTrials),
            Triple("Medical Papers", "Find studies in PubMed", ::showPapers),
            Triple("Settings", "LLM profile, tracker limit, and private data", ::showSettings)
        )
        items.forEach { (title, subtitle, action) -> content.addView(menuCard(title, subtitle) { action() }, matchWrap(top = 10)) }
        content.addView(disclaimer(), matchWrap(top = 22, bottom = 24))
        render(content)
    }

    private fun showImport() {
        currentScreen = "import"
        val content = verticalScreen("Submit your medical documents", "Select a PDF, text file, or picture. Extracted text is sent only to your configured LLM.")
        content.addView(infoCard("Supported files", "PDF and text are extracted on-device. Images use a bundled on-device OCR model; no separate OCR installation or model download is needed."), matchWrap(top = 10))
        val schemaRaw = store.read(DataFile.EXTRACTION_SCHEMA)
        val schema = JSONObject(schemaRaw)
        val schemaLabel = if (schema.length() == 0) {
            "Built-in medical schema"
        } else {
            val title = schema.optString("title").ifBlank { "Custom JSON Schema" }
            val properties = schema.optJSONObject("properties")?.length() ?: 0
            "$title • $properties top-level properties"
        }
        content.addView(infoCard("Extraction structure", schemaLabel), matchWrap(top = 10))
        content.addView(secondaryButton("Upload medical extraction JSON Schema") {
            extractionSchemaPicker.launch(arrayOf("application/schema+json", "application/json", "text/plain"))
        }, matchWrap(top = 9))
        if (schema.length() > 0) {
            content.addView(secondaryButton("View current schema") { showJsonDialog("Medical extraction schema", schemaRaw) }, matchWrap(top = 8))
            content.addView(secondaryButton("Use built-in schema") {
                store.write(DataFile.EXTRACTION_SCHEMA, "{}"); toast("Built-in extraction schema restored"); showImport()
            }, matchWrap(top = 8))
        }
        content.addView(primaryButton("Select file or picture") {
            documentPicker.launch(arrayOf("application/pdf", "text/*", "image/*"))
        }, matchWrap(top = 24))
        content.addView(secondaryButton("Skip for now") { showMain() }, matchWrap(top = 10))
        render(content)
    }

    private fun processDocument(uri: Uri) {
        currentScreen = "processing"
        val content = verticalScreen("Processing…", "Reading the document and asking your LLM to structure explicit medical facts.")
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        content.addView(progress, matchWrap(top = 28))
        val status = paragraph("Please keep the app open. This may take a moment.")
        content.addView(status, matchWrap(top = 14))
        render(content)
        runBusy(null, {
            val extracted = DocumentTextExtractor(this).extract(uri)
            val profile = store.activeProfile() ?: error("Configure an LLM profile first")
            val schemaRaw = store.read(DataFile.EXTRACTION_SCHEMA)
            val response = RemoteLlmClient().complete(
                RemoteLlmClient.Config.fromJson(profile), PromptTemplates.medicalExtraction(schemaRaw),
                "SOURCE DOCUMENT: ${extracted.displayName}\n\n${extracted.text}"
            )
            val medical = parseJsonObject(response)
            validateMedicalAgainstSchema(medical, JSONObject(schemaRaw))
            store.write(DataFile.MEDICAL_DATA, medical.toString(2))
            store.append(DataFile.DOCUMENTS, "documents", JSONObject().put("name", extracted.displayName)
                .put("mimeType", extracted.mimeType).put("importedAt", timestamp()).put("characterCount", extracted.text.length)
                .put("extractionSchema", if (JSONObject(schemaRaw).length() > 0) "custom" else "built-in"))
            extracted.displayName
        }) {
            toast("Processed $it")
            showInfo()
        }
    }

    private fun showInfo() {
        currentScreen = "info"
        val content = verticalScreen("Info", "Structured facts extracted from your submitted medical records.")
        val raw = store.read(DataFile.MEDICAL_DATA)
        if (raw.trim() == "{}") {
            content.addView(emptyState("No medical information yet", "Import a document to build your summary."), matchWrap(top = 14))
        } else {
            content.addView(infoCard("Medical summary", prettyMedical(JSONObject(raw))), matchWrap(top = 12))
            content.addView(secondaryButton("View structured JSON") { showJsonDialog("Processed medical data", raw) }, matchWrap(top = 10))
        }
        content.addView(primaryButton("Import another document") { showImport() }, matchWrap(top = 18))
        render(content)
    }

    private fun showChat() {
        currentScreen = "chat"
        val content = verticalScreen("Chat", "Ask about your stored information. Responses are informational, not medical advice.")
        val history = JSONObject(store.read(DataFile.CHAT_HISTORY)).optJSONArray("messages") ?: JSONArray()
        if (history.length() == 0) content.addView(emptyState("No messages yet", "Try: “Summarize the treatments listed in my records.”"), matchWrap(top = 10))
        for (index in 0 until history.length()) {
            val message = history.getJSONObject(index)
            content.addView(infoCard(if (message.optString("role") == "user") "You" else "Assistant", message.optString("content")), matchWrap(top = 8))
        }
        val input = field("Message", multiline = true)
        content.addView(input, matchWrap(top = 16))
        content.addView(primaryButton("Send") {
            val message = input.text.toString().trim()
            if (message.isBlank()) return@primaryButton
            val patientContext = store.read(DataFile.MEDICAL_DATA).take(50_000)
            runBusy("Contacting your LLM…", {
                val profile = store.activeProfile() ?: error("Configure an LLM profile first")
                val prior = (0 until history.length()).toList().takeLast(8).joinToString("\n") {
                    val item = history.getJSONObject(it); "${item.optString("role")}: ${item.optString("content")}"
                }
                RemoteLlmClient().complete(RemoteLlmClient.Config.fromJson(profile),
                    PromptTemplates.chatSystem + "\n" + profile.optString("systemPrompt"),
                    "PATIENT CONTEXT:\n$patientContext\n\nRECENT CHAT:\n$prior\n\nUSER MESSAGE:\n$message")
            }) { reply ->
                store.append(DataFile.CHAT_HISTORY, "messages", JSONObject().put("role", "user").put("content", message).put("at", timestamp()))
                store.append(DataFile.CHAT_HISTORY, "messages", JSONObject().put("role", "assistant").put("content", reply).put("at", timestamp()))
                showChat()
            }
        }, matchWrap(top = 10, bottom = 24))
        render(content)
    }

    private fun showTracker() {
        currentScreen = "tracker"
        val content = verticalScreen("Medical tracker", "Record today’s symptoms and observations for discussion with your care team.")
        val questions = JSONObject(store.read(DataFile.QUESTIONNAIRE)).optJSONArray("questions") ?: JSONArray()
        if (questions.length() == 0) {
            content.addView(emptyState("No questionnaire yet", "Generate questions from your structured medical summary."), matchWrap(top = 12))
            content.addView(primaryButton("Generate questionnaire") { generateQuestionnaire() }, matchWrap(top = 18))
        } else {
            val answers = mutableListOf<() -> JSONObject>()
            for (index in 0 until questions.length()) {
                val question = questions.getJSONObject(index)
                val type = question.optString("type")
                val block = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)); background = rounded(color(R.color.surface), 18)
                }
                block.addView(TextView(this).apply {
                    text = "${index + 1}. ${question.optString("label")}"; textSize = 17f; setTextColor(color(R.color.text_primary)); setTypeface(typeface, Typeface.BOLD)
                }, matchWrap())
                question.optString("helpText").takeIf { it.isNotBlank() }?.let { block.addView(paragraph(it), matchWrap(top = 4)) }
                val options = question.optJSONArray("options") ?: JSONArray()
                if (type == "single_choice" && options.length() > 0) {
                    val labels = mutableListOf("Choose an answer…")
                    for (optionIndex in 0 until options.length()) labels += options.optJSONObject(optionIndex)?.optString("label").orEmpty()
                    val spinner = Spinner(this).apply {
                        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels)
                        background = rounded(color(R.color.surface), 12, color(R.color.primary_soft), 1); setPadding(dp(10), dp(8), dp(10), dp(8))
                    }
                    block.addView(spinner, matchWrap(top = 10))
                    answers += {
                        val selected = spinner.selectedItemPosition - 1
                        if (selected < 0 && question.optBoolean("required", true)) error("Please answer: ${question.optString("label")}")
                        val option = if (selected >= 0) options.getJSONObject(selected) else JSONObject()
                        JSONObject().put("questionId", question.optString("id")).put("label", question.optString("label"))
                            .put("value", option.optString("value")).put("displayValue", option.optString("label"))
                            .put("numericValue", option.opt("numericValue") ?: JSONObject.NULL).put("unit", option.opt("unit") ?: JSONObject.NULL)
                    }
                } else {
                    val edit = field("Your answer", multiline = type == "text")
                    block.addView(edit, matchWrap(top = 10))
                    answers += {
                        val value = edit.text.toString().trim()
                        if (question.optBoolean("required") && value.isBlank()) error("Please answer: ${question.optString("label")}")
                        JSONObject().put("questionId", question.optString("id")).put("label", question.optString("label"))
                            .put("value", value).put("displayValue", value).put("numericValue", value.toDoubleOrNull() ?: JSONObject.NULL)
                    }
                }
                content.addView(block, matchWrap(top = 9))
            }
            content.addView(primaryButton("Save today’s answers") {
                try {
                    val values = JSONArray()
                    answers.forEach { values.put(it()) }
                    store.upsert(DataFile.QUESTIONNAIRE_HISTORY, "entries", "date", JSONObject().put("date", today())
                        .put("recordedAt", timestamp()).put("answers", values))
                    toast("Today's answers were saved")
                    showMain()
                } catch (error: Exception) { showError("Could not save answers", error) }
            }, matchWrap(top = 18))
            content.addView(secondaryButton("Regenerate questions") { generateQuestionnaire() }, matchWrap(top = 10))
        }
        val count = JSONObject(store.read(DataFile.QUESTIONNAIRE_HISTORY)).optJSONArray("entries")?.length() ?: 0
        if (count > 0) content.addView(secondaryButton("View history and trends") { showTrackerHistory(7, "7 days") }, matchWrap(top = 12))
        content.addView(paragraph("$count daily entr${if (count == 1) "y" else "ies"} saved."), matchWrap(top = 18, bottom = 24))
        render(content)
    }

    private fun showTrackerHistory(days: Int?, periodLabel: String) {
        currentScreen = "tracker-history"
        val content = verticalScreen("History and trends", "$periodLabel • ${store.activePatientProfile()?.optString("name")}")
        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(1 to "Today", 7 to "7 days", 28 to "4 weeks", null to "All").forEach { (period, label) ->
                addView(secondaryButton(label) { showTrackerHistory(period, label) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(4)
                })
            }
        }
        content.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(filters) }, matchWrap(top = 8))
        val allEntries = JSONObject(store.read(DataFile.QUESTIONNAIRE_HISTORY)).optJSONArray("entries") ?: JSONArray()
        val cutoff = days?.let { dateDaysAgo(it - 1) }
        val entries = (0 until allEntries.length()).mapNotNull { allEntries.optJSONObject(it) }
            .filter { cutoff == null || it.optString("date") >= cutoff }
        if (entries.isEmpty()) {
            content.addView(emptyState("No entries in this period", "Choose another time range or complete today's questionnaire."), matchWrap(top = 14))
        } else {
            val questions = JSONObject(store.read(DataFile.QUESTIONNAIRE)).optJSONArray("questions") ?: JSONArray()
            for (questionIndex in 0 until questions.length()) {
                val question = questions.optJSONObject(questionIndex) ?: continue
                val points = entries.mapNotNull { entry ->
                    val answers = entry.optJSONArray("answers") ?: JSONArray()
                    val answer = (0 until answers.length()).mapNotNull { answers.optJSONObject(it) }
                        .firstOrNull { it.optString("questionId") == question.optString("id") } ?: return@mapNotNull null
                    val number = (answer.opt("numericValue") as? Number)?.toFloat()
                        ?: answer.optString("value").toFloatOrNull() ?: return@mapNotNull null
                    QuestionChartView.Point(entry.optString("date").removePrefix(today().substringBeforeLast('-') + "-"), number)
                }
                if (points.isNotEmpty()) {
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(10)); background = rounded(color(R.color.surface), 18)
                        addView(TextView(this@MainActivity).apply {
                            text = question.optString("label"); textSize = 17f; setTextColor(color(R.color.text_primary)); setTypeface(typeface, Typeface.BOLD)
                        })
                        addView(paragraph("Higher/lower values follow the ordered answer choices defined for this question."), matchWrap(top = 4))
                        addView(QuestionChartView(this@MainActivity).apply { setPoints(points) }, matchWrap(top = 6))
                    }
                    content.addView(card, matchWrap(top = 10))
                }
            }
            content.addView(label("Daily entries"), matchWrap(top = 18))
            entries.asReversed().forEach { entry ->
                val lines = (entry.optJSONArray("answers") ?: JSONArray()).let { answers ->
                    (0 until answers.length()).mapNotNull { answers.optJSONObject(it) }.joinToString("\n") { answer ->
                        "${answer.optString("label")}: ${answer.optString("displayValue", answer.optString("value"))}"
                    }
                }
                content.addView(infoCard(entry.optString("date"), lines), matchWrap(top = 8))
            }
        }
        content.addView(secondaryButton("Back to questionnaire") { showTracker() }, matchWrap(top = 18, bottom = 24))
        render(content)
    }

    private fun generateQuestionnaire() {
        val medical = store.read(DataFile.MEDICAL_DATA)
        if (medical.trim() == "{}") { showError("Import a medical document first", IllegalStateException("No structured medical data is available")); return }
        val limit = JSONObject(store.read(DataFile.SETTINGS)).optInt("questionLimit", 12).coerceIn(1, 50)
        runBusy("Generating up to $limit questions…", {
            val profile = store.activeProfile() ?: error("Configure an LLM profile first")
            val response = RemoteLlmClient().complete(RemoteLlmClient.Config.fromJson(profile), PromptTemplates.questionnaire(limit), medical.take(50_000))
            val parsed = parseJsonObject(response)
            val questions = parsed.optJSONArray("questions") ?: error("The LLM response has no questions array")
            require(questions.length() in 1..limit) { "The LLM returned ${questions.length()} questions; maximum is $limit" }
            var textQuestions = 0
            for (index in 0 until questions.length()) {
                val question = questions.getJSONObject(index)
                require(question.optString("id").isNotBlank() && question.optString("label").isNotBlank()) { "Question ${index + 1} is incomplete" }
                if (question.optString("type") == "text") {
                    textQuestions++
                } else {
                    val options = question.optJSONArray("options") ?: error("Question ${index + 1} has no defined answers")
                    require(options.length() in 3..8) { "Question ${index + 1} must have 3–8 defined answers" }
                    for (optionIndex in 0 until options.length()) {
                        require(options.getJSONObject(optionIndex).optString("label").isNotBlank()) { "Question ${index + 1} has an unnamed answer" }
                    }
                    question.put("type", "single_choice")
                }
            }
            require(textQuestions <= 1) { "The questionnaire may contain only one free-text notes question" }
            store.write(DataFile.QUESTIONNAIRE, parsed.toString(2))
            questions.length()
        }) { toast("Created $it questions"); showTracker() }
    }

    private fun showPapers() {
        currentScreen = "papers"
        val content = verticalScreen("Medical Papers", "Search all PubMed records, publications with abstracts, or freely available full publications.")
        val query = field("PubMed search")
        content.addView(query, matchWrap(top = 12))
        val modes = PubMedApi.SearchMode.entries
        val mode = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, modes.map { it.label })
            background = rounded(color(R.color.surface), 12, color(R.color.primary_soft), 1)
        }
        content.addView(label("Search scope"), matchWrap(top = 8)); content.addView(mode, matchWrap())
        content.addView(primaryButton("Search PubMed") {
            val term = query.text.toString().trim()
            val selectedMode = modes[mode.selectedItemPosition]
            runBusy("Searching PubMed…", { PubMedApi().search(term, 1, mode = selectedMode) }) { result ->
                store.append(DataFile.SEARCH_HISTORY, "searches", JSONObject().put("source", "pubmed").put("query", term)
                    .put("mode", selectedMode.name).put("searchedAt", timestamp()).put("resultCount", result.totalRecords))
                showPaperResults(term, selectedMode, result)
            }
        }, matchWrap(top = 10))
        content.addView(paragraph("Results are supplied by the U.S. National Library of Medicine. Inclusion does not mean a study applies to you."), matchWrap(top = 18, bottom = 24))
        render(content)
    }

    private fun showPaperResults(query: String, mode: PubMedApi.SearchMode, result: PubMedApi.SearchPage) {
        currentScreen = "papers-results"
        val content = verticalScreen("PubMed results", "${mode.label} • ${result.totalRecords} results for “$query” • Page ${result.page} of ${result.totalPages.coerceAtLeast(1)}")
        if (result.papers.isEmpty()) content.addView(emptyState("No papers found", "Try a broader scientific term."), matchWrap(top = 12))
        result.papers.forEach { paper ->
            val preview = listOf(paper.journal, paper.date, paper.authors, paper.abstractText.take(180).takeIf { it.isNotBlank() })
                .filterNotNull().filter { it.isNotBlank() }.joinToString("\n")
            content.addView(menuCard(paper.title, preview) {
                showPaperDetail(paper)
            }, matchWrap(top = 9))
        }
        content.addView(paginationControls(
            result.page,
            result.totalPages,
            result.hasPrevious,
            result.hasNext,
            previous = { loadPaperPage(query, mode, result.page - 1) },
            next = { loadPaperPage(query, mode, result.page + 1) }
        ), matchWrap(top = 16))
        content.addView(secondaryButton("New search") { showPapers() }, matchWrap(top = 18, bottom = 24))
        render(content)
    }

    private fun loadPaperPage(query: String, mode: PubMedApi.SearchMode, page: Int) {
        runBusy("Loading PubMed page $page…", { PubMedApi().search(query, page, mode = mode) }) { showPaperResults(query, mode, it) }
    }

    private fun showPaperDetail(paper: PubMedApi.Paper) {
        currentScreen = "paper-detail"
        val content = verticalScreen(paper.title, listOf(paper.journal, paper.date).filter { it.isNotBlank() }.joinToString(" • "))
        addDetailSection(content, "Authors", paper.authors)
        addDetailSection(content, "Abstract", paper.abstractText.ifBlank { "No abstract is available in PubMed for this publication." })
        if (paper.pmcId != null) {
            content.addView(primaryButton("Read full text in app") {
                runBusy("Loading article text from PMC…", { PubMedApi().fetchPmcFullText(paper.pmcId) }) {
                    showPmcFullText(paper, it)
                }
            }, matchWrap(top = 18))
            content.addView(secondaryButton("Open official PMC page") {
                openUrl("https://pmc.ncbi.nlm.nih.gov/articles/${paper.pmcId}/")
            }, matchWrap(top = 9))
        }
        if (paper.id.isNotBlank()) {
            content.addView(secondaryButton("Open PubMed record") { openUrl("https://pubmed.ncbi.nlm.nih.gov/${paper.id}/") }, matchWrap(top = 9, bottom = 24))
        }
        render(content)
    }

    private fun showPmcFullText(paper: PubMedApi.Paper, article: PubMedApi.FullTextArticle) {
        currentScreen = "pmc-full-text"
        val content = verticalScreen(paper.title, "${article.pmcId} • Full text supplied by PubMed Central")
        content.addView(infoCard(
            "Reading and reuse",
            article.licenseNotice.ifBlank {
                "This article is free to read in PMC. Reuse and redistribution rights vary by article; check the license on the official PMC page."
            }
        ), matchWrap(top = 10))
        article.sections.forEach { section ->
            addDetailSection(content, section.title, section.paragraphs.joinToString("\n\n"))
        }
        content.addView(primaryButton("Open official PMC page") {
            openUrl("https://pmc.ncbi.nlm.nih.gov/articles/${article.pmcId}/")
        }, matchWrap(top = 18, bottom = 24))
        render(content)
    }

    private fun showTrials() {
        currentScreen = "trials"
        val content = verticalScreen("Clinical trials", "Search the EU CTIS public register. Enter terms yourself; no result is a recommendation.")
        val all = field("Contain all words")
        val any = field("Contain any words (optional)")
        val not = field("Exclude words (optional)")
        content.addView(all, matchWrap(top = 12)); content.addView(any, matchWrap(top = 9)); content.addView(not, matchWrap(top = 9))
        content.addView(primaryButton("Search clinical trials") {
            val term = all.text.toString().trim()
            if (term.isBlank()) { toast("Enter at least one search term"); return@primaryButton }
            loadTrialPage(term, any.text.toString(), not.text.toString(), 1)
        }, matchWrap(top = 12))
        content.addView(secondaryButton("Open EU CTIS advanced search") { openUrl("https://euclinicaltrials.eu/search-for-clinical-trials/") }, matchWrap(top = 10, bottom = 24))
        render(content)
    }

    private fun loadTrialPage(all: String, any: String, not: String, page: Int) {
        runBusy("Loading clinical-trials page $page…", { EUClinicalTrialsApi().search(all, page, any, not, 25) }) { response ->
            if (page == 1) store.append(DataFile.SEARCH_HISTORY, "searches", JSONObject().put("source", "eu_ctis")
                .put("query", all).put("containAny", any).put("containNot", not).put("searchedAt", timestamp())
                .put("resultCount", response.optJSONObject("pagination")?.optInt("totalRecords", 0) ?: 0))
            showTrialResults(all, any, not, response)
        }
    }

    private fun showTrialResults(all: String, any: String, not: String, response: JSONObject) {
        currentScreen = "trials-results"
        val pagination = response.optJSONObject("pagination") ?: JSONObject()
        val page = pagination.optInt("currentPage", 1).coerceAtLeast(1)
        val totalPages = pagination.optInt("totalPages", page).coerceAtLeast(page)
        val totalRecords = pagination.optInt("totalRecords", 0)
        val content = verticalScreen("Clinical trial results", "$totalRecords EU CTIS results for “$all” • Page $page of $totalPages")
        val candidates = response.optJSONArray("data") ?: response.optJSONArray("trials") ?: response.optJSONArray("content")
        if (candidates == null || candidates.length() == 0) {
            content.addView(emptyState("No displayable results", "The register returned no records, or changed its response format."), matchWrap(top = 12))
        } else {
            for (index in 0 until minOf(candidates.length(), 25)) {
                val trial = candidates.optJSONObject(index) ?: continue
                val title = firstText(trial, "ctTitle", "title", "fullTitle", "shortTitle", "trialTitle").ifBlank { "Clinical trial" }
                val number = firstText(trial, "ctNumber", "number", "euTrialNumber", "trialNumber")
                val condition = firstText(trial, "conditions", "medicalCondition")
                val phase = firstText(trial, "trialPhase", "phase")
                val sponsor = firstText(trial, "sponsor")
                val details = listOf(number, condition, phase, sponsor).filter { it.isNotBlank() }.joinToString("\n")
                content.addView(menuCard(title, details) { loadTrialDetails(trial) }, matchWrap(top = 9))
            }
        }
        content.addView(paginationControls(
            page,
            totalPages,
            pagination.optBoolean("prevPage", page > 1),
            pagination.optBoolean("nextPage", page < totalPages),
            previous = { loadTrialPage(all, any, not, page - 1) },
            next = { loadTrialPage(all, any, not, page + 1) }
        ), matchWrap(top = 16))
        content.addView(secondaryButton("New search") { showTrials() }, matchWrap(top = 9, bottom = 24))
        render(content)
    }

    private fun loadTrialDetails(summary: JSONObject) {
        val number = firstText(summary, "ctNumber", "number", "euTrialNumber", "trialNumber")
        if (number.isBlank()) { showError("Cannot load trial", IllegalStateException("The result has no CTIS trial number")); return }
        runBusy("Loading full CTIS record…", { EUClinicalTrialsApi().retrieve(number) }) { detail ->
            showTrialDetails(summary, detail)
        }
    }

    private fun showTrialDetails(summary: JSONObject, detail: JSONObject) {
        currentScreen = "trial-detail"
        val application = detail.optJSONObject("authorizedApplication") ?: JSONObject()
        val partI = application.optJSONObject("authorizedPartI") ?: JSONObject()
        val trialDetails = partI.optJSONObject("trialDetails") ?: JSONObject()
        val identifiers = trialDetails.optJSONObject("clinicalTrialIdentifiers") ?: JSONObject()
        val information = trialDetails.optJSONObject("trialInformation") ?: JSONObject()
        val title = firstText(identifiers, "publicTitle", "fullTitle", "shortTitle")
            .ifBlank { firstText(summary, "ctTitle", "shortTitle").ifBlank { "Clinical trial" } }
        val number = detail.optString("ctNumber", summary.optString("ctNumber"))
        val content = verticalScreen(title, "$number • ${detail.optString("ctStatus", "Status not published")}")

        val overview = linkedMapOf(
            "Short title" to firstText(identifiers, "shortTitle"),
            "Condition" to firstText(summary, "conditions", "medicalCondition"),
            "Phase" to firstText(summary, "trialPhase", "phase"),
            "Sponsor" to firstText(summary, "sponsor"),
            "Countries" to jsonList(summary.optJSONArray("trialCountries")),
            "Age group" to firstText(summary, "ageGroup"),
            "Gender" to firstText(summary, "gender"),
            "Planned enrollment" to firstText(summary, "totalNumberEnrolled"),
            "EU start date" to detail.optString("startDateEU"),
            "Estimated recruitment start" to information.optJSONObject("trialDuration")?.optString("estimatedRecruitmentStartDate").orEmpty(),
            "Estimated end date" to information.optJSONObject("trialDuration")?.optString("estimatedEndDate").orEmpty(),
            "Decision date" to detail.optString("decisionDate").substringBefore('T'),
            "Published" to detail.optString("publishDate").substringBefore('T'),
            "Trial region" to detail.optString("trialRegion")
        )
        addDetailSection(content, "Overview", overview.filterValues { it.isNotBlank() }.entries.joinToString("\n") { "${it.key}: ${it.value}" })

        val objective = information.optJSONObject("trialObjective") ?: JSONObject()
        val objectives = buildList {
            objective.optString("mainObjective").takeIf { it.isNotBlank() }?.let { add("Main objective\n$it") }
            arrayValues(objective.optJSONArray("secondaryObjectives"), "secondaryObjective").takeIf { it.isNotBlank() }?.let { add("Secondary objectives\n$it") }
        }.joinToString("\n\n")
        addDetailSection(content, "Study objectives", objectives)

        val eligibility = information.optJSONObject("eligibilityCriteria") ?: JSONObject()
        addDetailSection(content, "Who may be eligible", arrayValues(eligibility.optJSONArray("principalInclusionCriteria"), "principalInclusionCriteria"))
        addDetailSection(content, "Main exclusion criteria", arrayValues(eligibility.optJSONArray("principalExclusionCriteria"), "principalExclusionCriteria"))

        val endpoints = information.optJSONObject("endPoint") ?: JSONObject()
        addDetailSection(content, "Primary endpoints", arrayValues(endpoints.optJSONArray("primaryEndPoints"), "endPoint"))
        addDetailSection(content, "Secondary endpoints", arrayValues(endpoints.optJSONArray("secondaryEndPoints"), "endPoint"))

        val products = buildList {
            val items = partI.optJSONArray("products") ?: JSONArray()
            for (index in 0 until items.length()) {
                val product = items.optJSONObject(index) ?: continue
                val dictionary = product.optJSONObject("productDictionaryInfo") ?: JSONObject()
                val lines = listOf(
                    firstText(product, "productName"),
                    dictionary.optString("activeSubstanceName").takeIf { it.isNotBlank() }?.let { "Active substance: $it" }.orEmpty(),
                    jsonList(product.optJSONArray("routes")).takeIf { it.isNotBlank() }?.let { "Route: $it" }.orEmpty(),
                    firstText(product, "pharmaceuticalFormDisplay").takeIf { it.isNotBlank() }?.let { "Form: $it" }.orEmpty()
                ).filter { it.isNotBlank() }
                if (lines.isNotEmpty()) add(lines.joinToString("\n"))
            }
        }.joinToString("\n\n")
        addDetailSection(content, "Investigational products", products.ifBlank { firstText(summary, "product") })

        val sponsors = buildList {
            val items = partI.optJSONArray("sponsors") ?: JSONArray()
            for (index in 0 until items.length()) {
                val sponsor = items.optJSONObject(index) ?: continue
                val organisation = sponsor.optJSONObject("organisation") ?: JSONObject()
                val lines = mutableListOf(organisation.optString("name"), organisation.optString("type")).filter { it.isNotBlank() }.toMutableList()
                val contacts = sponsor.optJSONArray("publicContacts") ?: JSONArray()
                for (contactIndex in 0 until contacts.length()) {
                    val contact = contacts.optJSONObject(contactIndex) ?: continue
                    val contactLine = listOf(contact.optString("functionalName"), contact.optString("functionalEmailAddress"), contact.optString("telephone"))
                        .filter { it.isNotBlank() }.joinToString(" • ")
                    if (contactLine.isNotBlank()) lines += "Public contact: $contactLine"
                }
                if (lines.isNotEmpty()) add(lines.joinToString("\n"))
            }
        }.joinToString("\n\n")
        addDetailSection(content, "Sponsor and public contacts", sponsors)

        val locations = buildList {
            val partsII = application.optJSONArray("authorizedPartsII") ?: JSONArray()
            for (partIndex in 0 until partsII.length()) {
                val partII = partsII.optJSONObject(partIndex) ?: continue
                val country = partII.optJSONObject("mscInfo")?.optString("countryName").orEmpty()
                val sites = partII.optJSONArray("trialSites") ?: JSONArray()
                for (siteIndex in 0 until sites.length()) {
                    val site = sites.optJSONObject(siteIndex) ?: continue
                    val addressInfo = site.optJSONObject("organisationAddressInfo") ?: JSONObject()
                    val organisation = addressInfo.optJSONObject("organisation")?.optString("name").orEmpty()
                    val address = addressInfo.optJSONObject("address") ?: JSONObject()
                    val place = listOf(address.optString("city"), address.optString("postcode"), country).filter { it.isNotBlank() }.joinToString(", ")
                    add(listOf(organisation, site.optString("departmentName"), place).filter { it.isNotBlank() }.joinToString(" • "))
                }
            }
        }.filter { it.isNotBlank() }.distinct().joinToString("\n") { "• $it" }
        addDetailSection(content, "Trial sites", locations)

        val events = detail.optJSONObject("events") ?: JSONObject()
        val eventText = buildList {
            val countries = events.optJSONArray("trialEvents") ?: JSONArray()
            for (countryIndex in 0 until countries.length()) {
                val country = countries.optJSONObject(countryIndex) ?: continue
                val name = country.optString("mscName")
                val items = country.optJSONArray("events") ?: JSONArray()
                for (eventIndex in 0 until items.length()) {
                    val event = items.optJSONObject(eventIndex) ?: continue
                    add(listOf(name, event.optString("notificationType").replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }, event.optString("date"))
                        .filter { it.isNotBlank() }.joinToString(" • "))
                }
            }
        }.joinToString("\n") { "• $it" }
        addDetailSection(content, "Trial events", eventText)

        val documents = detail.optJSONArray("documents") ?: JSONArray()
        val documentText = (0 until documents.length()).mapNotNull { index ->
            documents.optJSONObject(index)?.let { doc ->
                listOf(doc.optString("title"), doc.optString("documentTypeLabel"), doc.optString("fileType"))
                    .filter { it.isNotBlank() }.joinToString(" • ")
            }
        }.distinct().joinToString("\n") { "• $it" }
        addDetailSection(content, "Public documents (${documents.length()})", documentText)

        val results = detail.optJSONObject("results") ?: JSONObject()
        if (results.length() > 0) addDetailSection(content, "Published results", results.toString(2))
        content.addView(primaryButton("Open official CTIS record") {
            openUrl("https://euclinicaltrials.eu/search-for-clinical-trials/?lang=en&EUCT=$number")
        }, matchWrap(top = 18))
        content.addView(secondaryButton("View complete API record") { showJsonDialog("Complete CTIS record", detail.toString(2)) }, matchWrap(top = 9, bottom = 24))
        render(content)
    }

    private fun showSettings() {
        currentScreen = "settings"
        val content = verticalScreen("Settings", "Manage the model connection and local private data.")
        val patient = store.activePatientProfile()
        content.addView(infoCard("Active patient profile", patient?.optString("name") ?: "No profile selected"), matchWrap(top = 12))
        content.addView(primaryButton("Manage and switch profiles") { showProfileManager() }, matchWrap(top = 10))
        content.addView(secondaryButton("Export full profile as ZIP") {
            val safeName = patient?.optString("name")?.replace(Regex("[^A-Za-z0-9_-]"), "_")?.ifBlank { "profile" } ?: "profile"
            profileExporter.launch("care-companion-$safeName-${today()}.zip")
        }, matchWrap(top = 9))
        val profile = store.activeProfile()
        content.addView(infoCard("Active LLM connection", if (profile == null) "Not configured" else "${profile.optString("name")}\n${profile.optString("model")}\n${profile.optString("baseUrl")}"), matchWrap(top = 18))
        content.addView(primaryButton("Edit LLM profile") { showSetup(firstLaunch = false) }, matchWrap(top = 12))
        val settings = JSONObject(store.read(DataFile.SETTINGS))
        val limit = field("Maximum tracker questions (1–50)", settings.optInt("questionLimit", 12).toString(), number = true)
        content.addView(limit, matchWrap(top = 18))
        content.addView(secondaryButton("Save tracker limit") {
            try {
                val value = limit.text.toString().toInt()
                require(value in 1..50) { "Limit must be from 1 to 50" }
                store.write(DataFile.SETTINGS, JSONObject().put("questionLimit", value).toString(2)); toast("Tracker limit saved")
            } catch (error: Exception) { showError("Could not save setting", error) }
        }, matchWrap(top = 9))
        content.addView(infoCard("Private JSON storage", store.dataDirectoryPath()), matchWrap(top = 18))
        content.addView(dangerButton("Delete this profile") {
            AlertDialog.Builder(this).setTitle("Delete ${patient?.optString("name") ?: "profile"}?")
                .setMessage("This permanently deletes this profile's medical data, documents, chat, questionnaire and history, settings, and research searches. Export it first if you may need a copy.")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete profile") { _, _ ->
                    store.deleteActivePatientProfile(); toast("Profile deleted")
                    if (store.activePatientProfile() == null) showProfileManager() else showMain()
                }.show()
        }, matchWrap(top = 12, bottom = 24))
        render(content)
    }

    private fun showProfileManager() {
        currentScreen = "profiles"
        val activeId = store.activePatientProfile()?.optString("id")
        val profiles = store.patientProfiles()
        val content = verticalScreen("Profiles", "Every profile has separate medical records, documents, chat, questionnaire history, settings, and research history.")
        for (index in 0 until profiles.length()) {
            val profile = profiles.optJSONObject(index) ?: continue
            val active = profile.optString("id") == activeId
            content.addView(menuCard(
                profile.optString("name"),
                if (active) "Active profile" else "Tap to switch to this profile"
            ) {
                store.switchPatientProfile(profile.getString("id")); toast("Switched to ${profile.optString("name")}"); showMain()
            }, matchWrap(top = 9))
        }
        val name = field("New profile name")
        content.addView(name, matchWrap(top = 20))
        content.addView(primaryButton("Create profile") {
            try {
                val profile = store.createPatientProfile(required(name, "Profile name"))
                toast("Created ${profile.optString("name")}")
                showImport()
            } catch (error: Exception) { showError("Could not create profile", error) }
        }, matchWrap(top = 9))
        if (activeId != null) content.addView(secondaryButton("Back to active profile") { showMain() }, matchWrap(top = 9, bottom = 24))
        render(content)
    }

    private fun <T> runBusy(message: String?, work: () -> T, success: (T) -> Unit) {
        val dialog = message?.let {
            AlertDialog.Builder(this).setMessage(it).setView(ProgressBar(this)).setCancelable(false).create().apply { show() }
        }
        executor.execute {
            try {
                val result = work()
                runOnUiThread { dialog?.dismiss(); success(result) }
            } catch (error: Exception) {
                runOnUiThread { dialog?.dismiss(); showError("Something went wrong", error) }
            }
        }
    }

    private fun verticalScreen(title: String, subtitle: String): LinearLayout {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(36))
            addView(TextView(this@MainActivity).apply {
                text = title; textSize = 29f; setTextColor(color(R.color.text_primary)); setTypeface(typeface, Typeface.BOLD)
            }, matchWrap())
            addView(paragraph(subtitle), matchWrap(top = 7, bottom = 5))
        }
        return body
    }

    private fun render(content: LinearLayout) {
        host.removeAllViews()
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content, matchWrap()) }
        host.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun field(hint: String, value: String = "", password: Boolean = false, decimal: Boolean = false, number: Boolean = false, multiline: Boolean = false) =
        EditText(this).apply {
            this.hint = hint; setText(value); textSize = 16f; setTextColor(color(R.color.text_primary)); setHintTextColor(color(R.color.text_secondary))
            background = rounded(color(R.color.surface), 14, color(R.color.primary_soft), 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minHeight = dp(if (multiline) 84 else 52)
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                decimal -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                number -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (multiline) { gravity = Gravity.TOP; minLines = 3 }
        }

    private fun label(value: String) = paragraph(value).apply { setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(10), 0, dp(4)) }
    private fun paragraph(value: String) = TextView(this).apply { text = value; textSize = 15f; setTextColor(color(R.color.text_secondary)); setLineSpacing(0f, 1.15f) }
    private fun disclaimer() = paragraph(getString(R.string.medical_disclaimer)).apply { textSize = 13f }

    private fun primaryButton(text: String, action: () -> Unit) = button(text, color(R.color.primary), Color.WHITE, action)
    private fun secondaryButton(text: String, action: () -> Unit) = button(text, color(R.color.primary_soft), color(R.color.primary_dark), action)
    private fun dangerButton(text: String, action: () -> Unit) = button(text, 0xFFFFE4E1.toInt(), color(R.color.warning), action)
    private fun button(value: String, backgroundColor: Int, textColor: Int, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; textSize = 16f; setTextColor(textColor); setTypeface(typeface, Typeface.BOLD)
        background = rounded(backgroundColor, 14); minHeight = dp(54); setOnClickListener { action() }
    }

    private fun menuCard(title: String, subtitle: String, action: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(17), dp(18), dp(17)); background = rounded(color(R.color.surface), 20)
            elevation = dp(2).toFloat(); isClickable = true; isFocusable = true; setOnClickListener { action() }
            addView(TextView(this@MainActivity).apply { text = title; textSize = 19f; setTextColor(color(R.color.primary_dark)); setTypeface(typeface, Typeface.BOLD) })
            addView(paragraph(subtitle), matchWrap(top = 4))
        }
    }

    private fun paginationControls(
        page: Int,
        totalPages: Int,
        hasPrevious: Boolean,
        hasNext: Boolean,
        previous: () -> Unit,
        next: () -> Unit
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val previousButton = secondaryButton("Previous") { previous() }.apply { isEnabled = hasPrevious; alpha = if (hasPrevious) 1f else 0.45f }
        val nextButton = secondaryButton("Next") { next() }.apply { isEnabled = hasNext; alpha = if (hasNext) 1f else 0.45f }
        addView(previousButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@MainActivity).apply {
            text = "$page / ${totalPages.coerceAtLeast(1)}"; gravity = Gravity.CENTER; textSize = 15f; setTextColor(color(R.color.text_secondary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.65f))
        addView(nextButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun infoCard(title: String, body: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(15), dp(16), dp(15)); background = rounded(color(R.color.surface), 18)
        addView(TextView(this@MainActivity).apply { text = title; textSize = 17f; setTextColor(color(R.color.text_primary)); setTypeface(typeface, Typeface.BOLD) })
        addView(paragraph(body.ifBlank { "No additional details" }), matchWrap(top = 5))
    }

    private fun emptyState(title: String, body: String) = infoCard(title, body)
    private fun addDetailSection(content: LinearLayout, title: String, body: String) {
        if (body.isNotBlank()) content.addView(infoCard(title, body), matchWrap(top = 10))
    }

    private fun jsonList(values: JSONArray?): String {
        if (values == null) return ""
        return (0 until values.length()).mapNotNull { index ->
            when (val value = values.opt(index)) {
                is String -> value.takeIf { it.isNotBlank() }
                is JSONObject -> firstText(value, "name", "title", "value").takeIf { it.isNotBlank() }
                else -> value?.toString()
            }
        }.joinToString(", ")
    }

    private fun arrayValues(values: JSONArray?, field: String): String {
        if (values == null) return ""
        return (0 until values.length()).mapNotNull { index ->
            values.optJSONObject(index)?.optString(field)?.takeIf { it.isNotBlank() }
        }.joinToString("\n") { "• $it" }
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null, strokeWidth: Int = 0) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(fill); cornerRadius = dp(radius).toFloat(); if (stroke != null) setStroke(dp(strokeWidth), stroke)
    }

    private fun prettyMedical(root: JSONObject): String {
        val lines = mutableListOf<String>()
        fun section(name: String, key: String, field: String = "name") {
            val items = root.optJSONArray(key) ?: return
            if (items.length() > 0) {
                lines += name.uppercase()
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i)
                    lines += "• " + (item?.optString(field)?.ifBlank { item.toString() } ?: items.optString(i))
                }
                lines += ""
            }
        }
        section("Diseases", "diseases"); section("Treatments", "treatments"); section("Medications", "medications")
        section("Procedures", "procedures"); section("Allergies", "allergies")
        val summary = root.optString("sourceSummary")
        if (summary.isNotBlank()) { lines += "RECORD SUMMARY"; lines += summary }
        return lines.joinToString("\n").ifBlank { "The LLM returned structured data without summary fields. Use “View structured JSON” for details." }
    }

    private fun parseJsonObject(value: String): JSONObject {
        var cleaned = value.trim()
        if (cleaned.startsWith("```")) cleaned = cleaned.removePrefix("```json").removePrefix("```").substringBeforeLast("```").trim()
        return JSONObject(cleaned)
    }

    private fun validateMedicalAgainstSchema(value: JSONObject, schema: JSONObject) {
        if (schema.length() == 0) return
        validateSchemaValue(value, schema, "$")
    }

    private fun validateSchemaValue(value: Any?, schema: JSONObject, path: String) {
        val allowedTypes = when (val type = schema.opt("type")) {
            is String -> listOf(type)
            is JSONArray -> (0 until type.length()).map { type.optString(it) }
            else -> emptyList()
        }
        val isNull = value == null || value == JSONObject.NULL
        if (isNull) {
            require(allowedTypes.isEmpty() || "null" in allowedTypes) { "$path may not be null" }
            return
        }
        if (allowedTypes.isNotEmpty()) {
            val valid = allowedTypes.any { type ->
                when (type) {
                    "object" -> value is JSONObject
                    "array" -> value is JSONArray
                    "string" -> value is String
                    "number" -> value is Number
                    "integer" -> value is Number && value.toDouble() % 1.0 == 0.0
                    "boolean" -> value is Boolean
                    else -> true
                }
            }
            require(valid) { "$path does not match schema type ${allowedTypes.joinToString("|")}" }
        }
        val enumValues = schema.optJSONArray("enum")
        if (enumValues != null) {
            require((0 until enumValues.length()).any { enumValues.opt(it)?.toString() == value.toString() }) {
                "$path is not one of the allowed values"
            }
        }
        if (value is JSONObject) {
            val required = schema.optJSONArray("required") ?: JSONArray()
            for (index in 0 until required.length()) {
                val key = required.optString(index)
                require(value.has(key)) { "$path is missing required property '$key'" }
            }
            val properties = schema.optJSONObject("properties") ?: JSONObject()
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val childSchema = properties.optJSONObject(key)
                if (childSchema != null) {
                    validateSchemaValue(value.opt(key), childSchema, "$path.$key")
                } else if (schema.opt("additionalProperties") == false) {
                    error("$path contains unsupported property '$key'")
                }
            }
        } else if (value is JSONArray) {
            val itemSchema = schema.optJSONObject("items") ?: return
            for (index in 0 until value.length()) validateSchemaValue(value.opt(index), itemSchema, "$path[$index]")
        }
    }

    private fun firstText(json: JSONObject, vararg keys: String): String {
        keys.forEach { key ->
            val value = json.opt(key)
            if (value is String && value.isNotBlank()) return value
            if (value is JSONObject) {
                listOf("en", "value", "name", "title").forEach { nested -> value.optString(nested).takeIf { it.isNotBlank() }?.let { return it } }
            }
        }
        return ""
    }

    private fun showJsonDialog(title: String, json: String) {
        val view = TextView(this).apply { text = json; setTextIsSelectable(true); setPadding(dp(18), dp(12), dp(18), dp(12)); setTextColor(color(R.color.text_primary)) }
        AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(view) }).setPositiveButton("Close", null).show()
    }

    private fun showError(title: String, error: Exception) {
        AlertDialog.Builder(this).setTitle(title).setMessage(error.message ?: error.javaClass.simpleName).setPositiveButton("OK", null).show()
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (error: Exception) { showError("Could not open link", error) }
    }

    private fun required(edit: EditText, name: String): String = edit.text.toString().trim().also { require(it.isNotBlank()) { "$name is required" } }
    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())
    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun dateDaysAgo(days: Int): String {
        val calendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days.coerceAtLeast(0)) }
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun matchWrap(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(top); bottomMargin = dp(bottom)
    }
}
