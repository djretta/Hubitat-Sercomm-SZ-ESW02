
/**
 *  Sercomm SZ-ESW02 Zigbee Smart Plug Driver for Hubitat
 *  Version: 1.2.0
 *
 *  Hubitat Smart Plug driver designed to pass power monitoring data through from Hubitat to Home Assistant. 
 *  Likely does not conform to Hubitat driver standards, but works well for my use case.
 *  Majorly coded using Claude AI with some manual tuning, cleaning, and reviewing.
 *  Also works fine with SZ-ESW02N-CZ3
 *
 *  - Power: raw mW (INT24 BE) / 1,000 -> W
 *  - Offline detection: marks offline if no message received in last 10s
 */

metadata {
    definition(
        name: "Sercomm SZ-ESW02 Metering Plug",
        namespace: "community",
        author: "community"
    ) {
        capability "Actuator"
        capability "Switch"
        capability "Outlet"
        capability "PowerMeter"
        capability "Refresh"
        capability "Configuration"
        //capability "HealthCheck"

        attribute "healthStatus", "enum", ["offline", "online"]

        fingerprint profileId: "0104", endpointId: "01",
            inClusters: "0000,0003,0004,0005,0006,0702",
            outClusters: "0019",
            manufacturer: "Sercomm Corp.",
            model: "SZ-ESW02",
            deviceJoinName: "Sercomm SZ-ESW02 Smart Plug"
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable info logging",  defaultValue: true
    }
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

def installed() {
    configure()
}

def configure() {
    log.info "SZ-ESW02: configuring reporting"
    def cmds = []
    cmds += zigbee.configureReporting(0x0006, 0x0000, 0x10, 0, 300, null) // switch status
    cmds += zigbee.configureReporting(0x0702, 0x0400, 0x2A, 5, 10, 20)    // power consumption
    cmds += refresh()
    startOfflineChecker()
    return cmds
}

def updated() {
    log.info "SZ-ESW02: preferences saved"
    if (logEnable) runIn(1800, logsOff)
    configure()
    startOfflineChecker()
}

def refresh() {
    def cmds = []
    cmds += zigbee.readAttribute(0x0006, 0x0000)
    cmds += zigbee.readAttribute(0x0702, 0x0400)
    return cmds
}

// ---------------------------------------------------------------------------
// Switch commands
// ---------------------------------------------------------------------------

def on()  { return zigbee.on() }
def off() { return zigbee.off() }

// ---------------------------------------------------------------------------
// Message parsing
// ---------------------------------------------------------------------------

def parse(String description) {
    if (logEnable) log.debug "SZ-ESW02 parse: ${description}"

    if (!description?.startsWith("read attr -")) return

    def descMap = zigbee.parseDescriptionAsMap(description)
    if (!descMap || !descMap.value) return

    def cluster = descMap.cluster ?: descMap.clusterId
    def attrId  = descMap.attrId

    // OnOff (0x0006 / 0x0000)
    if (cluster == "0006" && attrId == "0000") {
        def sw = descMap.value == "01" ? "on" : "off"
        if (txtEnable) log.info "SZ-ESW02: switch -> ${sw}"
        sendEvent(name: "switch", value: sw)
        updateHealthStatus("online")
        return
    }

    // Simple Metering (0x0702)
    if (cluster == "0702" && attrId == "0400") {
        long rawMw = Long.parseLong(descMap.value, 16)
        if (rawMw > 0x7FFFFF) rawMw -= 0x1000000   // sign-extend 24-bit
        if (rawMw < 0) rawMw = 0
        double watts = (rawMw as double) / 1000.0
        if (txtEnable) log.info "SZ-ESW02: power -> ${watts} W  (raw ${rawMw} mW)"
        sendEvent(name: "power", value: watts, unit: "W")
        updateHealthStatus("online")
    }
}

// ---------------------------------------------------------------------------
// Offline checker - runs every 5s via cron, marks offline if >10s since last message
// ---------------------------------------------------------------------------

def startOfflineChecker() {
    unschedule(checkOffline)
    schedule("0/5 * * * * ? *", checkOffline)
}

def checkOffline() {
    def lastActivity = device.lastActivity
    if (lastActivity == null) return  // never heard from; leave status alone until first message

    long elapsed = now() - lastActivity.time
    if (elapsed > 20000) {
        if (logEnable) log.debug "SZ-ESW02: ${elapsed}ms since last activity - marking offline"
        updateHealthStatus("offline")
        if (device.currentValue("power") != 0) sendEvent(name: "power", value: 0, unit: "W")
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private void updateHealthStatus(String status) {
    if (device.currentValue("healthStatus") != status) {
        log.warn "SZ-ESW02: healthStatus -> ${status}"
        sendEvent(name: "healthStatus", value: status)
    }
}

def logsOff() {
    log.warn "SZ-ESW02: debug logging auto-disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}
