# Working on RepCoach

## Who owns what

| Role | Owns | Stages |
| --- | --- | --- |
| Backend + AI | `core/`, `backend/`, `data/` replay test, README | 1, 2, 6, 7, 8, 9 (core part) |
| Phone lead | `phone/` | 3, 4 (phone side), 9 (picker), 11 (phone side) |
| TV lead | `tv/` | 4 (TV side), 5, 10, 11 (TV side) |
| Fourth teammate | Recording squat sessions into `data/`, Polly audio clips, demo video, testing on devices | all |

`core` is shared by both apps: **only Backend + AI changes it.** If the phone or TV needs a new message field, ask them.

## Device test checklist

Everything is built and unit tested. These checks need real devices:

| # | Do this | Pass when |
| --- | --- | --- |
| 1 | Install both APKs; open RepCoach on the Fire TV | It's in Your Apps with the banner; shows its IP and port 8765 |
| 2 | Open the phone app | The TV appears in the list (or connect with the IP); TV shows the setup screen |
| 3 | Stand in view | Phone shows the green tick; TV says "Squats" and the workout screen appears; skeleton moves with no visible lag |
| 4 | 10 squats | Exactly 10 spoken counts on the TV |
| 5 | One deliberate knee cave | Legs turn red, "Knees out" banner and voice within half a second |
| 6 | Press Select on the remote | Summary: reps, clean %, most common fault, coaching text |
| 7 | Turn the router's internet off (Wi-Fi stays on), do a set | Summary still appears (built-in text) |
| 8 | 10 sets in a row | No crash, no lost connection |
| 9 | Push-ups (pick on phone or TV): 10 reps, then one with sagging hips | 10 counted; "Hips up" |
| 10 | Connect a second phone | Duel lobby appears; Start duel, countdown, then split screen; first to 10 clean wins; play 2 full matches |
| 11 | Solo, from rep 2 | A faint skeleton replays your best rep beside you; live skeleton stays smooth |
| 12 | Record sets with Record CSV, label them, run `./gradlew :core:replay` | 95%+ count accuracy for squats and push-ups; put the numbers in README |

Feature freeze: Thu 22 Oct, 12 PM IST. Deadline: Fri 23 Oct, 6 PM IST.

## Rules

1. **Small steps.** Run the app on real devices before moving on to the next change.
2. **Branch per stage**, e.g. `stage-3-phone-camera`. Open a pull request to `main`; one other person checks it builds before merging.
3. `./gradlew :core:test :pose-bridge:test :tv:testDebugUnitTest :phone:assembleDebug :tv:assembleDebug` must pass before you merge. CI runs the same on every push, plus the backend tests.
4. Never commit `local.properties`, API keys, the Lambda URL key or AWS credentials.
5. No Google Play Services or Firebase in `tv/`. Fire OS doesn't have them.
6. Never send camera frames off the phone. Landmarks only.

## When something breaks

Paste the full error and say what you did. Find the root cause before changing code, fix only that, and don't touch other people's modules.
