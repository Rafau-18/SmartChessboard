// Smart Chessboard — NimBLE peripheral implementation. See ble_service.h for
// the architecture (producer/consumer spine; the scan task owns `stable`).
//
// Ported from the ESP-IDF `bleprph` example for IDF 6.0 / NimBLE: one primary
// service with `board_event` (notify) + `mobile_command` (write), Just-Works
// bonding persisted to NVS, single central, service UUID in the advertisement
// and the device name in the scan response (a 128-bit UUID + a 20-char name
// overflows the 31-byte legacy advertisement). Notify uses
// ble_gatts_notify_custom (verified against the installed NimBLE headers — old
// NimBLE used ble_gattc_notify_custom).

#include "ble_service.h"

#include <cstdio>
#include <cstring>

#include "esp_log.h"
#include "esp_mac.h"
#include "esp_timer.h"
#include "nvs_flash.h"

#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"

#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

// Provided by NimBLE's NVS-backed store (CONFIG_BT_NIMBLE_NVS_PERSIST=y).
extern "C" void ble_store_config_init(void);

namespace {

const char* TAG = "ble";

// DEVICE_STATUS constants (§1.3 / plan Critical Details): battery is a fixed
// USB constant in the 0–100 range — not a real reading; firmware is 1.0.0.
constexpr uint8_t kBatteryPctUsb = 100;
constexpr uint8_t kFwMajor = 1;
constexpr uint8_t kFwMinor = 0;
constexpr uint8_t kFwPatch = 0;

// GATT UUIDs — recorded in docs/reference/contract-surfaces.md §1.2.
// BLE_UUID128_INIT takes the 16 bytes little-endian (LSB first), i.e. the
// canonical UUID string reversed.
//   Service        787e0001-15a4-4fc9-a469-05096dbad1a1
//   board_event    787e0002-15a4-4fc9-a469-05096dbad1a1  (notify)
//   mobile_command 787e0003-15a4-4fc9-a469-05096dbad1a1  (write)
const ble_uuid128_t kSvcUuid = BLE_UUID128_INIT(
    0xa1, 0xd1, 0xba, 0x6d, 0x09, 0x05, 0x69, 0xa4,
    0xc9, 0x4f, 0xa4, 0x15, 0x01, 0x00, 0x7e, 0x78);
const ble_uuid128_t kBoardEventUuid = BLE_UUID128_INIT(
    0xa1, 0xd1, 0xba, 0x6d, 0x09, 0x05, 0x69, 0xa4,
    0xc9, 0x4f, 0xa4, 0x15, 0x02, 0x00, 0x7e, 0x78);
const ble_uuid128_t kMobileCommandUuid = BLE_UUID128_INIT(
    0xa1, 0xd1, 0xba, 0x6d, 0x09, 0x05, 0x69, 0xa4,
    0xc9, 0x4f, 0xa4, 0x15, 0x03, 0x00, 0x7e, 0x78);

uint8_t  s_own_addr_type;
uint16_t s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
uint16_t s_board_event_handle;
uint16_t s_mobile_command_handle;
volatile bool s_subscribed = false;
char s_device_name[24];  // "SmartChessboard-XXXX" = 20 chars + NUL

QueueHandle_t s_out_queue;  // board_protocol::Frame → consumer task notifies
QueueHandle_t s_req_queue;  // ble_service::Request → producer (scan loop)

// Forward declarations (gap_event ⇄ start_advertising are mutually recursive).
int gap_event(struct ble_gap_event* event, void* arg);
int gatt_access(uint16_t conn_handle, uint16_t attr_handle,
                struct ble_gatt_access_ctxt* ctxt, void* arg);
void start_advertising();

// One primary service, two characteristics. NimBLE auto-adds the CCCD (0x2902)
// for the notify characteristic. Named arrays (not inline GNU compound
// literals) keep this valid C++. Every field is initialized — ESP-IDF builds
// with -Werror=missing-field-initializers.
const struct ble_gatt_chr_def k_chrs[] = {
    {
        .uuid = &kBoardEventUuid.u,
        .access_cb = gatt_access,
        .arg = nullptr,
        .descriptors = nullptr,
        .flags = BLE_GATT_CHR_F_NOTIFY,
        .min_key_size = 0,
        .val_handle = &s_board_event_handle,
        .cpfd = nullptr,
    },
    {
        .uuid = &kMobileCommandUuid.u,
        .access_cb = gatt_access,
        .arg = nullptr,
        .descriptors = nullptr,
        .flags = BLE_GATT_CHR_F_WRITE,
        .min_key_size = 0,
        .val_handle = &s_mobile_command_handle,
        .cpfd = nullptr,
    },
    {},  // terminator (uuid == NULL)
};

const struct ble_gatt_svc_def k_svcs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &kSvcUuid.u,
        .includes = nullptr,
        .characteristics = k_chrs,
    },
    {},  // terminator (type == 0)
};

