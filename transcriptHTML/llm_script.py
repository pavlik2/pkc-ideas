#!/usr/bin/env python3
import argparse
import json
import re
import sys
from pathlib import Path

from openai import OpenAI

SYSTEM_PROMPT = r"""Return only valid JSON in this schema:

{
"title": "краткое название события",
"chapters": [
{
"id": 1,
"start": "HH:MM:SS,mmm",
"end": "HH:MM:SS,mmm",
"label": "название главы",
"summary": "подробное описание главы на русском, 5–10 предложений",
"quote": "одно точное полное предложение из SRT",
"speaker": "точный спикер этой цитаты"
}
],
"source_check": {
"first_timestamp_seen": "HH:MM:SS,mmm",
"last_timestamp_seen": "HH:MM:SS,mmm"
}
}

Rules:

1. Output only JSON. No markdown, no comments, no explanations.

2. The input is a full Russian SRT transcript of a long event. Create 8 to 20 chronological, non-overlapping chapters that cover the full event from near the beginning to near the final subtitle timestamp.

3. Chapters must reflect main themes and combine closely related subthemes into one chapter.

4. Copy all timestamps exactly from the SRT in HH:MM:SS,mmm format. Do not invent or transform timestamps.

5. You must inspect the final subtitle timestamp and put it exactly into source_check.last_timestamp_seen. The final chapter must end close to that timestamp. Do not cluster all chapters in the early part of the recording.

6. summary must describe only content inside that chapter range, in Russian, without invented facts.

7. quote must be exactly one full sentence copied verbatim from the SRT, taken from inside that chapter range only.

8. quote must not be paraphrased, merged from different subtitle lines, shortened in the middle, or taken from another chapter.

9. speaker must match the exact speaker of that quote from the same subtitle line.

10. Do not assign all quotes to one default speaker. Preserve transcript speaker identity exactly as written, for example:
    "SPEAKER_05"
    "Name"
    "Name Middlename Surname"

11. If a subtitle line names Name Middlename Surname with a misspelled surname, normalize it to exactly "Name Middlename Surname".

12. For each chapter, internally verify:

* there are subtitle lines within [start, end]
* quote is an exact full sentence from that range
* speaker comes from the same subtitle line as quote
* if not, fix it
* if no long quote fits, choose a shorter exact sentence from that same range

13. Before returning JSON, verify:

* source_check.last_timestamp_seen equals the final SRT timestamp
* the final chapter ends near it
* chapters span the full recording
* chapter count is between 8 and 20
* every quote exists verbatim in its chapter range
* every speaker matches the exact line of the quote
* one chapter approximately 20-40 minutes long

Return only the JSON object."""

TIMESTAMP_RE = re.compile(r"(\d{2}:\d{2}:\d{2},\d{3})\s*-->\s*(\d{2}:\d{2}:\d{2},\d{3})")


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return path.read_text(encoding="utf-8-sig")


def extract_first_last_timestamps(srt_text: str) -> tuple[str | None, str | None]:
    matches = TIMESTAMP_RE.findall(srt_text)
    if not matches:
        return None, None
    first_start = matches[0][0]
    last_end = matches[-1][1]
    return first_start, last_end


