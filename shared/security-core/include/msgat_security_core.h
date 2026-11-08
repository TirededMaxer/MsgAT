/* SPDX-License-Identifier: AGPL-3.0-only */
#ifndef MSGAT_SECURITY_CORE_H
#define MSGAT_SECURITY_CORE_H
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    uint8_t *data;
    size_t length;
} MsgatBuffer;

/* The caller persists returned private state only in its encrypted database.
 * Input belongs to the caller. Output belongs to Rust; release it exactly once
 * using msgat_buffer_free. No Rust allocation may be released by the host CRT.
 * Return 0 means a valid response; inspect its ok field for operation success.
 * A nonzero return indicates an invalid ABI argument and provides no response. */
uint32_t msgat_security_abi_version(void);
int32_t msgat_security_call(const uint8_t *request, size_t length, MsgatBuffer *response);
void msgat_buffer_free(MsgatBuffer *buffer);

#ifdef __cplusplus
}
#endif
#endif
