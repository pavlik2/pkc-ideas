package com.example.cancerapp.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class JsonFileStore(context: Context) {
    private val directory = File(context.filesDir, "care_data").apply { mkdirs() }
    private val profileDirectory = File(directory, "profiles").apply { mkdirs() }

    enum class DataFile(val fileName: String, val emptyValue: String, val global: Boolean = false) {
        LLM_PROFILES("llm_profiles.json", "{\"profiles\":[],\"activeProfileId\":null}", true),
        PATIENT_PROFILES("patient_profiles.json", "{\"profiles\":[],\"activeProfileId\":null}", true),
        MEDICAL_DATA("processed_medical_data.json", "{}"),
        EXTRACTION_SCHEMA("medical_extraction_schema.json", "{}"),
        DOCUMENTS("imported_documents.json", "{\"documents\":[]}"),
        QUESTIONNAIRE("questionnaire.json", "{\"questions\":[]}"),
        QUESTIONNAIRE_HISTORY("questionnaire_history.json", "{\"entries\":[]}"),
        CHAT_HISTORY("chat_history.json", "{\"messages\":[]}"),
        SEARCH_HISTORY("search_history.json", "{\"searches\":[]}"),
        SETTINGS("settings.json", "{\"questionLimit\":12}")
    }

    init { migrateLegacyPatientData() }

    @Synchronized
    fun read(type: DataFile): String {
        val file = resolve(type)
        if (!file.exists()) write(type, type.emptyValue)
        return file.readText(Charsets.UTF_8)
    }

    @Synchronized
    fun write(type: DataFile, json: String) {
        validate(json)
        val destination = resolve(type)
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${type.fileName}.tmp")
        temporary.writeText(json, Charsets.UTF_8)
        if (!temporary.renameTo(destination)) {
            destination.writeText(json, Charsets.UTF_8)
            temporary.delete()
        }
    }

    @Synchronized
    fun append(type: DataFile, arrayName: String, value: JSONObject) {
        val root = JSONObject(read(type))
        val items = root.optJSONArray(arrayName) ?: JSONArray().also { root.put(arrayName, it) }
        items.put(value)
        write(type, root.toString(2))
    }

    @Synchronized
    fun upsert(type: DataFile, arrayName: String, key: String, value: JSONObject) {
        val root = JSONObject(read(type))
        val items = root.optJSONArray(arrayName) ?: JSONArray().also { root.put(arrayName, it) }
        var replaced = false
        for (index in 0 until items.length()) {
            if (items.optJSONObject(index)?.optString(key) == value.optString(key)) {
                items.put(index, value); replaced = true; break
            }
        }
        if (!replaced) items.put(value)
        write(type, root.toString(2))
    }

    fun hasActiveProfile(): Boolean {
        val root = JSONObject(read(DataFile.LLM_PROFILES))
        return root.optString("activeProfileId").isNotBlank() && (root.optJSONArray("profiles")?.length() ?: 0) > 0
    }

    fun activeProfile(): JSONObject? = findActive(DataFile.LLM_PROFILES)

    fun patientProfiles(): JSONArray = JSONObject(read(DataFile.PATIENT_PROFILES)).optJSONArray("profiles") ?: JSONArray()

    fun activePatientProfile(): JSONObject? = findActive(DataFile.PATIENT_PROFILES)

    fun createPatientProfile(name: String): JSONObject {
        require(name.trim().isNotBlank()) { "Profile name is required" }
        val root = JSONObject(read(DataFile.PATIENT_PROFILES))
        val profiles = root.optJSONArray("profiles") ?: JSONArray().also { root.put("profiles", it) }
        val profile = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("name", name.trim())
            .put("createdAt", System.currentTimeMillis())
        profiles.put(profile)
        root.put("activeProfileId", profile.getString("id"))
        write(DataFile.PATIENT_PROFILES, root.toString(2))
        initializeProfile(profile.getString("id"))
        return profile
    }

    fun switchPatientProfile(id: String) {
        val root = JSONObject(read(DataFile.PATIENT_PROFILES))
        val profiles = root.optJSONArray("profiles") ?: JSONArray()
        require((0 until profiles.length()).any { profiles.optJSONObject(it)?.optString("id") == id }) { "Profile not found" }
        root.put("activeProfileId", id)
        write(DataFile.PATIENT_PROFILES, root.toString(2))
        initializeProfile(id)
    }

    fun deleteActivePatientProfile() {
        val root = JSONObject(read(DataFile.PATIENT_PROFILES))
        val activeId = root.optString("activeProfileId")
        require(activeId.isNotBlank()) { "No active profile" }
        val existing = root.optJSONArray("profiles") ?: JSONArray()
        val remaining = JSONArray()
        for (index in 0 until existing.length()) {
            val profile = existing.optJSONObject(index) ?: continue
            if (profile.optString("id") != activeId) remaining.put(profile)
        }
        deleteRecursively(File(profileDirectory, safeId(activeId)))
        root.put("profiles", remaining)
        root.put("activeProfileId", if (remaining.length() > 0) remaining.getJSONObject(0).getString("id") else JSONObject.NULL)
        write(DataFile.PATIENT_PROFILES, root.toString(2))
    }

    fun exportActiveProfile(output: OutputStream) {
        val profile = activePatientProfile() ?: error("No active patient profile")
        val id = profile.getString("id")
        initializeProfile(id)
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("profile.json"))
            zip.write(profile.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            DataFile.entries.filter { !it.global }.forEach { type ->
                val file = File(profileDirectory, "${safeId(id)}/${type.fileName}")
                if (file.exists()) {
                    zip.putNextEntry(ZipEntry(type.fileName))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    fun clearPatientData() {
        DataFile.entries.filter { !it.global }.forEach { write(it, it.emptyValue) }
    }

    fun dataDirectoryPath(): String = activePatientProfile()?.optString("id")
        ?.let { File(profileDirectory, safeId(it)).absolutePath } ?: profileDirectory.absolutePath

    private fun findActive(type: DataFile): JSONObject? {
        val root = JSONObject(read(type))
        val id = root.optString("activeProfileId")
        val profiles = root.optJSONArray("profiles") ?: return null
        for (index in 0 until profiles.length()) {
            val profile = profiles.optJSONObject(index)
            if (profile?.optString("id") == id) return profile
        }
        return null
    }

    private fun resolve(type: DataFile): File {
        if (type.global) return File(directory, type.fileName)
        val id = activePatientProfile()?.optString("id")
            ?: throw IllegalStateException("Create or select a patient profile first")
        return File(profileDirectory, "${safeId(id)}/${type.fileName}")
    }

    private fun initializeProfile(id: String) {
        val folder = File(profileDirectory, safeId(id)).apply { mkdirs() }
        DataFile.entries.filter { !it.global }.forEach { type ->
            val file = File(folder, type.fileName)
            if (!file.exists()) file.writeText(type.emptyValue, Charsets.UTF_8)
        }
    }

    private fun migrateLegacyPatientData() {
        val index = File(directory, DataFile.PATIENT_PROFILES.fileName)
        if (index.exists()) return
        val legacyTypes = DataFile.entries.filter { !it.global }
        val hasLegacy = legacyTypes.any { File(directory, it.fileName).exists() }
        val profile = createPatientProfile(if (hasLegacy) "My profile" else "Default profile")
        if (hasLegacy) {
            val folder = File(profileDirectory, safeId(profile.getString("id")))
            legacyTypes.forEach { type ->
                val source = File(directory, type.fileName)
                if (source.exists()) {
                    source.copyTo(File(folder, type.fileName), overwrite = true)
                    source.delete()
                }
            }
        }
    }

    private fun safeId(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "")

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach(::deleteRecursively)
        file.delete()
    }

    private fun validate(json: String) {
        val trimmed = json.trim()
        if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
    }
}
