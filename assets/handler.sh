#!/system/bin/sh
# HyRemote bridge handler - one instance per connection, driven by nc -L.
#
# Two parsing rules learned the hard way:
#  1. Only the FIRST line is the request line. Later headers like
#     "Accept: */*" also contain a slash and would clobber the path.
#  2. Reading the body with `read -t` stalls for the full timeout because
#     the client waits on our response. Use dd with Content-Length instead,
#     which returns as soon as it has its bytes.

DIR=/data/local/tmp/hyremote
LOG=$DIR/bridge.log
METHOD=""
PATH_=""
LEN=""

while IFS= read -r LINE; do
  LINE="${LINE%$'\r'}"
  [ -z "$LINE" ] && break
  # Parse with parameter expansion only. Every subprocess (cut, tr) costs
  # ~60ms on this SoC, and this runs on every button press.
  case "$LINE" in
    POST\ *) METHOD="POST"; PATH_="${LINE#POST }"; PATH_="${PATH_%% *}" ;;
    GET\ *)  METHOD="GET";  PATH_="${LINE#GET }";  PATH_="${PATH_%% *}" ;;
    [Cc]ontent-[Ll]ength:*)
      LEN="${LINE#*: }"
      LEN="${LEN%%[!0-9]*}"
      ;;
  esac
done

BODY=""
if [ -n "$LEN" ] && [ "$LEN" -gt 0 ] 2>/dev/null; then
  BODY=$(dd bs=1 count="$LEN" 2>/dev/null)
fi

case "$PATH_" in
/ping)
    printf 'HTTP/1.1 200 OK\r\nContent-Length: 4\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\npong'
    ;;
/status)
    printf 'HTTP/1.1 200 OK\r\nContent-Length: 34\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n{"ok":true,"service":"hyremote-bridge"}'
    ;;
/key)
    # Accept only pure digits; anything else is ignored. Validation by pattern
    # avoids forking tr on the hot path.
    KEY=""
    case "$BODY" in
      ''|*[!0-9]*) ;;
      *) KEY="$BODY" ;;
    esac
    if [ -n "$KEY" ]; then
      input keyevent "$KEY" >/dev/null 2>&1
      printf 'HTTP/1.1 200 OK\r\nContent-Length: 9\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n{"ok":1}'
      printf 'key %s\n' "$KEY" >> "$LOG"
    else
      printf 'HTTP/1.1 200 OK\r\nContent-Length: 9\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n{"ok":0}'
    fi
    ;;
/tap)
    # Body: "x,y" in device pixels. Screen size comes from wm size at runtime.
    COORD="$BODY"
    X="${COORD%%,*}"
    Y="${COORD##*,}"
    case "$X$Y" in
      ''|*[!0-9,]*) X=""; Y="" ;;
    esac
    if [ -n "$X" ] && [ -n "$Y" ]; then
      input tap "$X" "$Y" >/dev/null 2>&1
      printf 'HTTP/1.1 200 OK\r\nContent-Length: 9\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n{"ok":1}'
      printf 'tap %s,%s\n' "$X" "$Y" >> "$LOG"
    else
      printf 'HTTP/1.1 200 OK\r\nContent-Length: 9\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n{"ok":0}'
    fi
    ;;
/swipe)
    # Body: "x1,y1,x2,y2,duration"
    C="$BODY"
    A="${C%%,*}"; R1="${C#*,}"; B="${R1%%,*}"; R2="${R1#*,}"
    D="${R2%%,*}"; E="${R2##*,}"
    case "$A$B$D$E" in
      ''|*[!0-9,]*) A=""; B=""; D=""; E="" ;;
    esac
    if [ -n "$A" ] && [ -n "$B" ] && [ -n "$D" ] && [ -n "$E" ]; then
      [ -z "$D" ] && D=300
      input swipe "$A" "$B" "$D" "$E" >/dev/null 2>&1
      printf 'HTTP/1.1 200 OK\r\nContent-Length: 9\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n{"ok":1}'
      printf 'swipe %s,%s,%s,%s\n' "$A" "$B" "$D" "$E" >> "$LOG"
    else
      printf 'HTTP/1.1 200 OK\r\nContent-Length: 9\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n{"ok":0}'
    fi
    ;;
