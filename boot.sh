#!/system/bin/sh
# Brings the HyRemote bridge up. Started by init as root
# (/system/etc/init/hyremote.rc), which restarts it if it ever exits.
#
# Three bugs this file used to have, all fixed here:
#
#  1. Double start. RemoteService.onStartCommand runs more than once per boot
#     (once from BOOT_COMPLETED, again when the launcher opens), and each run
#     launched another supervisor. Nine loops used to coexist, fighting for
#     8080. The lock below makes a second run a no-op.
#
#  2. Blind pkill. It killed any nc on the box, including one belonging to
#     somebody else. It now only clears the listeners this bridge owns.
#
#  3. Two sources of truth. The app also wrote these scripts into
#     /data/local/tmp, so the version init ran depended on install order. The
#     copy in /system/etc/hyremote is now authoritative and is re-synced on
#     every start; /data/local/tmp is just the runtime dir.

SYS=/system/etc/hyremote
DIR=/data/local/tmp/hyremote
LOG=$DIR/bridge.log

# The mutex lives in /dev (tmpfs), not in $DIR: /data/local/tmp survives a
# reboot, so a lock left behind by a boot that was interrupted (reboot in the
# middle of the setup) would make every later boot exit with "another start in
# flight" and the bridge would never come up.
LOCK=/dev/hyremote-boot.lock

mkdir -p "$DIR"
rm -rf "$DIR/boot.lock" 2>/dev/null

# mkdir is atomic, so it is a real mutex: if another boot.sh is mid-setup this
# one exits instead of racing it to the port.
if ! mkdir "$LOCK" 2>/dev/null; then
  echo "[$(date '+%H:%M:%S')] boot: another start in flight, skipping" >> "$LOG"
  exit 0
fi

# Android 12+ freezes apps that stay in the background, and a frozen app does
# not receive broadcasts: the BOOT_COMPLETED is deferred until the app is
# thawed. The vendor launcher takes the screen at boot, so HyRemote would stay
# frozen and never hear the boot. Turning the freezer off lets the app's own
# receiver work; launch.sh (started by init) is the belt to this braces.
for i in 1 2 3 4 5 6 7 8 9 10; do
  if [ "$(settings get global cached_app_freezer 2>/dev/null)" = "disabled" ]; then
    break
  fi
  settings put global cached_app_freezer disabled >/dev/null 2>&1
  sleep 2
done
echo "[$(date '+%H:%M:%S')] boot: cached_app_freezer=$(settings get global cached_app_freezer 2>/dev/null)" >> "$LOG"

# Re-sync the authoritative scripts and web UI from /system on every start.
# It used to copy only when the file was missing, which meant a redeploy of
# index.html or handler.sh silently kept running the stale copy in $DIR.
if [ -d "$SYS" ]; then
  for f in "$SYS"/*.sh; do
    [ -f "$f" ] && cp -f "$f" "$DIR"/ 2>/dev/null
  done
  if [ -d "$SYS/www" ]; then
    mkdir -p "$DIR/www"
    cp -f "$SYS/www/"* "$DIR/www/" 2>/dev/null
  fi
  chmod 755 "$DIR"/*.sh 2>/dev/null
fi

# Clear only this bridge's leftovers, whatever port it settled on.
pkill -9 -f "nc .*$DIR/handler.sh" 2>/dev/null

echo "[$(date '+%H:%M:%S')] boot: starting bridge as uid=$(id -u)" >> "$LOG"

# Drop the mutex before exec. exec replaces this process, so the EXIT trap
# would never fire and the lock would outlive us - init's restart path would
# then find it held and refuse to ever bring the bridge back.
rmdir "$LOCK" 2>/dev/null

# Foreground and exec'd: init owns the supervision, so boot.sh does not return
# while the listener is up. This is also why no sleep belongs here - serve.sh
# binds 8080 on its first command.
exec sh "$DIR/serve.sh" >> "$LOG" 2>&1
