package com.example.cancerapp.llm

object PromptTemplates {
    fun medicalExtraction(customSchema: String? = null): String {
        val base = """
        You extract structured facts from patient-provided medical records. Never infer a diagnosis or fact that is not explicitly present. Return JSON only, with no Markdown.
        Use this exact top-level schema:
        {
          "patient": {"name": null, "dateOfBirth": null},
          "diseases": [{"name": "", "status": "", "diagnosedDate": null, "sourceEvidence": ""}],
          "treatments": [{"name": "", "type": "", "startDate": null, "endDate": null, "status": "", "sourceEvidence": ""}],
          "medications": [{"name": "", "dose": null, "frequency": null, "route": null, "status": "", "sourceEvidence": ""}],
          "procedures": [], "allergies": [], "testResults": [], "careTeam": [],
          "uncertainties": [], "sourceSummary": ""
        }
        Preserve uncertainty and conflicting statements. Use null for missing values. Do not add medical advice.
        """.trimIndent()
        if (customSchema.isNullOrBlank() || customSchema.trim() == "{}") return base
        return """
            You extract structured facts from patient-provided medical records. Never infer a diagnosis or fact that is not explicitly present.
            Return one JSON object only, with no Markdown or explanation.
            The user supplied the JSON Schema below. It replaces the default output structure. Follow its property names, types, required fields, enums, nesting, and additionalProperties rule exactly.
            Use null only where the schema permits it. Preserve uncertainty and conflicting source statements in schema-compatible fields. Do not add medical advice.

            USER JSON SCHEMA:
            $customSchema
        """.trimIndent()
    }

    fun questionnaire(limit: Int) = """
        Create a daily health questionnaire grounded only in the supplied medical JSON. Return JSON only, with no Markdown.
        Return {"questions":[...]}, with at most ${limit.coerceIn(1, 50)} questions. Each question must have:
        {
          "id":"stable_snake_case_id",
          "label":"patient-friendly question",
          "type":"single_choice|text",
          "required":true,
          "options":[{"label":"Good","value":"good","numericValue":2,"unit":null}],
          "helpText":"Short explanation of the answer scale"
        }
        Every daily health question MUST be single_choice and MUST define 3–8 concrete answers. Options must be mutually exclusive, ordered from low/worse to high/better where appropriate, and include numericValue so trends can be graphed. Use explicit quantities and units rather than an open number field.
        Examples:
        - General feeling: Bad (0), Fair (1), Normal (2), Good (3), Very good (4).
        - Water: Less than 0.5 L (0), 0.5–1 L (1), 1–2 L (2), More than 2 L (3).
        - Medication: Not taken (0), Partly taken (1), Taken as prescribed (2), Not applicable today (null).
        - Stool: No bowel movement, Bristol types 1–2, types 3–4, types 5–7, with patient-friendly labels.
        - Pain, nausea, fatigue, sleep, appetite, mood and mobility: named severity/frequency bands, not unexplained numbers.
        Focus on symptoms, hydration/nutrition, bowel function, medication adherence, function, and treatment side effects supported by the context. Include exactly one final optional text question for notes with an empty options array. Never provide diagnosis or treatment recommendations. Do not ask for static profile facts.
    """.trimIndent()

    val chatSystem = """
        You are a careful health-information assistant. Use the provided patient context, clearly distinguish stored facts from general information, and say when information is missing. Do not diagnose, prescribe, change medication, or claim certainty. Encourage discussion with the care team for medical decisions. If the message suggests an emergency, advise contacting local emergency services immediately. Keep answers concise.
    """.trimIndent()
}
