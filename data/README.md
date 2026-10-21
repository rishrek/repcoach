# Recorded sessions

The replay test runs every recording listed in `labels.csv` through the real analyzers and reports how well they count.

## Add a recording

1. On the phone, tap the frame-rate box (top-left) and switch on **Record CSV**. Do a set, then switch it off.
2. Copy the files to this folder:
   ```bash
   adb pull /sdcard/Android/data/com.repcoach.phone/files/ data/
   ```
   (move the CSVs out of the `files/` subfolder adb creates)
3. Add one line per file to `labels.csv`:

   | column | meaning |
   | --- | --- |
   | `file` | CSV file name, e.g. `squat_2026-10-22_10-15-00.csv` |
   | `true_reps` | reps you really did (count them on the video or out loud) |
   | `bad_reps` | rep numbers that had a deliberate fault, separated by `;` (e.g. `3;7`), empty if none |
   | `exercise` | `squat` or `pushup` (empty = squat) |

## Run the report

```bash
./gradlew :core:replay
```

It prints per-file and total count accuracy: `1 - sum(|counted - true|) / sum(true)`. The target is 95% or better for squats and push-ups. If a file is off, tune the thresholds in `RepConfig` / `PushupConfig` (core/src/main/kotlin/com/repcoach/core/Pose.kt) one at a time and run it again.
