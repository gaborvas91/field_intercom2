# Building the APK with only a browser (no local Android Studio/SDK needed)

This uses GitHub Actions to compile the APK on GitHub's own servers. You
never install Android Studio, the Android SDK, or Gradle anywhere. Every
step below is done in a browser tab.

- [ ] **Create a GitHub account** at https://github.com/join if you don't
      already have one (free).

- [ ] **Create a new repository**: click the **+** in the top-right corner
      → **New repository**. Name it e.g. `field-intercom`. Either Public or
      Private is fine — Private repos get free GitHub Actions build minutes
      too. Click **Create repository**.

- [ ] **Upload the project**: on the new repo's page, click
      **"uploading an existing file"** (or **Add file → Upload files**).
      Drag the entire `intercom-system` folder (from the zip you already
      have) into the browser window — modern GitHub preserves the folder
      structure, including the hidden `.github/workflows/build.yml` file
      that's already included and does the actual build. Scroll down and
      click **Commit changes**.

  > If your browser's upload doesn't seem to keep the `.github` folder
  > (some very old browsers hide dot-folders in file pickers), upload
  > everything else first, then click **Add file → Create new file**,
  > type the path `.github/workflows/build.yml` directly into the
  > filename box, and paste in the contents of that file from the zip.

- [ ] **Trigger the build**: click the **Actions** tab near the top of the
      repo. You should see a workflow run start automatically after your
      upload (or click **"Build APK"** in the left sidebar, then the
      **"Run workflow"** button on the right if it hasn't started).

- [ ] **Wait for it to finish** — usually 5-10 minutes. A green checkmark
      means success; a red X means something went wrong (click into the
      run and read the log — happy to help debug if you paste the error).

- [ ] **Download the APK**: click into the completed run, scroll down to
      the **Artifacts** section at the bottom, and click
      `field-intercom-debug-apk` to download a zip containing `app-debug.apk`.

- [ ] **Unzip it** (most browsers/OSes can do this natively — right-click →
      Extract) to get `app-debug.apk`.

- [ ] **Copy `app-debug.apk` onto a USB drive** and transfer it to each
      phone (or email it to yourself / use any file-transfer method you
      already use).

- [ ] **Install on each phone**: open the `.apk` file with a file manager
      app on the phone. Android will prompt to allow installing from that
      source the first time — allow it, then tap **Install**.

This is a normal "debug" build — Gradle signs it automatically with a
built-in debug key, which is completely fine for installing directly on
your own phones. It's only Play Store distribution that requires a
different signing process, which doesn't apply here.

**Making changes later**: if you edit any of the Kotlin/XML files, just
upload the changed files to the same GitHub repo (drag them into the file
list, or use **Add file → Upload files** again) — every push automatically
re-triggers the build, and a fresh APK shows up in Actions a few minutes
later.
