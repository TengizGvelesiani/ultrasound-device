package com.ultrasound.bscan.io;

import com.fazecast.jSerialComm.SerialPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Service for enumerating and detecting available COM ports.
 */
public final class PortDiscoveryService {

    public List<PortInfo> listAvailablePorts() {
        NativeLibraryBootstrap.prepareNativeLibraries();

        List<PortInfo> result = new ArrayList<>();
        try {
            SerialPort[] commPorts = SerialPort.getCommPorts();
            for (SerialPort port : commPorts) {
                String name = port.getSystemPortName();
                String desc = port.getDescriptivePortName();
                String full = (name + " " + desc).toUpperCase();

                boolean isUsb = full.contains("USB") || full.contains("FTDI")
                        || full.contains("UART") || full.contains("CH340")
                        || full.contains("CP210") || full.contains("SERIAL");

                result.add(new PortInfo(name, desc, isUsb));
            }
        } catch (Throwable t) {
            System.err.println("[PortDiscoveryService] Warning: Native serial port enumeration failed: " + t.getMessage());
        }

        return result;
    }

    public Optional<PortInfo> autoDetectUltrasoundPort() {
        List<PortInfo> ports = listAvailablePorts();
        List<PortInfo> usbCandidates = ports.stream()
                .filter(PortInfo::isUsbCandidate)
                .toList();

        if (usbCandidates.size() == 1) {
            return Optional.of(usbCandidates.getFirst());
        }
        return Optional.empty();
    }
}
