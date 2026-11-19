// SPDX-License-Identifier: AGPL-3.0-only
#ifndef MSGAT_PREVIEW_HTTP_H
#define MSGAT_PREVIEW_HTTP_H
#include <stddef.h>
#include <stdint.h>
typedef struct MsgatPreviewCancel MsgatPreviewCancel;
typedef struct { long status; char content_type[128]; char location[4097]; uint8_t *body; size_t length; } MsgatPreviewHTTPResult;
MsgatPreviewCancel *msgat_preview_cancel_create(void);
void msgat_preview_cancel(MsgatPreviewCancel *token);
void msgat_preview_cancel_free(MsgatPreviewCancel *token);
int msgat_preview_http_fetch(const char *url,const char *resolution,size_t maximum,long timeout_ms,MsgatPreviewCancel *token,MsgatPreviewHTTPResult *output);
const char *msgat_preview_http_location(const MsgatPreviewHTTPResult *output);
void msgat_preview_http_free(MsgatPreviewHTTPResult *output);
#endif
