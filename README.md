# Punch Capture (Android)

Offline photo capture for variant-wise checkpoint data collection. Runs on the Zebra; no laptop or network needed.

## Install / update on the Zebra
1. Open this repo's **Releases** page in the Zebra's browser (sign in to GitHub if the repo is private).
2. Download the newest `PunchCapture-v1.0.N.apk` and open it. Allow "Install unknown apps" for the browser if asked.
3. First launch: tap **Open settings** and turn on **Allow access to manage all files**.

New versions install over the old one. Photos are never touched by an update.

## Where photos go
```
Internal storage/PunchCapture/
  <Variant>/interior/<checkpoint>_<Variant>/<Variant>_<checkpoint>_V001_01.jpg
  <Variant>/exterior/<checkpoint>_<Variant>/...
  config.json        ← checkpoints and colors per variant (also editable in the app)
  capture_log.csv    ← time, variant, vehicle, section, checkpoint, file, saved/retaken/deleted
  vehicles.csv       ← color chosen for each vehicle
```
To zip: home screen → **Zip photos…**, pick the date and time range. Zips go to `Download/PunchCapture_Zips/`.

To copy: plug the Zebra into the laptop with USB, choose **File transfer**, and copy the `PunchCapture` folder.

## Build
Every push to `main` builds the APK on GitHub Actions and publishes it as a Release.
Local build: Android Studio, or `gradle :app:assembleRelease` with the Android SDK installed.

`app/punch.keystore` is a project signing key kept in the repo so every build installs over the previous one.
