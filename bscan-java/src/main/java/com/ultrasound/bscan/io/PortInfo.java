package com.ultrasound.bscan.io;

/**
 * Information about an available serial communication port.
 */
public record PortInfo(String systemPortName, String descriptivePortName, boolean isUsbCandidate) {

    @Override
    public String toString() {
        return systemPortName + " - " + descriptivePortName;
    }
}
