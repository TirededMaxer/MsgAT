// SPDX-License-Identifier: AGPL-3.0-only
#include "include/StickerInflate.h"
#include <zlib.h>
#include <string.h>
int msgat_sticker_inflate(const uint8_t *input,size_t input_length,uint8_t *output,size_t output_length){
    if(!input || !output || input_length==0 || output_length==0 || input_length>8*1024*1024 || output_length>8*1024*1024)return -1;
    z_stream stream;memset(&stream,0,sizeof(stream));
    stream.next_in=(Bytef*)input;stream.avail_in=(uInt)input_length;stream.next_out=output;stream.avail_out=(uInt)output_length;
    if(inflateInit2(&stream,-MAX_WBITS)!=Z_OK)return -1;
    int result=inflate(&stream,Z_FINISH);
    int valid=result==Z_STREAM_END && stream.total_out==output_length && stream.total_in==input_length;
    inflateEnd(&stream);return valid?0:-1;
}
