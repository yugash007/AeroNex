package com.aeronex.app.session

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubnetTest {
    private fun ip(text: String) = InetAddress.getByName(text)

    @Test fun anAddressIsOnLinkWhenItSharesTheInterfacePrefix() {
        assertTrue(Subnet.contains(ip("192.168.4.2"), 24, ip("192.168.4.1"))) // an ESP32 access point
        assertTrue(Subnet.contains(ip("10.0.0.5"), 24, ip("10.0.0.1"))) // ELRS Backpack
        assertTrue(Subnet.contains(ip("192.168.0.17"), 24, ip("192.168.0.5"))) // the PC running SITL
        assertTrue(Subnet.contains(ip("10.0.2.16"), 24, ip("10.0.2.2"))) // the emulator's host alias
        assertTrue(Subnet.contains(ip("172.16.9.1"), 12, ip("172.31.0.1")))
        assertTrue(Subnet.contains(ip("fe80::1"), 64, ip("fe80::abcd")))
    }

    @Test fun otherNetworksFamiliesAndLoopbackAreNot() {
        assertFalse(Subnet.contains(ip("192.168.4.2"), 24, ip("192.168.5.1")))
        assertFalse(Subnet.contains(ip("172.16.9.1"), 12, ip("172.32.0.1")))
        assertFalse(Subnet.contains(ip("192.168.4.2"), 24, ip("127.0.0.1")))
        assertFalse(Subnet.contains(ip("192.168.4.2"), 24, ip("::1")))
        assertFalse(Subnet.contains(ip("192.168.4.2"), 0, ip("127.0.0.1")))
    }
}
