# RepCoach

**Your Fire TV becomes a strength coach.** Prop an Android phone against the wall, do squats or push-ups, and the TV counts every rep out loud, draws your skeleton live, and calls out form mistakes the moment they happen. After each set, Amazon Bedrock writes a short coaching summary.

The phone never sends video anywhere. Pose detection runs on the phone; only 33 body points go to the TV over your home Wi-Fi.

Built for the Amazon Developer Hackathon, Fire TV track.

## What it does

| | |
| --- | --- |
| **Rep counting** | Squats and push-ups, counted out loud on the TV. Partial reps don't count. |
| **Live form cues** | Knees caving in, chest dropping, too shallow, hips sagging or piking. The legs turn red, a banner shows the cue and the TV says it ("Knees out"). |
| **AI summary** | After each set, Amazon Bedrock writes 3 sentences: one thing done well, the main fix, one cue for next time. Works offline too, with a built-in summary. |
| **Duel mode** | Two phones, one TV, split screen. First to 10 clean reps wins (60 second limit). |
| **Ghost rep** | Your deepest clean rep replays as a faint skeleton beside you from the second rep on. |

## How it works

1. The phone camera (CameraX, 640x480) feeds MediaPipe pose detection on the phone.
2. The phone works out angles, reps and form faults, and sends small JSON messages to the TV over Wi-Fi (WebSocket, port 8765).
3. The TV draws the skeleton, shows the counter and plays the voice clips. It sends back control messages like start and end of set.
4. At the end of a set the phone sends only the numbers over HTTPS to an AWS Lambda, which asks Amazon Bedrock for three coaching sentences.

- **The phone does the thinking.** MediaPipe Pose Landmarker runs on-device. The `core` library turns landmarks into joint angles, counts reps with a hysteresis state machine (so jitter never double counts) and checks form rules over 3 consecutive frames.
- **The TV only renders.** It runs a WebSocket server, announces itself on the network (NSD, `_repcoach._tcp`), draws a mirrored skeleton and plays voice clips. It uses no Google Play Services, so it runs on Fire OS.
- **Messages** are small JSON objects (`pose`, `rep`, `fault`, `status`, `summary`, `control`, ...), with landmark coordinates rounded to 3 decimals and at most 15 poses per second.

### How we use AWS

- **Amazon Bedrock**: an AWS Lambda behind a Function URL calls the Bedrock **Converse API** (Amazon Nova Lite by default, 200 max tokens) with only the set's numbers: exercise, reps, clean reps, fault counts and duration. Neither app holds AWS credentials; the phone sends a shared key header and the Lambda checks it. See [backend/README.md](backend/README.md).
- **Amazon Polly**: the TV's voice (rep numbers 1 to 20, cues, countdown, winner calls) is generated with Polly neural voices by [tools/make_audio_polly.sh](tools/make_audio_polly.sh) and played with SoundPool, so cues play instantly with no network round trip.

## Accuracy

The replay test runs the team's recorded sets (landmarks only, CSV) through the same analyzers the phone uses and compares the counts with hand labels.

| Exercise | Recorded sets | Count accuracy |
| --- | --- | --- |
| Squats | _to be filled_ | _to be filled_ |
| Push-ups | _to be filled_ | _to be filled_ |

Target: 95% or better. How to record and run it: [data/README.md](data/README.md).

## Run it

You need Android Studio, an Android phone (Android 9+) and a Fire TV on the same Wi-Fi.

1. Open this folder in Android Studio and let Gradle sync. The first build downloads the MediaPipe pose models (about 15 MB) automatically.
2. Build both apps (or use the Run button for each module):

   ```bash
   ./gradlew :phone:assembleDebug :tv:assembleDebug
   ```

3. Install. Phone over USB (turn on USB debugging in Developer options):

   ```bash
   adb install -r phone/build/outputs/apk/debug/phone-debug.apk
   ```

   Fire TV: open Settings, then My Fire TV, then Developer options, and turn **ADB debugging** on. You can find its IP in Settings under My Fire TV, About, Network.

   ```bash
   adb connect FIRE_TV_IP:5555
   ```

   ```bash
   adb -s FIRE_TV_IP:5555 install -r tv/build/outputs/apk/debug/tv-debug.apk
   ```

