#!/system/bin/sh
# Fallback launcher list, used only when the APK has not written its own yet.
# Real labels come from the launcher app (PackageManager); this exists just so
# /apps never returns empty.
#
# NOTE: do not hardcode friendly names here. Guessing them is what made
# "NMVDO TV" open a different app than the one it was named after.

pm list packages -3 2>/dev/null | sed 's/^package://' | while read -r P; do
  [ -z "$P" ] && continue
  case "$P" in
    com.hyremote.ctrl|com.android.settings|com.android.systemui) continue ;;
  esac
  ACT=$(cmd package resolve-activity --brief \
          -c android.intent.category.LAUNCHER \
          -a android.intent.action.MAIN "$P" 2>/dev/null | grep '/' | tail -1)
  [ -z "$ACT" ] && continue
  echo "$P|${P##*.}"
done