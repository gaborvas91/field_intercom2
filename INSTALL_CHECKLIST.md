# Field Intercom — Installation Checklist

Print this or check items off as you go. Part 1 must be done on a computer
**with internet access** since the server machine has none; everything
after that happens on-site.

---

## Part 1 — Prepare the offline install package (on a computer WITH internet)

The Windows server needs Python itself plus a handful of Python packages
("wheels"). Since it's offline, you download everything ahead of time and
carry it over on a USB drive.

**1a. Download the Python installer**

- [ ] Go to https://www.python.org/downloads/windows/
- [ ] Download the **64-bit Windows installer** for Python 3.11 or 3.12
      (e.g. `python-3.11.9-amd64.exe`) — note the exact version, you'll need
      it to match below.
- [ ] Copy this `.exe` onto your USB drive.

**1b. Download the wheels**

On the internet-connected computer (any OS — Windows, Mac, or Linux all
work for this step, you're just downloading files, not installing them):

```
pip install --upgrade pip
mkdir wheels
pip download --platform win_amd64 --python-version 311 --implementation cp --abi cp311 --only-binary=:all: -d wheels numpy sounddevice
```

Change `311` / `cp311` to match whatever Python version you downloaded in
1a (e.g. `312` / `cp312` for Python 3.12).

This produces exactly four files in the `wheels` folder — confirmed by
actually running this command:

- [ ] `numpy-2.4.6-cp311-cp311-win_amd64.whl` (version number may differ slightly by the time you run it — that's fine)
- [ ] `sounddevice-0.5.5-py3-none-win_amd64.whl`
- [ ] `cffi-2.1.0-cp311-cp311-win_amd64.whl`
- [ ] `pycparser-3.0-py3-none-any.whl`

That's the complete dependency tree — nothing else is required. The
Windows `sounddevice` wheel conveniently bundles the PortAudio DLL inside
it, so there's no separate native library to hunt down.

- [ ] Copy the whole `wheels` folder onto your USB drive.
- [ ] Also copy the whole `field-intercom` project folder (server + android)
      onto the USB drive.

**1c. (Safety net, optional but recommended)**

- [ ] Also download the **Microsoft Visual C++ Redistributable (x64)** from
      https://aka.ms/vs/17/release/vc_redist.x64.exe and copy it over too.
      Modern numpy wheels rarely need it, but if `import numpy` ever fails
      with a DLL load error on the server, this fixes it.

---

## Part 2 — Set up the Windows server (on-site, offline is fine from here)

- [ ] Copy the `wheels` folder and `field-intercom` project folder from the
      USB drive onto the server computer.
- [ ] Run the Python installer. On the first screen, check **"Add python.exe
      to PATH"** before clicking Install.
- [ ] Open Command Prompt and confirm: `python --version` and `pip --version`
      both return something (not "not recognized").
- [ ] Install the wheels with no internet lookup:
  ```
  cd path\to\field-intercom\server
  pip install --no-index --find-links=path\to\wheels -r requirements.txt
  ```
- [ ] Confirm it worked: `python -c "import numpy, sounddevice; print('OK')"`
- [ ] Open `config.py` in a text editor:
  - [ ] Set `PASSWORD` to your real event password.
  - [ ] Leave `USB_INPUT_DEVICE` / `USB_OUTPUT_DEVICE` as `None` for now if
        you're not wiring up the USB interface yet.
- [ ] If using the USB audio interface: plug it in, then run
      `python list_audio_devices.py` and copy the exact device name (or
      index number) into `config.py`.
- [ ] Open **Windows Firewall** → Advanced Settings → Inbound Rules → New
      Rule → Port → UDP → `5005` → Allow the connection → apply to
      Private (and Domain, if applicable) networks.
- [ ] Run the smoke test: `python test_server.py` — you should see six
      lines all starting with `PASS`.
- [ ] Start the real server: run **`python admin_gui.py`** for the dashboard
      (see who's connected/talking, mute phones, send messages, view logs),
      or `python server.py` for a plain console-only run. You should see
      `Intercom server listening on 0.0.0.0:5005` either way.
- [ ] Find the server's LAN IP address: open Command Prompt and run
      `ipconfig`, note the `IPv4 Address` (e.g. `192.168.1.10`). You'll type
      this into each phone.

---

## Part 3 — Build the APK (browser-only, via GitHub Actions cloud build)

Since the build machine stays fully offline, skip Android Studio entirely
and let GitHub's servers build the APK instead. Full details are in
**`BUILD_APK_VIA_GITHUB.md`** — short version:

- [ ] Create a free GitHub account (browser only).
- [ ] Create a new repository and upload the whole `intercom-system` folder
      to it (drag-and-drop in the browser; the included
      `.github/workflows/build.yml` does the actual build automatically).
- [ ] Open the **Actions** tab, wait for the build to turn green
      (~5-10 minutes).
- [ ] Download the `field-intercom-debug-apk` artifact, unzip it to get
      `app-debug.apk`.
- [ ] Copy `app-debug.apk` onto a USB drive.
- [ ] On each phone: open the `.apk` file with a file manager, allow
      "install from this source" when prompted, tap **Install**.
- [ ] Grant the microphone permission when the app first launches.
- [ ] Enter the server's LAN IP address and the password.
- [ ] Confirm the status banner turns green ("Connected").

---

## Part 4 — Tune the Ubiquiti WiFi network

- [ ] Create (or confirm) a 5GHz-capable SSID for the intercom phones.
- [ ] Confirm WMM (WiFi Multimedia) / voice QoS is enabled on the APs.
- [ ] If many phones share one AP, consider a dedicated SSID/VLAN just for
      the intercom so it isn't competing with guest WiFi or streaming.
- [ ] Walk the venue with a phone to confirm WiFi coverage reaches every
      area intercom users will be in.

---

## Part 5 — Event-day checks

- [ ] Power on the server computer and start `server.py` well before doors
      open.
- [ ] If using the USB interface, confirm it's recognized (check for its
      startup log lines) before relying on it.
- [ ] Test with two phones: confirm both directions of audio work, then
      have one walk out of WiFi range and back — banner should go red then
      green automatically, no button presses.
- [ ] Open **Test latency** on a phone at the far edge of your venue's WiFi
      coverage to see real-world numbers, not just a same-room best case.
- [ ] If your event runs longer than 8 hours, plan for everyone to
      re-enter the password partway through (sessions expire automatically
      at 8 hours, per spec).
