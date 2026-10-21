# RepCoach backend: coaching summary Lambda

After each set the phone POSTs the set's numbers to this AWS Lambda through a **Function URL**. The Lambda calls **Amazon Bedrock** (Converse API) and returns three short coaching sentences, which the TV shows on the summary screen.

- No AWS credentials in either app. The phone only knows the Function URL and a shared key (`x-repcoach-key` header).
- Only numbers leave the phone: exercise, reps, clean reps, fault counts, duration. Never video or landmarks.
- If the call fails or there's no internet, the phone writes its own three-sentence summary, so the summary always appears.

| | |
| --- | --- |
| Runtime | Python 3.12, handler `lambda_function.lambda_handler`, timeout 15 s |
| Env `KEY` | shared secret; requests with any other `x-repcoach-key` get 403 |
| Env `MODEL_ID` | e.g. `us.amazon.nova-lite-v1:0` (a cross-region inference profile; plain model IDs work in some regions) |
| Bedrock call | `converse`, `maxTokens` 200, system prompt in `lambda_function.py` |
| Responses | 200 with `{"aiText": "..."}`, 400 bad body, 403 bad key, 502 Bedrock error |

## Test locally (no AWS needed)

```bash
python3 -m unittest discover -s backend -v
```

## Deploy with the AWS CLI

Pick a region where Bedrock and your model are available (e.g. `us-east-1`). Make a key first:

```bash
export KEY=$(openssl rand -hex 24) && echo $KEY
```

1. Role for the function (logs + Bedrock invoke):

```bash
aws iam create-role --role-name repcoach-lambda --assume-role-policy-document file://backend/trust-policy.json
```

```bash
aws iam attach-role-policy --role-name repcoach-lambda --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole
```

```bash
aws iam put-role-policy --role-name repcoach-lambda --policy-name bedrock-invoke --policy-document file://backend/iam-policy.json
```

2. The function (wait ~10 s after creating the role):

```bash
cd backend && zip function.zip lambda_function.py && cd ..
```

```bash
aws lambda create-function --function-name repcoach-summary --runtime python3.12 --handler lambda_function.lambda_handler --timeout 15 --zip-file fileb://backend/function.zip --role "arn:aws:iam::$(aws sts get-caller-identity --query Account --output text):role/repcoach-lambda" --environment "Variables={KEY=$KEY,MODEL_ID=us.amazon.nova-lite-v1:0}"
```

3. Public Function URL (the key check in the code is the guard):

```bash
aws lambda create-function-url-config --function-name repcoach-summary --auth-type NONE
```

```bash
aws lambda add-permission --function-name repcoach-summary --statement-id public-url --action lambda:InvokeFunctionUrl --principal "*" --function-url-auth-type NONE
```

The first command prints the `FunctionUrl`.

To update the code later:

```bash
cd backend && zip function.zip lambda_function.py && aws lambda update-function-code --function-name repcoach-summary --zip-file fileb://function.zip && cd ..
```

## Deploy in the AWS console

1. In **IAM**, go to **Roles**, click **Create role**, pick AWS service and Lambda, and attach `AWSLambdaBasicExecutionRole`. Name it `repcoach-lambda`. Then open the role, click **Add permissions**, then **Create inline policy**, switch to JSON and paste `iam-policy.json`.
2. In **Lambda**, click **Create function**, choose Author from scratch, name it `repcoach-summary`, runtime **Python 3.12**, execution role `repcoach-lambda`.
3. Paste `lambda_function.py` into the code editor and click **Deploy**.
4. Under **Configuration**, open **General configuration**, click **Edit** and set the timeout to **15 s**.
5. Under **Configuration**, open **Environment variables** and add `KEY` with your key and `MODEL_ID` with `us.amazon.nova-lite-v1:0`.
6. Under **Configuration**, open **Function URL**, click **Create**, pick auth type **NONE** and save. Copy the URL.
7. If your account still has a Bedrock **Model access** page, enable **Amazon Nova Lite** there.

## Check it with curl

```bash
curl -s -X POST "https://YOUR-ID.lambda-url.us-east-1.on.aws/" -H "content-type: application/json" -H "x-repcoach-key: $KEY" -d '{"exercise":"SQUAT","reps":10,"cleanReps":8,"faultCounts":{"KNEES_CAVE":2},"durationSec":41}'
```

Expected: `{"aiText": "..."}` with three sentences. With a wrong key you get `{"error": "forbidden"}`.

Troubleshooting:
- `{"error": "coach unavailable"}`: Bedrock refused. Check CloudWatch logs for the function: usually the model isn't enabled in this region, or `MODEL_ID` needs the `us.` inference-profile form.
- `{"Message":"Forbidden"}` (AWS's own error, not ours): the Function URL permission is missing. Re-run the `add-permission` command. If it still fails, newer accounts also need invoke permission: `aws lambda add-permission --function-name repcoach-summary --statement-id public-invoke --action lambda:InvokeFunction --principal "*"` (requests still need the key).

## Point the phone at it

Add to `local.properties` in the repo root (git-ignored, never commit it), then rebuild the phone app:

```
repcoach.lambdaUrl=https://YOUR-ID.lambda-url.us-east-1.on.aws/
repcoach.lambdaKey=the-same-key
```
