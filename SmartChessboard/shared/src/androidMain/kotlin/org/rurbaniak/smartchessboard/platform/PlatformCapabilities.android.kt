package org.rurbaniak.smartchessboard.platform

// Android drives the physical board over BLE (the emulated BoardConnection in S-06; the real adapter in S-09).
actual val supportsPhysicalBoard: Boolean = true
