// SPDX-License-Identifier: AGPL-3.0-only
#include "include/PreviewHTTP.h"
#include <curl/curl.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>

struct MsgatPreviewCancel {atomic_bool cancelled;};
typedef struct {MsgatPreviewHTTPResult *result;size_t maximum;size_t header_bytes;} Transfer;
static pthread_once_t curl_once=PTHREAD_ONCE_INIT;
static void initialize_curl(void){curl_global_init(CURL_GLOBAL_DEFAULT);}
MsgatPreviewCancel *msgat_preview_cancel_create(void){MsgatPreviewCancel *p=calloc(1,sizeof(*p));if(p)atomic_init(&p->cancelled,false);return p;}
void msgat_preview_cancel(MsgatPreviewCancel *p){if(p)atomic_store(&p->cancelled,true);}
void msgat_preview_cancel_free(MsgatPreviewCancel *p){free(p);}
static int progress(void *context,curl_off_t a,curl_off_t b,curl_off_t c,curl_off_t d){(void)a;(void)b;(void)c;(void)d;return context && atomic_load(&((MsgatPreviewCancel*)context)->cancelled);}
static size_t receive_body(char *bytes,size_t size,size_t count,void *context){
    Transfer *t=context;if(size && count>SIZE_MAX/size)return 0;size_t n=size*count;
    if(n>t->maximum-t->result->length)return 0;
    if(n){memcpy(t->result->body+t->result->length,bytes,n);t->result->length+=n;}return n;
}
static size_t receive_header(char *bytes,size_t size,size_t count,void *context){
    (void)bytes;Transfer *t=context;if(size && count>SIZE_MAX/size)return 0;size_t n=size*count;
    if(n>32768-t->header_bytes)return 0;t->header_bytes+=n;return n;
}
const char *msgat_preview_http_location(const MsgatPreviewHTTPResult *out) { return out ? out->location : ""; }
void msgat_preview_http_free(MsgatPreviewHTTPResult *out){if(!out)return;if(out->body){volatile uint8_t *bytes=out->body;for(size_t i=0;i<out->length;i++)bytes[i]=0;free(out->body);}memset(out,0,sizeof(*out));}
int msgat_preview_http_fetch(const char *url,const char *resolution,size_t maximum,long timeout_ms,MsgatPreviewCancel *token,MsgatPreviewHTTPResult *out){
    if(!url || !resolution || !out || !token || maximum==0 || maximum>2*1024*1024 || timeout_ms<1 || timeout_ms>12000)return -1;
    memset(out,0,sizeof(*out));if(atomic_load(&token->cancelled))return -1;pthread_once(&curl_once,initialize_curl);
    CURL *curl=curl_easy_init();if(!curl)return -1;
    struct curl_slist *resolved=NULL;
    if(*resolution){resolved=curl_slist_append(NULL,resolution);if(!resolved){curl_easy_cleanup(curl);return -1;}}
    out->body=malloc(maximum);if(!out->body){curl_slist_free_all(resolved);curl_easy_cleanup(curl);return -1;}
    Transfer transfer={out,maximum,0};
    #define SET(option,value) do{if(curl_easy_setopt(curl,option,value)!=CURLE_OK)goto failure;}while(0)
    SET(CURLOPT_URL,url);SET(CURLOPT_RESOLVE,resolved);
    SET(CURLOPT_PROTOCOLS,(long)(CURLPROTO_HTTP|CURLPROTO_HTTPS));
    SET(CURLOPT_FOLLOWLOCATION,0L);SET(CURLOPT_MAXREDIRS,0L);
    SET(CURLOPT_PROXY,"");SET(CURLOPT_NOPROXY,"*");SET(CURLOPT_NETRC,(long)CURL_NETRC_IGNORED);
    SET(CURLOPT_HTTPAUTH,(long)CURLAUTH_NONE);SET(CURLOPT_UNRESTRICTED_AUTH,0L);
    SET(CURLOPT_SSL_VERIFYPEER,1L);SET(CURLOPT_SSL_VERIFYHOST,2L);
    SET(CURLOPT_HTTP_VERSION,(long)CURL_HTTP_VERSION_1_1);
    SET(CURLOPT_NOSIGNAL,1L);SET(CURLOPT_TIMEOUT_MS,timeout_ms);SET(CURLOPT_CONNECTTIMEOUT_MS,timeout_ms<3000?timeout_ms:3000L);
    SET(CURLOPT_MAXFILESIZE_LARGE,(curl_off_t)maximum);SET(CURLOPT_ACCEPT_ENCODING,"identity");SET(CURLOPT_USERAGENT,"MsgAT-LinkPreview/2.0");
    SET(CURLOPT_WRITEFUNCTION,receive_body);SET(CURLOPT_WRITEDATA,&transfer);SET(CURLOPT_HEADERFUNCTION,receive_header);SET(CURLOPT_HEADERDATA,&transfer);
    SET(CURLOPT_NOPROGRESS,0L);SET(CURLOPT_XFERINFOFUNCTION,progress);SET(CURLOPT_XFERINFODATA,token);
    if(curl_easy_perform(curl)!=CURLE_OK)goto failure;
    char *type=NULL,*location=NULL;curl_easy_getinfo(curl,CURLINFO_RESPONSE_CODE,&out->status);curl_easy_getinfo(curl,CURLINFO_CONTENT_TYPE,&type);curl_easy_getinfo(curl,CURLINFO_REDIRECT_URL,&location);
    if(type){if(strlen(type)>=sizeof(out->content_type))goto failure;strcpy(out->content_type,type);}
    if(location){if(strlen(location)>=sizeof(out->location))goto failure;strcpy(out->location,location);}
    curl_slist_free_all(resolved);curl_easy_cleanup(curl);return 0;
failure:
    curl_slist_free_all(resolved);curl_easy_cleanup(curl);msgat_preview_http_free(out);return -1;
    #undef SET
}
