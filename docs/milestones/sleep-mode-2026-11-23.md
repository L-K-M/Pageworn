# Follow-up report: sleep mode

Date: 23 November 2026. Target: the owner's request to turn sleep into a mode, added after the milestones. The plan changed in the same pull request.

## 1. Delivered

The owner asked for six things. Each is in the [sleep behavior table](../05-playback-and-formats.md#sleep-behavior):

| Request | Behavior |
|---|---|
| Sleep is a mode that is on or off | The player's Sleep sheet turns the mode on with a length or off. It persists across process death, and every start of playback while it is on runs a fresh timer, also a headset Play after process death. |
| Show the timer on the player | While a timer runs, a live countdown (m:ss) under the Sleep label. With the mode on and no timer running, the length. |
| Presets in Settings | Settings > Sleep timer: 1 to 8 lengths from 1 to 720 minutes. End of chapter stays in the sheet. |
| Automatic times | An optional daily window, 22:00 to 07:00 by default. The mode turns on when it begins and off when it ends, and the listener's own choice holds in between. |
| Fade out | The volume falls over the last 3 s of wall time, also before an end-of-chapter stop, which still lands on the boundary. |
| Play during or shortly after the stop | Play or Pause during the fade keeps the book playing with a fresh timer. A Play within five minutes of a stop runs the same length, even after the window has ended. |

The owner chose these behaviors where the request left them open:

- A Play after a pause restarts the full length.
- End of chapter stays a choice.
- A book playing when the window begins gets its timer at once.

These rules follow from those answers:

- Any pause, including a playback failure, ends the timer.
- When the window ends, a running timer still runs to its end.
- Choosing in the sheet while the book is at rest no longer loads the book.

## 2. Files and design decisions

- `core/sleep/SleepSchedule.kt` holds the pure window rules. `data/SleepSettingsStore.kt` keeps the mode, length, presets, window and the last stop by a timer in preferences. It applies the window only at its edges, and finds them lazily, so an edge that passed while the phone was off still counts.
- `playback/PlaybackController.kt` runs the timers. It starts one at the start of playback, during a book switch while playing, or when the mode turns on during playback. Any pause ends it. The fade follows the time left with the player's volume and stops a timed timer on its 50 ms step. `BookPlayer` passes Play and Pause during the fade to the controller, so every surface keeps the book playing the same way.
- The `SET_SLEEP` session command now sets the mode and needs no loaded book.
- `ui/settings/SleepSettingsScreen.kt` and `ui/state/SleepSettingsViewModel.kt` add the Settings page. The time pickers follow the device's 12 or 24 hour setting.

## 3. Evidence

Environment: Media3 1.11.1, compile and target API 37. Emulator `pw-api37` (API 37, image `CE2A.260420.051.A3`), read-only instance with the swipe lock screen. Nothing ran on a phone.

- JVM: `scripts/build.sh` passed with 317 tests. New tests: `SleepTimerTest` (11 tests: arming, restart after pause, fade curve and timed stop, Play during the fade, the five-minute rule, Off, the window starting during playback), `SleepScheduleTest` (10) and `SleepSettingsStoreTest` (14).
- Instrumented on the API 37 emulator: `SleepPlaybackTest` 14 of 14, including new A13d, A13e (timed and 2x end of chapter), A13f and A13h; `PlaybackFailureTest` 7 of 7; `SystemControlsTest` 5 of 5; `PlayKeyTapTest` 11 of 11; `VisualChecksTest` 6 of 6; `RestartTest` A12b and A16e in separate processes, 4 of 4.
- Instrumented on emulator `pw-api34` (API 34, image `UE1A.230829.050`), read-only instance: `SleepPlaybackTest` 14 of 14, `PlaybackFailureTest` 7 of 7, `SystemControlsTest` 5 of 5, `PlayKeyTapTest` 11 of 11 and `VisualChecksTest` 6 of 6. The first full run failed A13 once at its locked-keyguard check; A13 then passed 3 of 3 alone and the whole class passed in a rerun.
- Real touches with screenshots on the same emulator: Settings shows the Sleep timer row. The page added a 90-minute preset, switched on the window, and closed the time picker with Cancel. The player sheet listed the new preset. Choosing 15 minutes at rest showed "15 min". Play showed a live countdown read as "Sleep, 14 minutes 55 seconds remaining", and Pause returned to "Sleep, on, 15 minutes".

## 4. Pending

- An API 31 emulator run of the instrumented sleep tests. No API 31 image is installed on this machine.
- Owner checks: hearing the fade and a locked-screen stop on the phone, a Bluetooth headset Play during the fade, and the window across a real night.
