.pragma library

// Small text helpers the pages share. The numbers themselves come from the shared core.

function clock(totalSeconds) {
    var s = Math.max(0, Math.round(totalSeconds || 0))
    var h = Math.floor(s / 3600)
    var m = Math.floor((s % 3600) / 60)
    var sec = s % 60
    var mm = (h > 0 && m < 10 ? "0" : "") + m
    return (h > 0 ? h + ":" : "") + mm + ":" + (sec < 10 ? "0" : "") + sec
}

function duration(totalSeconds) {
    if ((totalSeconds || 0) < 60)
        return qsTr("%1 s").arg(Math.round(totalSeconds || 0))
    var mins = Math.round(totalSeconds / 60)
    if (mins < 60)
        return qsTr("%1 min").arg(mins)
    if (mins % 60 === 0)
        return qsTr("%1 h").arg(mins / 60)
    return qsTr("%1 h %2 min").arg(Math.floor(mins / 60)).arg(mins % 60)
}

// The zone's colour from the core's `zones` topic, or a neutral one before it has arrived.
function zoneColor(zones, number, fallback) {
    if (zones && number >= 1 && number <= zones.length)
        return zones[number - 1].color
    return fallback
}

function zoneForPct(zones, pct) {
    if (!zones)
        return 1
    for (var i = 0; i < zones.length; ++i) {
        if (pct <= zones[i].ftpHighPct)
            return zones[i].number
    }
    return 7
}
