#!/system/bin/sh
# q5q_cameraserver_hr v4 - report the outcome, and try to start the service if boot missed it.
LOG=/data/adb/q5q_cam_module.log
log() { echo "$(date '+%m-%d %H:%M:%S') $*" >> $LOG; }
for i in 1 2 3 4 5 6; do
    sleep 5
    SVC=$(getprop init.svc.cameraserver)
    if [ "$SVC" = "running" ]; then
        log "service(v4): cameraserver RUNNING (patched binary active)"
        exit 0
    fi
done
log "service(v4): cameraserver not running (state=$SVC); attempting ctl.start"
setprop ctl.start cameraserver 2>/dev/null
sleep 6
log "service(v4): after ctl.start state=$(getprop init.svc.cameraserver)"