def extract_json(text: str) -> dict:
    text = text.strip()

    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass

    start = text.find("{")
    end = text.rfind("}")
    if start != -1 and end != -1 and end > start:
        return json.loads(text[start:end + 1])

    raise ValueError("Model output does not contain valid JSON")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Generate chapter JSON from a Language SRT transcript using LM Studio OpenAI-compatible API."
    )
    parser.add_argument("srt_file", help="Path to input SRT file")
    parser.add_argument(
        "--model",
        default="qwen/qwen3.5-9b",
        help="Model name loaded in LM Studio. Default: qwen/qwen3.5-9b",
    )
    parser.add_argument(
        "--base-url",
        default="http://127.0.0.1:1234/v1",
        help="LM Studio OpenAI-compatible base URL. Default: http://127.0.0.1:1234/v1",
    )
    parser.add_argument(
        "--api-key",
        default="lm-studio",
        help="API key placeholder for LM Studio. Default: lm-studio",
    )
    parser.add_argument(
        "--temperature",
        default=0.2,
        type=float,
        help="Sampling temperature. Default: 0.2",
    )
    parser.add_argument(
        "--max-output-tokens",
        default=12000,
        type=int,
        help="Maximum output tokens. Default: 12000",
    )
    parser.add_argument(
        "--no-thinking",
        action="store_true",
        help="Disable reasoning/thinking if supported by LM Studio/model.",
    )
    parser.add_argument(
        "--output",
        default="",
        help="Optional output JSON file path. If omitted, prints JSON to stdout.",
    )
    args = parser.parse_args()

    srt_path = Path(args.srt_file)
    if not srt_path.exists():
        print(f"Input file not found: {srt_path}", file=sys.stderr)
        return 1

    srt_text = read_text(srt_path)
    first_ts, last_ts = extract_first_last_timestamps(srt_text)

    if not first_ts or not last_ts:
        print("Could not find SRT timestamps in the input file.", file=sys.stderr)
        return 1

    user_prompt = (
        "Below is the full Language SRT transcript.\n\n"
        f"First timestamp seen: {first_ts}\n"
        f"Final timestamp seen: {last_ts}\n\n"
        "SRT:\n"
        f"{srt_text}"
    )

    client = OpenAI(base_url=args.base_url, api_key=args.api_key)

    request_kwargs = {
        "model": args.model,
        "temperature": args.temperature,
        "max_tokens": args.max_output_tokens,
        "response_format": {
            "type": "json_schema",
            "json_schema": {
                "name": "srt_chapters",
                "schema": {
                    "type": "object",
                    "additionalProperties": False,
                    "properties": {
                        "title": {"type": "string"},
                        "chapters": {
                            "type": "array",
                            "minItems": 8,
                            "maxItems": 20,
                            "items": {
                                "type": "object",
                                "additionalProperties": False,
                                "properties": {
                                    "id": {"type": "integer"},
                                    "start": {"type": "string"},
                                    "end": {"type": "string"},
                                    "label": {"type": "string"},
                                    "summary": {"type": "string"},
                                    "quote": {"type": "string"},
                                    "speaker": {"type": "string"}
                                },
                                "required": [
                                    "id", "start", "end", "label",
                                    "summary", "quote", "speaker"
                                ]
                            }
                        },
                        "source_check": {
                            "type": "object",
                            "additionalProperties": False,
                            "properties": {
                                "first_timestamp_seen": {"type": "string"},
                                "last_timestamp_seen": {"type": "string"}
                            },
                            "required": [
                                "first_timestamp_seen",
                                "last_timestamp_seen"
                            ]
                        }
                    },
                    "required": ["title", "chapters", "source_check"]
                }
            }
        },
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user_prompt},
        ],
    }

    if args.no_thinking:
        request_kwargs["reasoning_effort"] = "none"

    print("LM Studio request options:", file=sys.stderr)
    print(json.dumps({
        "model": args.model,
        "base_url": args.base_url,
        "temperature": args.temperature,
        "max_tokens": args.max_output_tokens,
        "response_format": {"type": "json_object"},
        "reasoning_effort": request_kwargs.get("reasoning_effort", "(default)"),
        "srt_file": str(srt_path),
        "output": args.output or "(stdout)",
    }, ensure_ascii=False, indent=2), file=sys.stderr)

    response = client.chat.completions.create(**request_kwargs)

    content = response.choices[0].message.content
    if not content:
        print("Model returned empty content.", file=sys.stderr)
        return 1

    try:
        data = extract_json(content)
    except Exception as e:
        print(f"Failed to parse JSON output: {e}", file=sys.stderr)
        print(content, file=sys.stderr)
        return 1

    pretty = json.dumps(data, ensure_ascii=False, indent=2)

    if args.output:
        Path(args.output).write_text(pretty + "\n", encoding="utf-8")
    else:
        print(pretty)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
