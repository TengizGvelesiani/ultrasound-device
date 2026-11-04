package com.ultrasound.bscan.model;

/**
 * Listener for state change notifications.
 */
@FunctionalInterface
public interface StateChangeListener {
    void onStateChanged(ScannerState state);
}
