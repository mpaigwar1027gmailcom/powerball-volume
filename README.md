# PowerBall Volume

A floating ball that stays on top of every app. Tap it to open sliders for
Media, Ringer, Notification, Alarm, System, In-call and Bluetooth volume.
It collapses back to a ball after a few seconds or when you tap outside.
Drag the ball to move it.

## Build the APK on GitHub
1. Create a new GitHub repo and upload everything in this folder (keep the `.github` folder).
2. Open the **Actions** tab -> **Build APK** -> wait for it to finish (or click *Run workflow*).
3. Open the finished run and download **PowerBallVolume-debug-apk** under *Artifacts*. Unzip it to get `app-debug.apk`.
4. Copy the APK to your phone and install it (allow "install unknown apps").

## First run on the phone
1. Allow **Display over other apps**.
2. Allow **Do Not Disturb access** (Android blocks setting ringer/notification/system to 0 without it).
3. Allow notifications (Android 13+), then tap **Start PowerBall**.

The ball also restarts after a reboot once the overlay permission is granted.
Some phones (Xiaomi, Oppo, Vivo, Samsung) need battery optimisation turned off for the app so it isn't killed.
