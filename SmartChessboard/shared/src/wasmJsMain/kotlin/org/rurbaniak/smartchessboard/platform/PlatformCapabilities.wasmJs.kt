package org.rurbaniak.smartchessboard.platform

// Web is digital-only: Web Bluetooth is too inconsistent cross-browser for MVP physical play (lessons.md).
actual val supportsPhysicalBoard: Boolean = false
