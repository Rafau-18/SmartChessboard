# The device (esp32dev) build does NOT auto-discover firmware/lib/ as ESP-IDF
# components (that auto-discovery is the PlatformIO LDF, which only runs for the
# `native` host-test env). So the pure board_protocol codec is pulled in here by
# path; its header dir is added to the include path. `debounce` is wired in
# Phase 3 when main.cpp switches to it.
idf_component_register(
    SRCS
        "main.cpp"
        "ble_service.cpp"
        "../lib/board_protocol/board_protocol.cpp"
    INCLUDE_DIRS
        "."
        "../lib/board_protocol"
    REQUIRES
        bt            # NimBLE host
        nvs_flash     # persisted bonding keys + PHY calibration
        esp_hw_support # esp_read_mac (esp_mac.h)
        esp_timer     # uptime for DEVICE_STATUS
)
