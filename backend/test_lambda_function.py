# runs without an AWS account, see the backend readme for the command

import base64
import json
import os
import unittest

import lambda_function as fn

GOOD_SET = {"exercise": "SQUAT", "reps": 10, "cleanReps": 8, "faultCounts": {"KNEES_CAVE": 2}, "durationSec": 41}


class FakeBedrock:
    def __init__(self, reply=None, error=None):
        self.reply, self.error, self.calls = reply, error, []

    def converse(self, **kwargs):
        self.calls.append(kwargs)
        if self.error:
            raise self.error
        return {"output": {"message": {"role": "assistant", "content": [{"text": self.reply}]}}}


def event(body, key="secret", encode=False):
    raw = json.dumps(body)
    if encode:
        raw = base64.b64encode(raw.encode()).decode()
    return {"headers": {"X-RepCoach-Key": key, "content-type": "application/json"}, "body": raw, "isBase64Encoded": encode}


class LambdaTest(unittest.TestCase):
    def setUp(self):
        os.environ["KEY"] = "secret"
        os.environ["MODEL_ID"] = "us.amazon.nova-lite-v1:0"
        self.fake = FakeBedrock(reply="Great depth. Push your knees out. Think knees out next set.")
        fn._bedrock = self.fake

    def test_returns_ai_text(self):
        response = fn.lambda_handler(event(GOOD_SET), None)
        self.assertEqual(200, response["statusCode"])
        self.assertEqual({"aiText": "Great depth. Push your knees out. Think knees out next set."}, json.loads(response["body"]))

    def test_sends_system_prompt_model_and_token_limit(self):
        fn.lambda_handler(event(GOOD_SET), None)
        call = self.fake.calls[0]
        self.assertEqual("us.amazon.nova-lite-v1:0", call["modelId"])
        self.assertEqual(200, call["inferenceConfig"]["maxTokens"])
        self.assertIn("exactly 3 short sentences", call["system"][0]["text"])
        self.assertEqual(GOOD_SET, json.loads(call["messages"][0]["content"][0]["text"]))

    def test_wrong_or_missing_key_is_rejected(self):
        self.assertEqual(403, fn.lambda_handler(event(GOOD_SET, key="nope"), None)["statusCode"])
        self.assertEqual(403, fn.lambda_handler({"body": json.dumps(GOOD_SET)}, None)["statusCode"])
        self.assertEqual([], self.fake.calls)

    def test_no_key_configured_rejects_everything(self):
        os.environ["KEY"] = ""
        self.assertEqual(403, fn.lambda_handler(event(GOOD_SET, key=""), None)["statusCode"])

    def test_bad_bodies_are_400(self):
        for body in [{"reps": "x"}, {"reps": 3, "cleanReps": 5}, {**GOOD_SET, "exercise": "LUNGE"}, {**GOOD_SET, "faultCounts": {"BAD": 1}}, [1, 2]]:
            self.assertEqual(400, fn.lambda_handler(event(body), None)["statusCode"], body)
        self.assertEqual(400, fn.lambda_handler({"headers": {"x-repcoach-key": "secret"}, "body": "{nope"}, None)["statusCode"])

    def test_base64_body_from_function_url(self):
        self.assertEqual(200, fn.lambda_handler(event(GOOD_SET, encode=True), None)["statusCode"])

    def test_bedrock_error_is_502(self):
        fn._bedrock = FakeBedrock(error=RuntimeError("throttled"))
        response = fn.lambda_handler(event(GOOD_SET), None)
        self.assertEqual(502, response["statusCode"])
        self.assertEqual({"error": "coach unavailable"}, json.loads(response["body"]))

    def test_empty_bedrock_reply_is_502(self):
        fn._bedrock = FakeBedrock(reply="   ")
        self.assertEqual(502, fn.lambda_handler(event(GOOD_SET), None)["statusCode"])


if __name__ == "__main__":
    unittest.main()
