package com.ultrasound.bscan;

import com.ultrasound.bscan.io.PortDiscoveryService;
import com.ultrasound.bscan.io.PortInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PortDiscoveryServiceTest {

    @Test
    void testPortEnumerationDoesNotCrash() {
        PortDiscoveryService service = new PortDiscoveryService();
        List<PortInfo> ports = service.listAvailablePorts();
        assertNotNull(ports);
        System.out.println("Discovered " + ports.size() + " COM ports: " + ports);
    }
}