/apps)
  # One "package|label|iconDataUri" per line.
  #
  # Icons are embedded rather than fetched one by one: the bridge handles a
  # single connection at a time, so a browser requesting 24 images in parallel
  # only got a few. The payload is ~420KB, which is too big to build per
  # request (~5s) and too big to stream reliably, so it is cached to a file and
  # served with cat.
  CACHE="$DIR/apps_built.txt"
  SRC="/sdcard/Android/data/com.hyremote.ctrl/files/apps.txt"
  REBUILD=0

  # Rebuild when the launcher has published a newer list.
  if [ ! -f "$CACHE" ]; then
    REBUILD=1
  elif [ "$SRC" -nt "$CACHE" ] 2>/dev/null; then
    REBUILD=1
  fi
  [ "$REBUILD" = "1" ] && sh "$DIR/buildapps.sh" > "$CACHE" 2>/dev/null

  if [ -s "$CACHE" ]; then
    SIZE=$(wc -c < "$CACHE")
    printf 'HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: %s\r\nConnection: close\r\n\r\n' "$SIZE"
    cat "$CACHE"
  else
    printf 'HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: 0\r\nConnection: close\r\n\r\n'
  fi
  ;;
/icon*)
  # App icon PNG published by the launcher. The file name arrives in the query
  # string so a plain <img src="/icon?name=..."> works without a POST body.
  ICON=""
  case "$PATH_" in
    *name=*) ICON="${PATH_##*name=}" ;;
  esac
  ICON=$(printf '%s' "$ICON" | tr -cd 'A-Za-z0-9_.\-')
  FILE="/sdcard/Android/data/com.hyremote.ctrl/files/$ICON"
  if [ -n "$ICON" ] && [ -f "$FILE" ]; then
    printf 'HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nCache-Control: max-age=86400\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: %s\r\nConnection: close\r\n\r\n' \
      "$(wc -c < "$FILE")"
    cat "$FILE"
  else
    printf 'HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n'
  fi
  ;;
/volume)
  # Real volume as "current/max/mute". Guessing it left the bar drifting from
  # whatever the projector actually had.
  # The speaker entry can be several lines under the stream header, so take a
  # wider window and stop at the first match.
  read_stream() {
    dumpsys audio 2>/dev/null \
      | sed -n "/STREAM_$1:/,/^-\{0,1\}[ ]*STREAM_\|^$/p" \
      | sed -n 's/.*speaker): \([0-9]*\).*/\1/p' | head -1
  }
  CUR=$(read_stream MUSIC)
  [ -z "$CUR" ] && CUR=0

  # The firmware caps the music stream at 50; the "default" values in the dump
  # belong to other streams, so read the max off STREAM_MUSIC itself.
  MAX=$(dumpsys audio 2>/dev/null \
        | sed -n "/STREAM_MUSIC:/,/^-\{0,1\}[ ]*STREAM_\|^$/p" \
        | sed -n 's/.*40000000 (default): \([0-9]*\).*/\1/p' | head -1)
  [ -z "$MAX" ] && MAX=50

  if [ "$CUR" -le 0 ] 2>/dev/null; then MUTED=1; else MUTED=0; fi

  OUT="$CUR/$MAX/$MUTED"
  printf 'HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: %s\r\nConnection: close\r\n\r\n%s' \
    "$(printf '%s' "$OUT" | wc -c)" "$OUT"
  ;;
/pkg)
    # Enables or disables an app. On a projector this is how a system app is
    # taken off the launcher: the package installer refuses to uninstall them.
    # Only a root process can do it, which is why the launcher asks the bridge.
    #
    # The package name is filtered to letters, digits, dot and underscore, so
    # this can never become a way to run something else.
    ACTION=""
    case "$BODY" in
      disable\ *) ACTION="disable-user --user 0" ;;
      enable\ *)  ACTION="enable --user 0" ;;
    esac
    PKG=$(printf '%s' "$BODY" | sed 's/^[a-z]* //')
    case "$PKG" in
      ''|*[!a-zA-Z0-9._]*) PKG="" ;;
    esac
    if [ -n "$PKG" ]; then
      pm $ACTION "$PKG" >/dev/null 2>&1
      printf 'HTTP/1.1 200 OK\r\nContent-Length: 9\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n{"ok":1}'
      printf 'pkg %s %s\n' "$ACTION" "$PKG" >> "$LOG"
    else
      printf 'HTTP/1.1 200 OK\r\nContent-Length: 9\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n{"ok":0}'
    fi
    ;;
