package com.example.cancerapp.llm;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/** Provider-neutral client for OpenAI-compatible Chat Completions APIs. */
public final class RemoteLlmClient {
    public static final class Config {
        public String baseUrl;
        public String apiKey;
        public String model;
        public double temperature = 0.2;
        public int maxTokens = 1200;
        public int timeoutSeconds = 60;
        public String extraHeadersJson = "{}";

        public static Config fromJson(JSONObject json) {
            Config config = new Config();
            config.baseUrl = json.optString("baseUrl");
            config.apiKey = json.optString("apiKey");
            config.model = json.optString("model");
            config.temperature = json.optDouble("temperature", 0.2);
            config.maxTokens = json.optInt("maxTokens", 1200);
            config.timeoutSeconds = json.optInt("timeoutSeconds", 60);
            config.extraHeadersJson = json.optString("extraHeaders", "{}");
            return config;
        }
    }

    public String complete(Config config, String systemPrompt, String userPrompt) throws IOException, JSONException {
        validate(config);
        JSONObject body = new JSONObject();
        body.put("model", config.model);
        body.put("temperature", config.temperature);
        body.put("max_tokens", config.maxTokens);
        body.put("stream", false);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
        messages.put(new JSONObject().put("role", "user").put("content", userPrompt));
        body.put("messages", messages);

        URL endpoint = URI.create(normalizeEndpoint(config.baseUrl)).toURL();
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(config.timeoutSeconds * 1000);
        connection.setReadTimeout(config.timeoutSeconds * 1000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        if (config.apiKey != null && !config.apiKey.isBlank()) {
            connection.setRequestProperty("Authorization", "Bearer " + config.apiKey.trim());
        }
        JSONObject headers = new JSONObject(config.extraHeadersJson == null || config.extraHeadersJson.isBlank() ? "{}" : config.extraHeadersJson);
        Iterator<String> headerKeys = headers.keys();
        while (headerKeys.hasNext()) {
            String key = headerKeys.next();
            connection.setRequestProperty(key, headers.getString(key));
        }

        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        String response = read(status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream());
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new IOException("LLM server returned HTTP " + status + ": " + safeError(response));
        }
        JSONObject root = new JSONObject(response);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new IOException("LLM response did not contain choices");
        JSONObject message = choices.getJSONObject(0).optJSONObject("message");
        if (message == null) throw new IOException("LLM response did not contain a message");
        String content = message.optString("content", "").trim();
        if (content.isEmpty()) throw new IOException("LLM returned an empty response");
        return content;
    }

    private static void validate(Config config) {
        if (config == null) throw new IllegalArgumentException("LLM configuration is missing");
        if (config.baseUrl == null || config.baseUrl.isBlank()) throw new IllegalArgumentException("LLM URL is required");
        URI uri = URI.create(config.baseUrl.trim());
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            throw new IllegalArgumentException("LLM URL must start with http:// or https://");
        }
        if (config.model == null || config.model.isBlank()) throw new IllegalArgumentException("Model is required");
        if (config.temperature < 0 || config.temperature > 2) throw new IllegalArgumentException("Temperature must be between 0 and 2");
    }

    private static String normalizeEndpoint(String value) {
        String url = value.trim().replaceAll("/+$", "");
        if (url.endsWith("/chat/completions")) return url;
        if (url.endsWith("/v1")) return url + "/chat/completions";
        return url + "/v1/chat/completions";
    }

    private static String read(InputStream input) throws IOException {
        if (input == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private static String safeError(String response) {
        if (response == null || response.isBlank()) return "No response body";
        try {
            JSONObject json = new JSONObject(response);
            JSONObject error = json.optJSONObject("error");
            if (error != null) return error.optString("message", "Request failed");
        } catch (JSONException ignored) { }
        return response.length() > 240 ? response.substring(0, 240) + "…" : response;
    }
}