// ---- GATT access ----------------------------------------------------------

int gatt_access(uint16_t /*conn_handle*/, uint16_t attr_handle,
                struct ble_gatt_access_ctxt* ctxt, void* /*arg*/) {
    if (ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR &&
        attr_handle == s_mobile_command_handle) {
        // Phase 2: accept the write and return success. Decoding the command via
        // board_protocol::decodeCommand and dispatching SET_MODE / REQUEST_* is
        // Phase 3 work; per the contract a malformed/unknown write is a silent
        // no-op (never an ATT error), so succeeding here is forward-safe.
        return 0;
    }
    return BLE_ATT_ERR_UNLIKELY;
}

// ---- Advertising + GAP lifecycle ------------------------------------------

void start_advertising() {
    // Advertisement: flags + the 128-bit service UUID (18 B) — fits the 31-byte
    // legacy budget alongside the 3-byte flags.
    struct ble_hs_adv_fields adv_fields;
    memset(&adv_fields, 0, sizeof adv_fields);
    adv_fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    adv_fields.uuids128 = const_cast<ble_uuid128_t*>(&kSvcUuid);
    adv_fields.num_uuids128 = 1;
    adv_fields.uuids128_is_complete = 1;

    int rc = ble_gap_adv_set_fields(&adv_fields);
    if (rc != 0) {
        ESP_LOGE(TAG, "adv_set_fields rc=%d", rc);
        return;
    }

    // Scan response carries the full name (too long to share the adv packet).
    struct ble_hs_adv_fields rsp_fields;
    memset(&rsp_fields, 0, sizeof rsp_fields);
    rsp_fields.name = reinterpret_cast<uint8_t*>(s_device_name);
    rsp_fields.name_len = static_cast<uint8_t>(strlen(s_device_name));
    rsp_fields.name_is_complete = 1;

    rc = ble_gap_adv_rsp_set_fields(&rsp_fields);
    if (rc != 0) {
        ESP_LOGE(TAG, "adv_rsp_set_fields rc=%d", rc);
        return;
    }

    struct ble_gap_adv_params adv_params;
    memset(&adv_params, 0, sizeof adv_params);
    adv_params.conn_mode = BLE_GAP_CONN_MODE_UND;  // undirected connectable
    adv_params.disc_mode = BLE_GAP_DISC_MODE_GEN;   // general discoverable

    rc = ble_gap_adv_start(s_own_addr_type, NULL, BLE_HS_FOREVER,
                           &adv_params, gap_event, NULL);
    if (rc != 0) {
        ESP_LOGE(TAG, "adv_start rc=%d", rc);
    }
}

int gap_event(struct ble_gap_event* event, void* /*arg*/) {
    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        if (event->connect.status == 0) {
            s_conn_handle = event->connect.conn_handle;
            s_subscribed = false;  // central must (re)enable the CCCD
            ESP_LOGI(TAG, "central connected; handle=%d", s_conn_handle);
        } else {
            ESP_LOGW(TAG, "connect failed; status=%d — re-advertising",
                     event->connect.status);
            start_advertising();
        }
        return 0;

    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "central disconnected; reason=%d — re-advertising",
                 event->disconnect.reason);
        s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
        s_subscribed = false;
        start_advertising();  // FR-FW-012: reconnectable without power cycle
        return 0;

    case BLE_GAP_EVENT_SUBSCRIBE:
        if (event->subscribe.attr_handle == s_board_event_handle) {
            s_subscribed = event->subscribe.cur_notify;
            ESP_LOGI(TAG, "board_event subscribe; cur_notify=%d",
                     event->subscribe.cur_notify);
            if (event->subscribe.cur_notify) {
                // The contract's "on connect emit BOARD_SNAPSHOT then
                // DEVICE_STATUS" maps to CCCD subscribe (a notify with no
                // subscriber is dropped). Hand the burst to the producer so the
                // snapshot is built from `stable` on the task that owns it.
                ble_service::Request burst = ble_service::Request::Burst;
                if (s_req_queue) {
                    xQueueSend(s_req_queue, &burst, 0);
                }
            }
        }
        return 0;

    case BLE_GAP_EVENT_ADV_COMPLETE:
        start_advertising();
        return 0;

    case BLE_GAP_EVENT_REPEAT_PAIRING: {
        // Already bonded but the peer wants a fresh secure link: drop the old
        // bond and let pairing proceed (trust-on-first-pair, contract §1.8).
        struct ble_gap_conn_desc desc;
        if (ble_gap_conn_find(event->repeat_pairing.conn_handle, &desc) == 0) {
            ble_store_util_delete_peer(&desc.peer_id_addr);
        }
        return BLE_GAP_REPEAT_PAIRING_RETRY;
    }

    default:
        return 0;
    }
}

