# Field Intercom

A private, low-latency push-to-talk intercom for events, running entirely on
your own WiFi LAN. Samsung phones are the handsets, a Windows PC is the
mixer/server, and it can bridge audio to/from another system through a USB
audio interface.

## How it works

- Everyone shares one open channel. Tap-and-hold (or tap-to-latch) a big
  button to talk; everyone else hears you, mixed in with anyone else talking
  at the same time.
- Audio rides plain UDP, 16kHz mono PCM, 20ms frames — no codec library to
  fight with, and at this bitrate 25 phones is trivial load for a modern
  WiFi network.
- A phone's mic is only ever open while it's actively transmitting, and the
  server never sends a phone its own voice back — so there's no feedback
  loop to design around.
- Connection status ("out of range") and reconnection are fully automatic:
  the phone just keeps trying, and the moment packets get through again it's
  connected — no button to press.
- Login is a single shared password; the session lasts 8 hours or until
  logout, matching the spec.

## What's in here

```
server/     Windows-side Python server (the "base station" / mixer)
android/    Android Studio project for the phone app
```

## 1. Running the server

Requires Python 3.10+.

```
cd server
pip install -r requirements.txt
python server.py
```

Before running for real:

- **Set the password** in `config.py` (`PASSWORD = "..."`).
- **Open the UDP port** (default 5005) in Windows Firewall for both
  private and any relevant profile.
- **USB audio interface**: run `python list_audio_devices.py` to see the
  exact device names/indices Windows exposes for your interface, then set
  `USB_INPUT_DEVICE` / `USB_OUTPUT_DEVICE` in `config.py`. Leave them as
  `None` if you just want phone-to-phone intercom with no bridge for now —
  everything else still works.

A smoke test is included (`python test_server.py`) that spins up the real
server logic with two simulated phones and verifies login, heartbeat, and
mixing all work — useful after any change, or just to confirm your Python
environment is set up correctly before testing with real phones.

## 2. Building the Android app

**If your build computer is fully offline with no way to get it online even
briefly**, see **`BUILD_APK_VIA_GITHUB.md`** instead — it builds the APK on
GitHub's servers using only a browser, no local Android Studio/SDK/Gradle
needed at all. The workflow file it uses is already included at
`.github/workflows/build.yml`.

Otherwise, open the `android/` folder in Android Studio (Giraffe or newer), let Gradle
sync, and build/run onto a phone. minSdk is 26 (covers the S9's original
Android 8.0 Oreo through its later updates).

**I was not able to compile this project inside my sandboxed environment**
(no Android SDK available there), so while the code is written carefully
against well-documented, standard Android APIs, please do a first build in
Android Studio and let me know if anything doesn't compile — I'll fix it
immediately.

First run on each phone:
1. Grant the microphone permission when prompted.
2. Enter the server's LAN IP address (e.g. `192.168.1.10`) and the password.
3. You're in. The status banner turns red with "Out of range" automatically
   if the phone loses the server's WiFi, and turns green again the moment
   it's back in range — no action needed.

**Headset button**: a wired headset's inline mic button (or a Bluetooth
headset's call button) works as the talk button automatically — same
hold-to-talk / tap-to-latch behavior as the on-screen button.

## 3. Tuning your Ubiquiti network for lowest latency

- Put the intercom phones on a **5GHz-only SSID** if possible — 2.4GHz has
  more contention and interference at a typical event venue.
- Enable **WMM (WiFi Multimedia)/voice QoS** on the APs — it's usually on
  by default on Ubiquiti gear but worth confirming.
- If you have many phones on one AP, consider a **dedicated SSID/VLAN**
  just for the intercom so it isn't competing for airtime with guest WiFi,
  streaming, etc.
- Keep phones reasonably distributed across APs rather than all hammering
  one AP — Ubiquiti's band-steering/load-balancing features can help here.

## Admin dashboard

Instead of `python server.py`, run:

```
python admin_gui.py
```

This runs the identical server logic, plus a small window (Tkinter, ships
with Python -- no extra install) showing:

- Every connected phone, by the name they entered at login, with live
  Connected/Out of range and Talking status.
- **Mute selected** / **Unmute selected** -- force-mutes a phone's mic
  remotely. The phone is notified immediately and its talk button becomes
  disabled and shows "Muted by admin" until you unmute it.
- **Message selected...** / **Broadcast to all...** -- sends a short text
  message that pops up on the phone's screen, plus a distinct chime and a
  double-buzz vibration, different from any other alert in the app.
- A live log panel at the bottom.

## Phone-side alerts

- **Incoming message**: a two-note chime, a short double-buzz, and a popup
  with the text -- deliberately gentle, so it doesn't get confused with the
  next one.
- **Going out of WiFi range**: a distinctly different three-beep alarm tone
  (on the phone's alarm audio stream, so it cuts through) plus a longer,
  more insistent vibration pattern -- so you notice even without looking at
  the screen. This fires once per disconnect, not repeatedly.

## Measuring real latency

Tap **Test latency** on the main screen. It runs two independent measurements:

- **Network ping** — a bare round trip to the server, answered instantly
  without going through the audio mixing tick. This isolates WiFi + server
  overhead from everything else. Shown as a one-way estimate (RTT / 2),
  updated once a second.
- **Full pipeline test** — hold the button and talk normally. For the
  duration of this screen, the server stops excluding this phone's own
  voice from its mix, so your audio travels the *entire* real path —
  mic capture → WiFi → server → WiFi → this phone — and comes back to you,
  both audibly (you'll hear yourself with a short delay) and as a precise
  millisecond number. This is already a one-way estimate (it crosses the
  same two WiFi hops a real conversation between two phones would), so
  don't halve it. It doesn't include the last step — the receiving
  phone's speaker playback buffer — so real perceived latency will run
  a little higher than this number, typically by another 10-30ms.

Run this test on a couple of different phones and while walking to the edge
of WiFi range to get a realistic picture, not just a same-room best case.

## Known limitations / things worth knowing

- The Galaxy S9 shipped with two different chipsets depending on region:
  Snapdragon 845 (US/China) and Exynos 9810 (most everywhere else). The
  Exynos audio path has historically had worse low-latency performance in
  Android. Worth testing round-trip latency on one phone before assuming
  it'll be uniform across your whole fleet.
- Audio quality is intentionally basic (16kHz mono PCM) to keep this simple
  and dependency-free. If you ever need more phones or tighter WiFi
  bandwidth, switching to Opus compression is a natural next step but adds
  a native codec dependency on the Android side.
- The server keeps working with a smaller phone-to-phone-only setup even
  before you've wired up the USB interface — you can bring that online
  later without changing anything else.