4. Open RepCoach on the TV (Your Apps row). It shows its IP address.
5. Open RepCoach on the phone. Pick the TV from the list, or type the IP shown on the TV.
6. Place the phone:
   - **Squats**: at hip height, 2 to 3 m away, about 30 degrees to your side.
   - **Push-ups**: on the floor, 1 to 1.5 m away, side view, whole body in frame.
7. When your whole body is in view the set starts by itself. Squat. Press **Select** on the remote to finish (or just stop: the set ends after 8 quiet seconds).
8. Second phone? Connect it to the same TV and the duel lobby appears.

Optional AI summaries: deploy the Lambda ([backend/README.md](backend/README.md)) and add its URL and key to `local.properties`, then rebuild the phone app. Without it, the phone's own summary text is used.

Without `adb` in your PATH, it lives in `~/Library/Android/sdk/platform-tools/` (macOS) or `%LOCALAPPDATA%\Android\Sdk\platform-tools\` (Windows).

## Using it

**Phone**: Connect screen, then the camera screen with a skeleton overlay, a green tick when you're fully in view, the placement hint and a Squats / Push-ups switch. During a set it goes black ("Look at your TV") to save battery. Tap the frame-rate box (top-left) for the debug menu: knee/elbow angle, rep count and **Record CSV**.

**TV remote**: D-pad moves between buttons, Select presses. During a set, Select ends it. Summary: **New set** or **Done**. Duel lobby: **Start duel** and the exercise picker; after a duel, **Rematch** or **Back to lobby**.

## Tests

```bash
./gradlew :core:test :pose-bridge:test :tv:testDebugUnitTest
```

```bash
python3 -m unittest discover -s backend -v
```

- `core`: geometry, both analyzers (synthetic angle sequences: clean reps, shallow reps, jitter, too-fast reps, each fault), the phone's `Coach` logic, duel rules, summary text, CSV and replay.
- `pose-bridge`: real WebSocket connections on localhost, including room full, slot takeover, and reconnecting after the server restarts.
- `tv`: the TV's whole flow without a TV: solo set, faults and cue timing, summaries (also when the phone disappears), duel countdown, winner, time limit, ghost replay.
- `backend`: the Lambda with a fake Bedrock: key check, validation, 502 on Bedrock errors.

## Project layout

| Folder | What |
| --- | --- |
| `core/` | Plain Kotlin, shared by both apps: messages, geometry, `SquatAnalyzer`, `PushupAnalyzer`, `Coach` (phone logic), duel rules, CSV recorder format, replay report |
| `pose-bridge/` | Small WebSocket library for streaming poses from phones to a TV: slots, room full, auto-reconnect. Ready to become its own repo ([pose-bridge/README.md](pose-bridge/README.md)) |
| `phone/` | Android app (minSdk 28): CameraX, MediaPipe, NSD discovery, summary call |
| `tv/` | Fire TV app (minSdk 25): server, NSD, Compose for TV, SoundPool |
| `backend/` | AWS Lambda (Python 3.12) calling Bedrock, with deploy guide |
| `data/` | Recorded sessions and `labels.csv` for the accuracy report |
| `tools/` | Voice clip scripts (Amazon Polly, or the macOS voice as a placeholder) |

Thresholds live in `RepConfig` and `PushupConfig` ([core/.../Pose.kt](core/src/main/kotlin/com/repcoach/core/Pose.kt)) so they can be tuned against recordings.

## Troubleshooting

- **TV doesn't appear in the phone's list**: type the IP shown on the TV. Some routers block the multicast discovery uses (guest networks, "AP isolation"). If typing the IP also fails, the devices aren't on the same network.
- **Phone shows "TV lost, retrying..."**: it retries every 2 seconds by itself. Check the TV app is open.
- **Low frame rate** (amber number in the phone's corner): the phone switches to the lighter pose model by itself below 15 fps.
- **No voice on the TV**: check the TV volume; clips are in `tv/src/main/res/raw`.

## License

MIT, see [LICENSE](LICENSE).
