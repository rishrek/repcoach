# Lambda that writes the coaching summary for a set.
# The phone sends the set with our shared key in a header, we check it and ask Bedrock
# for three short sentences. Needs KEY and MODEL_ID set as environment variables.

import base64
import hmac
import json
import os

SYSTEM_PROMPT = (
    "You are a friendly strength coach. You get data from one set of squats or push-ups as JSON. "
    "Write exactly 3 short sentences: one thing done well, the main fix, one cue for the next set. "
    "No medical advice."
)
MAX_TOKENS = 200
EXERCISES = {"SQUAT", "PUSHUP"}
FAULTS = {"KNEES_CAVE", "CHEST_DOWN", "SHALLOW", "HIPS_SAG", "HIPS_PIKE"}

_bedrock = None


def bedrock():
    # made on first use so the tests can put a fake in its place
    global _bedrock
    if _bedrock is None:
        import boto3

        _bedrock = boto3.client("bedrock-runtime")
    return _bedrock


def respond(status, body):
    return {"statusCode": status, "headers": {"content-type": "application/json"}, "body": json.dumps(body)}


def parse_set(event):
    # checks the body and keeps only what Bedrock needs
    raw = event.get("body") or ""
    if event.get("isBase64Encoded"):
        raw = base64.b64decode(raw).decode("utf-8")
    data = json.loads(raw)
    if not isinstance(data, dict):
        raise ValueError("body must be a JSON object")

    exercise = str(data.get("exercise", "SQUAT")).upper()
    if exercise not in EXERCISES:
        raise ValueError("unknown exercise")
    reps = int(data["reps"])
    clean = int(data["cleanReps"])
    duration = int(data.get("durationSec", 0))
    if not (0 <= clean <= reps <= 500) or not (0 <= duration <= 3600):
        raise ValueError("numbers out of range")
    faults = data.get("faultCounts") or {}
    if not isinstance(faults, dict) or any(k not in FAULTS for k in faults):
        raise ValueError("unknown fault")
    return {
        "exercise": exercise,
        "reps": reps,
        "cleanReps": clean,
        "faultCounts": {k: int(v) for k, v in faults.items()},
        "durationSec": duration,
    }


def lambda_handler(event, context):
    headers = {k.lower(): v for k, v in (event.get("headers") or {}).items()}
    expected = os.environ.get("KEY", "")
    if not expected or not hmac.compare_digest(headers.get("x-repcoach-key", ""), expected):
        return respond(403, {"error": "forbidden"})

    try:
        workout = parse_set(event)
    except (ValueError, KeyError, TypeError, json.JSONDecodeError):
        return respond(400, {"error": "bad request"})

    try:
        result = bedrock().converse(
            modelId=os.environ["MODEL_ID"],
            system=[{"text": SYSTEM_PROMPT}],
            messages=[{"role": "user", "content": [{"text": json.dumps(workout)}]}],
            inferenceConfig={"maxTokens": MAX_TOKENS},
        )
        text = " ".join(
            part["text"] for part in result["output"]["message"]["content"] if "text" in part
        ).strip()
        if not text:
            raise ValueError("empty reply")
    except Exception as error:  # the phone falls back to its own text when this fails
        print(f"Bedrock error: {error!r}")
        return respond(502, {"error": "coach unavailable"})

    return respond(200, {"aiText": text})
