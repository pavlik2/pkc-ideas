package com.example.cancerapp.network

import org.json.JSONArray
import org.json.JSONObject
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class PubMedApi {
    enum class SearchMode(val label: String, val database: String, val suffix: String) {
        ALL("PubMed citations", "pubmed", ""),
        ABSTRACTS("PubMed abstracts", "pubmed", "hasabstract"),
        PMC_FULL_TEXT("PMC full-text body", "pmc", "")
    }

    data class Paper(
        val id: String,
        val title: String,
        val journal: String,
        val date: String,
        val authors: String,
        val abstractText: String,
        val pmcId: String?
    )
    data class FullTextArticle(
        val pmcId: String,
        val sections: List<Section>,
        val licenseNotice: String
    ) {
        data class Section(val title: String, val paragraphs: List<String>)
    }
    data class SearchPage(
        val papers: List<Paper>,
        val page: Int,
        val pageSize: Int,
        val totalRecords: Int,
        val totalPages: Int
    ) {
        val hasPrevious: Boolean get() = page > 1
        val hasNext: Boolean get() = page < totalPages && page * pageSize < 10_000
    }

    fun search(query: String, page: Int = 1, pageSize: Int = 10, mode: SearchMode = SearchMode.ALL): SearchPage {
        require(query.isNotBlank()) { "Enter a PubMed search term" }
        val safePage = page.coerceAtLeast(1)
        val safeSize = pageSize.coerceIn(1, 25)
        val start = (safePage - 1) * safeSize
        require(start < 10_000) { "PubMed exposes only the first 10,000 search results. Narrow the search to continue." }
        val effectiveQuery = if (mode.suffix.isBlank()) query.trim() else "(${query.trim()}) AND ${mode.suffix}"
        val encoded = URLEncoder.encode(effectiveQuery, StandardCharsets.UTF_8.name())
        val searchUrl = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi?db=${mode.database}&retmode=json&retstart=$start&retmax=$safeSize&sort=relevance&term=$encoded"
        val searchResult = JSONObject(get(searchUrl)).getJSONObject("esearchresult")
        val total = searchResult.optString("count", "0").toIntOrNull() ?: 0
        val totalPages = if (total == 0) 0 else ((minOf(total, 10_000) + safeSize - 1) / safeSize)
        val ids = searchResult.getJSONArray("idlist")
        if (ids.length() == 0) return SearchPage(emptyList(), safePage, safeSize, total, totalPages)
        val joined = (0 until ids.length()).joinToString(",") { ids.getString(it) }
        val summaryUrl = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esummary.fcgi?db=${mode.database}&retmode=json&id=$joined"
        val result = JSONObject(get(summaryUrl)).getJSONObject("result")
        val summaries = (0 until ids.length()).mapNotNull { index ->
            result.optJSONObject(ids.getString(index))
        }
        fun articleId(summary: JSONObject, vararg types: String): String? {
            val allowed = types.toSet()
            return summary.optJSONArray("articleids")?.let { articleIds ->
                (0 until articleIds.length()).mapNotNull { articleIds.optJSONObject(it) }
                    .firstOrNull { article -> article.optString("idtype") in allowed }?.optString("value")
            }?.takeIf(String::isNotBlank)
        }
        val pmids = summaries.mapNotNull { summary ->
            if (mode.database == "pubmed") summary.optString("uid").takeIf(String::isNotBlank)
            else articleId(summary, "pmid")
        }
        val abstracts = if (pmids.isEmpty()) emptyMap() else fetchAbstracts(pmids.joinToString(","))
        val papers = summaries.map { summary ->
                val pmid = if (mode.database == "pubmed") summary.optString("uid") else articleId(summary, "pmid").orEmpty()
                val pmcId = articleId(summary, "pmc", "pmcid")
                val authors = summary.optJSONArray("authors")?.let { array ->
                    (0 until array.length()).mapNotNull { authorIndex -> array.optJSONObject(authorIndex)?.optString("name")?.takeIf(String::isNotBlank) }.joinToString(", ")
                }.orEmpty()
                Paper(pmid, summary.optString("title", "Untitled"), summary.optString("fulljournalname"), summary.optString("pubdate"),
                    authors, abstracts[pmid].orEmpty(), pmcId)
        }
        return SearchPage(papers, safePage, safeSize, total, totalPages)
    }

    private fun fetchAbstracts(ids: String): Map<String, String> {
        val xml = get("https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=pubmed&retmode=xml&id=$ids")
        val parser = Xml.newPullParser().apply { setInput(xml.reader()) }
        val result = linkedMapOf<String, String>()
        var currentId: String? = null
        val abstractParts = mutableListOf<String>()
        var abstractDepth = 0
        var abstractLabel = ""
        var abstractText: StringBuilder? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                if (abstractDepth > 0) {
                    abstractDepth++
                } else when (parser.name) {
                    "PubmedArticle" -> { currentId = null; abstractParts.clear() }
                    "PMID" -> if (currentId == null) currentId = parser.nextText().trim()
                    "AbstractText" -> {
                        abstractDepth = 1
                        abstractLabel = parser.getAttributeValue(null, "Label").orEmpty()
                        abstractText = StringBuilder()
                    }
                }
            } else if (event == XmlPullParser.TEXT && abstractDepth > 0) {
                abstractText?.append(parser.text)
            } else if (event == XmlPullParser.END_TAG) {
                if (abstractDepth > 0) {
                    abstractDepth--
                    if (abstractDepth == 0) {
                        val value = cleanText(abstractText.toString())
                        if (value.isNotBlank()) abstractParts += if (abstractLabel.isBlank()) value else "$abstractLabel: $value"
                        abstractText = null
                    }
                } else if (parser.name == "PubmedArticle") {
                    currentId?.let { result[it] = abstractParts.joinToString("\n\n") }
                }
            }
            event = parser.next()
        }
        return result
    }

    fun fetchPmcFullText(pmcId: String): FullTextArticle {
        require(pmcId.matches(Regex("PMC\\d+", RegexOption.IGNORE_CASE))) { "A valid PMC identifier is required" }
        val numericId = pmcId.removePrefix("PMC").removePrefix("pmc")
        val xml = get("https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=pmc&id=$numericId&retmode=xml")
        val parser = Xml.newPullParser().apply { setInput(xml.reader()) }
        val sections = mutableListOf<FullTextArticle.Section>()
        var inBody = false
        var bodyDepth = 0
        var captureTag: String? = null
        var captureDepth = 0
        var capture = StringBuilder()
        var sectionTitle = "Article"
        val paragraphs = mutableListOf<String>()
        var licenseDepth = 0
        var licenseText = StringBuilder()

        fun flushSection() {
            if (paragraphs.isNotEmpty()) {
                sections += FullTextArticle.Section(sectionTitle, paragraphs.toList())
                paragraphs.clear()
            }
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "license-p" && licenseDepth == 0) {
                        licenseDepth = 1
                        licenseText = StringBuilder()
                    } else if (licenseDepth > 0) {
                        licenseDepth++
                    }
                    if (parser.name == "body" && !inBody) {
                        inBody = true
                        bodyDepth = 1
                    } else if (inBody) {
                        bodyDepth++
                        if (captureTag == null && (parser.name == "title" || parser.name == "p")) {
                            captureTag = parser.name
                            captureDepth = 1
                            capture = StringBuilder()
                        } else if (captureTag != null) {
                            captureDepth++
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (captureTag != null) capture.append(parser.text)
                    if (licenseDepth > 0) licenseText.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    if (captureTag != null) {
                        captureDepth--
                        if (captureDepth == 0) {
                            val value = cleanText(capture.toString())
                            if (captureTag == "title" && value.isNotBlank()) {
                                flushSection()
                                sectionTitle = value
                            } else if (captureTag == "p" && value.isNotBlank()) {
                                paragraphs += value
                            }
                            captureTag = null
                        }
                    }
                    if (licenseDepth > 0) licenseDepth--
                    if (inBody) {
                        bodyDepth--
                        if (parser.name == "body" && bodyDepth == 0) {
                            inBody = false
                            flushSection()
                        }
                    }
                }
            }
            event = parser.next()
        }
        require(sections.isNotEmpty()) { "PMC did not return readable article body text for $pmcId" }
        return FullTextArticle(pmcId.uppercase(), sections, cleanText(licenseText.toString()))
    }

    private fun cleanText(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private fun get(url: String): String {
        val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/xml, application/json")
        connection.setRequestProperty("User-Agent", "CareCompanionAndroid/1.0 (PubMed patient research tool)")
        return connection.run {
            try {
                val status = responseCode
                val body = (if (status in 200..299) inputStream else errorStream).bufferedReader().use { it.readText() }
                if (status !in 200..299) error("PubMed returned HTTP $status")
                body
            } finally { disconnect() }
        }
    }
}