/home)
    # Changes the default home app. Only the two known launchers are accepted,
    # so this endpoint can never become a way to run arbitrary commands.
    # Only a root process can set the home app, which is why the launcher asks
    # the bridge instead of doing it itself.
    #
    # Both halves are needed: `set-home-activity` sets the preferred activity
    # for the HOME category, but the ROLE_HOME role is what the system trusts
    # at boot - without it the stock launcher takes the screen back after a
    # reboot.
    TARGET=""
    PKG=""
    case "$BODY" in
      hyremote)
        TARGET="com.hyremote.ctrl/.MainActivity"
        PKG="com.hyremote.ctrl"
        # The HOME key is dispatched to the system's default home package,
        # which is the stock launcher, no matter what the intents resolve to.
        # Disabling it is what makes HOME land on HyRemote.
        pm disable-user --user 0 com.htc.htclauncherhighenglishd08 >/dev/null 2>&1
        ;;
      original)
        TARGET="com.htc.htclauncherhighenglishd08/com.mao.launcher.activity.BarrelActivity"
        PKG="com.htc.htclauncherhighenglishd08"
        pm enable --user 0 "$PKG" >/dev/null 2>&1
        ;;
    esac
    if [ -n "$TARGET" ]; then
      cmd package set-home-activity "$TARGET" >/dev/null 2>&1
      cmd role add-role-holder --user 0 android.app.role.HOME "$PKG" >/dev/null 2>&1
      printf 'HTTP/1.1 200 OK\r\nContent-Length: 9\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n{"ok":1}'
      printf 'home %s\n' "$TARGET" >> "$LOG"
    else
      printf 'HTTP/1.1 200 OK\r\nContent-Length: 9\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n{"ok":0}'
    fi
    ;;
/radio)
    # Turns the Bluetooth radio on or off. Only those two words are accepted,
    # so this endpoint can never become a way to run arbitrary commands.
    # Needed because Android 13 turned BluetoothAdapter.enable()/disable()
    # into no-ops for apps: only a root process can flip the radio.
    MODE=""
    case "$BODY" in
      on) MODE="enable" ;;
      off) MODE="disable" ;;
    esac
    if [ -n "$MODE" ]; then
      cmd bluetooth_manager $MODE >/dev/null 2>&1
      printf 'HTTP/1.1 200 OK\r\nContent-Length: 9\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n{"ok":1}'
      printf 'radio %s\n' "$MODE" >> "$LOG"
    else
      printf 'HTTP/1.1 200 OK\r\nContent-Length: 9\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n{"ok":0}'
    fi
    ;;
/focus)
  # Which app is actually in the foreground right now. Lets the UI tell the
  # truth instead of assuming the launch succeeded.
  FG=$(dumpsys window 2>/dev/null | sed -n 's/.*mCurrentFocus=.* \([a-z0-9._]*\)\/.*/\1/p' | head -1)
  [ -z "$FG" ] && FG=$(dumpsys activity activities 2>/dev/null \
      | sed -n 's/.*mResumedActivity.* \([a-z0-9._]*\)\/.*/\1/p' | head -1)
  printf 'HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: %s\r\nConnection: close\r\n\r\n%s' \
    "$(printf '%s' "$FG" | wc -c)" "$FG"
  ;;
/app)
    # Only [a-z.0-9._] survive, so nothing can escape into the shell command.
    PKG=$(printf '%s' "$BODY" | tr -cd 'a-zA-Z0-9._')
    if [ -n "$PKG" ]; then
      # monkey is the only launcher method verified on this firmware: the
      # apps do not expose a MainActivity, so `am start` with an explicit
      # component fails with "does not exist".
      RES=$(monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 2>&1)
      case "$RES" in
        *Events\ injected*)
          printf 'HTTP/1.1 200 OK\r\nContent-Length: 9\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n{"ok":1}'
          printf 'app %s\n' "$PKG" >> "$LOG"
          ;;
        *)
          printf 'HTTP/1.1 200 OK\r\nContent-Length: 9\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n{"ok":0}'
          ;;
      esac
    else
      printf 'HTTP/1.1 200 OK\r\nContent-Length: 9\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n{"ok":0}'
    fi
    ;;
/text)
    TXT=$(printf '%s' "$BODY" | tr -d '\r' | sed 's/%s/ /g' | tr -cd ' -~')
    if [ -n "$TXT" ]; then
      input text "$TXT" >/dev/null 2>&1
      printf 'HTTP/1.1 200 OK\r\nContent-Length: 9\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n{"ok":1}'
      printf 'text [%s]\n' "$TXT" >> "$LOG"
    else
      printf 'HTTP/1.1 200 OK\r\nContent-Length: 9\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n{"ok":0}'
    fi
    ;;
/)
    printf 'HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n'
    cat "$DIR/www/index.html"
    ;;
  *)
    printf 'HTTP/1.1 404 Not Found\r\nContent-Length: 9\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\nnot found'
    ;;
esac
