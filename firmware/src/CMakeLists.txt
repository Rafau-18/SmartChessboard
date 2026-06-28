# The device (esp32dev) build does NOT auto-discover firmware/lib/ as ESP-IDF
# components (that auto-discovery is the PlatformIO LDF, which only runs for the
# `native` host-test env). So the pure board_protocol + debounce units are pulled
# in here by path; their header dirs are added to the include path. main.cpp uses
# lib/debounce for the matrix debounce as of Phase 3.
idf_component_register(
    SRCS
        "main.cpp"
        "ble_service.cpp"
        "../lib/board_protocol/board_protocol.cpp"
        "../lib/debounce/debounce.cpp"
    INCLUDE_DIRS
        "."
        "../lib/board_protocol"
        "../lib/debounce"
    REQUIRES
        bt            # NimBLE host
        nvs_flash     # persisted bonding keys + PHY calibration
        esp_hw_support # esp_read_mac (esp_mac.h)
        esp_timer     # uptime for DEVICE_STATUS
        esp_adc       # ADC1 one-shot for the confirmation buttons (DGT ~1.5V)
)
