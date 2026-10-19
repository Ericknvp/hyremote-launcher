#!/system/bin/sh
# Starts the HyRemote launcher when the system comes up.
#
# Why this exists: Android 12+ freezes apps that stay in the background, and
# a frozen app does not receive broadcasts - the BOOT_COMPLETED is deferred
# ("Deferred BOOT_COMPLETED broadcasts") until the app is thawed. On this
# projector the vendor launcher (com.jm.jmlauncher) takes the screen at boot,
# so HyRemote stays cached, gets frozen, and never hears the boot. The
# launcher would then only appear if the user pressed HOME.
#
# init runs this as root, and starting an app explicitly thaws it, so the
# launcher always comes up on its own.

LOG=/data/local/tmp/hyremote/launch.log
mkdir -p /data/local/tmp/hyremote

# Only if HyRemote is the home app. If the user put the stock launcher back,
# this script must stay out of the way instead of jumping on top of it.
#
# The check has to wait: at class late_start the system has not resolved the
# HOME category yet, and it answers with the stock launcher for a while. So
# the home is polled for a couple of minutes before giving up.
is_home() {
    HOME_ACTIVITY=$(/system/bin/cmd package resolve-activity --brief \
        -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null)
    case "$HOME_ACTIVITY" in
        *com.hyremote.ctrl*) return 0 ;;
        *) return 1 ;;
    esac
}

waited=0
while [ $waited -lt 36 ]; do
    if is_home; then
        break
    fi
    sleep 5
    waited=$((waited + 1))
done

if ! is_home; then
    echo "[$(date '+%H:%M:%S')] launch: no somos el launcher, no arrancamos nada" >> "$LOG"
    exit 0
fi

# We are the home app, so the stock launcher has to stay off. The HOME key
# goes to the system's default home package - the stock one - no matter what
# the intents resolve to, and the vendor re-enables its own launcher after
# some reboots.
pm disable-user --user 0 com.htc.htclauncherhighenglishd08 >/dev/null 2>&1
echo "[$(date '+%H:%M:%S')] launch: launcher de fabrica desactivado" >> "$LOG"

# `am` prints an error but still exits with 0 when the package is frozen or
# the activity manager is not up yet, so the output is what decides.
try_am() {
    OUT=$(/system/bin/am start -n com.hyremote.ctrl/.MainActivity 2>&1)
    echo "$OUT" >> "$LOG"
    case "$OUT" in
        *Error* | *"Can't find service"*) return 1 ;;
        *) return 0 ;;
    esac
}

# Fallback: monkey is the launch method this firmware really honours.
try_monkey() {
    OUT=$(/system/bin/monkey -p com.hyremote.ctrl -c android.intent.category.LAUNCHER 1 2>&1)
    echo "$OUT" >> "$LOG"
    case "$OUT" in
        *Events\ injected*) return 0 ;;
        *) return 1 ;;
    esac
}

i=0
while [ $i -lt 60 ]; do
    sleep 6
    i=$((i + 1))
    # monkey first: `am start` is refused while the package is still frozen
    # ("Activity class does not exist"), and monkey goes through anyway.
    if try_monkey || try_am; then
        echo "[$(date '+%H:%M:%S')] launch: launcher arrancado (intento $i)" >> "$LOG"
        exit 0
    fi
done

echo "[$(date '+%H:%M:%S')] launch: no se pudo arrancar el launcher" >> "$LOG"
