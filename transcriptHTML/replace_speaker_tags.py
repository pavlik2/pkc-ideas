#!/usr/bin/env python3
import argparse
import re
from collections import Counter
from pathlib import Path


SPEAKER_PATTERN = re.compile(r"\[(SPEAKER_\d+)\]:")


def find_most_common_speaker(text: str) -> str:
    matches = SPEAKER_PATTERN.findall(text)
    if not matches:
        raise ValueError("No speaker tags like [SPEAKER_04]: were found in the SRT file.")

    counts = Counter(matches)
    most_common_speaker, count = counts.most_common(1)[0]
    print(f"Most common tag: [{most_common_speaker}]: ({count} times)")
    return most_common_speaker


def replace_only_one_speaker(text: str, old_speaker: str, new_speaker: str) -> str:
    pattern = re.compile(rf"\[{re.escape(old_speaker)}\]:")
    return pattern.sub(f"[{new_speaker}]:", text)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Replace only the most common SRT speaker tag with a custom speaker name."
    )
    parser.add_argument("input", help="Input SRT file")
    parser.add_argument(
        "--speaker",
        required=True,
        help='Replacement speaker name, for example: --speaker "Anna"',
    )
    parser.add_argument(
        "-o",
        "--output",
        help="Output SRT file. Default: input filename with .replaced.srt suffix",
    )
    args = parser.parse_args()

    input_path = Path(args.input)
    if not input_path.exists():
        raise FileNotFoundError(f"Input file not found: {input_path}")

    output_path = (
        Path(args.output)
        if args.output
        else input_path.with_name(f"{input_path.stem}.replaced{input_path.suffix}")
    )

    text = input_path.read_text(encoding="utf-8")
    most_common = find_most_common_speaker(text)
    new_text = replace_only_one_speaker(text, most_common, args.speaker)
    output_path.write_text(new_text, encoding="utf-8")

    print(f"Written: {output_path}")


if __name__ == "__main__":
    main()
