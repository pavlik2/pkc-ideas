package com.example.cancerapp.network;

import org.json.JSONObject;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public final class EUClinicalTrialsApi {
    private static final String ENDPOINT = "https://euclinicaltrials.eu/ctis-public-api/search";
    private static final String BASE_URL = "https://euclinicaltrials.eu/ctis-public-api";

    public JSONObject search(String containAll, int page, String containAny, String containNot, int size) throws IOException, JSONException {
        JSONObject pagination = new JSONObject().put("page", Math.max(1, page)).put("size", Math.max(1, Math.min(size, 50)));
        JSONObject sort = new JSONObject().put("property", "decisionDate").put("direction", "DESC");
        JSONObject criteria = new JSONObject()
                .put("containAll", safe(containAll)).put("containAny", safe(containAny)).put("containNot", safe(containNot));
        String[] nullableFields = {"title", "number", "status", "medicalCondition", "sponsor", "endPoint", "productName",
                "productRole", "populationType", "orphanDesignation", "msc", "ageGroupCode", "therapeuticAreaCode",
                "trialPhaseCode", "sponsorTypeCode", "gender", "eeaStartDateFrom", "eeaStartDateTo", "eeaEndDateFrom",
                "eeaEndDateTo", "protocolCode", "rareDisease", "pip", "haveOrphanDesignation", "hasStudyResults",
                "hasClinicalStudyReport", "isLowIntervention", "hasSeriousBreach", "hasUnexpectedEvent",
                "hasUrgentSafetyMeasure", "isTransitioned", "eudraCtCode", "trialRegion", "vulnerablePopulation", "mscStatus"};
        for (String field : nullableFields) criteria.put(field, JSONObject.NULL);
        JSONObject payload = new JSONObject().put("pagination", pagination).put("sort", sort).put("searchCriteria", criteria);

        HttpURLConnection connection = (HttpURLConnection) URI.create(ENDPOINT).toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(25_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; CareCompanionAndroid/1.0)");
        try (OutputStream output = connection.getOutputStream()) {
            output.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        String body = read(status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream());
        connection.disconnect();
        if (status < 200 || status >= 300) throw new IOException("EU CTIS returned HTTP " + status);
        return new JSONObject(body);
    }

    /** Loads the public Part I/II application, events, results and documents for one CTIS trial. */
    public JSONObject retrieve(String ctNumber) throws IOException, JSONException {
        if (ctNumber == null || !ctNumber.matches("[0-9-]+")) {
            throw new IllegalArgumentException("A valid CTIS trial number is required");
        }
        HttpURLConnection connection = (HttpURLConnection) URI.create(BASE_URL + "/retrieve/" + ctNumber).toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; CareCompanionAndroid/1.0)");
        int status = connection.getResponseCode();
        String body = read(status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream());
        connection.disconnect();
        if (status == 404) throw new IOException("The trial is no longer available in CTIS");
        if (status < 200 || status >= 300) throw new IOException("EU CTIS returned HTTP " + status);
        return new JSONObject(body);
    }

    private static String read(InputStream input) throws IOException {
        if (input == null) return "{}";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }
}
