#ifndef PIKO_XQUIC_BRIDGE_H
#define PIKO_XQUIC_BRIDGE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void (*PikoXQuicFrameCallback)(int64_t channel_handle, const uint8_t *data, int32_t size, void *user_data);
typedef void (*PikoXQuicClosedCallback)(int64_t channel_handle, void *user_data);

int32_t piko_xquic_is_linked(void);
int64_t piko_xquic_open_server(
    const char *bind_host,
    const char *alpn,
    const char *certificate_directory,
    PikoXQuicFrameCallback on_frame,
    PikoXQuicClosedCallback on_closed,
    void *user_data
);
int32_t piko_xquic_server_port(int64_t server_handle);
void piko_xquic_close_server(int64_t server_handle);
int64_t piko_xquic_open_client(
    const char *host,
    int32_t port,
    int32_t timeout_millis,
    const char *alpn,
    PikoXQuicFrameCallback on_frame,
    PikoXQuicClosedCallback on_closed,
    void *user_data
);
int32_t piko_xquic_send_frame(int64_t channel_handle, const uint8_t *data, int32_t size);
void piko_xquic_close_channel(int64_t channel_handle);

#ifdef __cplusplus
}
#endif

#endif