// ---- Notify consumer task -------------------------------------------------

void consumer_task(void*) {
    board_protocol::Frame frame;
    for (;;) {
        if (xQueueReceive(s_out_queue, &frame, portMAX_DELAY) != pdTRUE) {
            continue;
        }
        // Dead/unsubscribed link delivers nothing (contract §1.7).
        if (s_conn_handle == BLE_HS_CONN_HANDLE_NONE || !s_subscribed) {
            continue;
        }
        struct os_mbuf* om = ble_hs_mbuf_from_flat(frame.bytes, frame.len);
        if (om == NULL) {
            ESP_LOGW(TAG, "mbuf alloc failed; dropping frame");
            continue;
        }
        // ble_gatts_notify_custom consumes `om` regardless of result.
        int rc = ble_gatts_notify_custom(s_conn_handle, s_board_event_handle, om);
        if (rc != 0) {
            ESP_LOGW(TAG, "notify rc=%d", rc);
        }
    }
}

// ---- NimBLE host plumbing -------------------------------------------------

void on_reset(int reason) {
    ESP_LOGE(TAG, "nimble host reset; reason=%d", reason);
}

void on_sync() {
    int rc = ble_hs_util_ensure_addr(0);
    if (rc != 0) {
        ESP_LOGE(TAG, "ensure_addr rc=%d", rc);
        return;
    }
    rc = ble_hs_id_infer_auto(0, &s_own_addr_type);
    if (rc != 0) {
        ESP_LOGE(TAG, "infer_auto rc=%d", rc);
        return;
    }
    start_advertising();
}

void host_task(void*) {
    ESP_LOGI(TAG, "nimble host task started");
    nimble_port_run();  // returns only on nimble_port_stop()
    nimble_port_freertos_deinit();
}

void make_device_name() {
    uint8_t mac[6] = {0};
    esp_read_mac(mac, ESP_MAC_BT);  // last 2 bytes → 4 hex chars (contract §1.1)
    snprintf(s_device_name, sizeof s_device_name, "SmartChessboard-%02X%02X",
             mac[4], mac[5]);
}

}  // namespace

// ---- Public API -----------------------------------------------------------

namespace ble_service {

void init() {
    // NVS is needed for both PHY calibration and persisted bonding keys.
    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);

    s_out_queue = xQueueCreate(16, sizeof(board_protocol::Frame));
    s_req_queue = xQueueCreate(8, sizeof(Request));
    configASSERT(s_out_queue != NULL && s_req_queue != NULL);

    ret = nimble_port_init();
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "nimble_port_init failed; rc=%d", ret);
        return;
    }

    ble_hs_cfg.reset_cb = on_reset;
    ble_hs_cfg.sync_cb = on_sync;
    ble_hs_cfg.store_status_cb = ble_store_util_status_rr;

    // "Just Works" pairing (no input/output), bonded, secure-connections, with
    // the encryption key distributed both ways and persisted to NVS.
    ble_hs_cfg.sm_io_cap = BLE_HS_IO_NO_INPUT_OUTPUT;
    ble_hs_cfg.sm_bonding = 1;
    ble_hs_cfg.sm_sc = 1;
    ble_hs_cfg.sm_our_key_dist |= BLE_SM_PAIR_KEY_DIST_ENC;
    ble_hs_cfg.sm_their_key_dist |= BLE_SM_PAIR_KEY_DIST_ENC;

    ble_svc_gap_init();
    ble_svc_gatt_init();

    int rc = ble_gatts_count_cfg(k_svcs);
    configASSERT(rc == 0);
    rc = ble_gatts_add_svcs(k_svcs);
    configASSERT(rc == 0);

    make_device_name();
    rc = ble_svc_gap_device_name_set(s_device_name);
    configASSERT(rc == 0);

    ble_store_config_init();

    nimble_port_freertos_init(host_task);

    xTaskCreate(consumer_task, "ble_tx", 4096, NULL, 5, NULL);

    ESP_LOGI(TAG, "BLE init done; advertising as %s", s_device_name);
}

bool poll_request(Request& out) {
    if (s_req_queue == NULL) {
        return false;
    }
    return xQueueReceive(s_req_queue, &out, 0) == pdTRUE;
}

void enqueue_frame(const board_protocol::Frame& frame) {
    if (s_out_queue != NULL) {
        xQueueSend(s_out_queue, &frame, 0);
    }
}

board_protocol::Frame build_status_frame() {
    uint32_t uptime_s = static_cast<uint32_t>(esp_timer_get_time() / 1000000);
    return board_protocol::encodeDeviceStatus(kBatteryPctUsb, kFwMajor, kFwMinor,
                                              kFwPatch, uptime_s);
}

}  // namespace ble_service
