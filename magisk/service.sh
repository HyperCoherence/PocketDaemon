#!/system/bin/sh
# Runs after boot (late_start service mode) as root.
# Auto-grants runtime permissions so the app never shows a dialog.

PKG=com.pocketdaemon.pocket_daemon

while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
done

# Wait for package manager to be fully ready
sleep 5

# Location — coarse must precede fine, both must precede background
pm grant $PKG android.permission.ACCESS_COARSE_LOCATION 2>/dev/null
pm grant $PKG android.permission.ACCESS_FINE_LOCATION 2>/dev/null
pm grant $PKG android.permission.ACCESS_BACKGROUND_LOCATION 2>/dev/null

# Contacts
pm grant $PKG android.permission.READ_CONTACTS 2>/dev/null
pm grant $PKG android.permission.WRITE_CONTACTS 2>/dev/null

# Phone / audio / notifications (may already be granted, harmless to re-grant)
pm grant $PKG android.permission.RECORD_AUDIO 2>/dev/null
pm grant $PKG android.permission.READ_PHONE_STATE 2>/dev/null
pm grant $PKG android.permission.READ_CALL_LOG 2>/dev/null
pm grant $PKG android.permission.ANSWER_PHONE_CALLS 2>/dev/null
pm grant $PKG android.permission.CALL_PHONE 2>/dev/null
pm grant $PKG android.permission.SEND_SMS 2>/dev/null
pm grant $PKG android.permission.BLUETOOTH_CONNECT 2>/dev/null
pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
pm grant $PKG android.permission.CAMERA 2>/dev/null

# External storage — broad access for persistent data on /sdcard/PocketDaemon
appops set $PKG MANAGE_EXTERNAL_STORAGE allow 2>/dev/null

# YouTube: keep package enabled but hide launcher activity so it can only be opened via agent
pm enable com.google.android.youtube 2>/dev/null
pm disable com.google.android.youtube/.app.honeycomb.Shell\$HomeActivity 2>/dev/null

# Assistant button: read setting from config.json and apply if enabled.
# Google reclaims the role during boot, so retry in a background loop.
CONFIG=/sdcard/PocketDaemon/config.json
(
    sleep 20
    if [ -f "$CONFIG" ] && grep -q '"assistantButton".*true' "$CONFIG"; then
        for i in 1 2 3 4 5; do
            settings put global power_button_long_press 5
            cmd role add-role-holder --user 0 android.app.role.ASSISTANT $PKG
            settings put secure assistant $PKG/.MainActivity
            sleep 15
        done
    fi
) &
