// SPDX-License-Identifier: AGPL-3.0-only
#ifndef MSGAT_STICKER_INFLATE_H
#define MSGAT_STICKER_INFLATE_H
#include <stddef.h>
#include <stdint.h>
int msgat_sticker_inflate(const uint8_t *input,size_t input_length,uint8_t *output,size_t output_length);
#endif
