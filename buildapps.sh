#!/system/bin/sh
# Builds "package|label|iconDataUri" lines. Kept as a file because the
# quoting is fragile through adb shell.
LIST="/sdcard/Android/data/com.hyremote.ctrl/files/apps.txt"
ICON_DIR="/sdcard/Android/data/com.hyremote.ctrl/files"

[ -f "$LIST" ] || exit 0

while IFS='|' read -r PKG LBL REST; do
  [ -z "$PKG" ] && continue
  ICON=""
  SAFE=$(echo "$PKG" | tr '.' '_')
  F="$ICON_DIR/icon_$SAFE.png"
  if [ -f "$F" ]; then
    B64=$(base64 "$F" 2>/dev/null | tr -d '\n')
    if [ -n "$B64" ]; then
      ICON="data:image/png;base64,$B64"
    fi
  fi
  LBL=$(echo "$LBL" | tr '|' '/')
  printf '%s|%s|%s\n' "$PKG" "$LBL" "$ICON"
done < "$LIST"