// SPDX-License-Identifier: AGPL-3.0-only
use super::{Entry,Span,MAX_ITEM,u16_at,u32_at,entry};

pub(super) fn catalog(bytes:&[u8])->Result<Vec<Entry>,String> {
    if bytes.len()<22 {return Err("Truncated ZIP package".into())}
    let tail=bytes.len().saturating_sub(65557);
    let end=(tail..=bytes.len()-22).rev().find(|p|bytes[*p..].starts_with(b"PK\x05\x06") && u16_at(bytes,*p+20).is_ok_and(|n|*p+22+n as usize==bytes.len())).ok_or("Missing ZIP directory")?;
    if u16_at(bytes,end+4)?!=0 || u16_at(bytes,end+6)?!=0 || u16_at(bytes,end+8)?!=u16_at(bytes,end+10)? {return Err("Split ZIP packages are not supported".into())}
    let count=u16_at(bytes,end+10)? as usize;let directory_size=u32_at(bytes,end+12)? as usize;let mut cursor=u32_at(bytes,end+16)? as usize;
    if count==65535 || directory_size==u32::MAX as usize || cursor==u32::MAX as usize {return Err("ZIP64 packages need a standard ZIP export".into())}
    if count>4096 || cursor.checked_add(directory_size).is_none_or(|n|n>end) {return Err("ZIP directory exceeds its bounds".into())}
    let directory_end=cursor+directory_size;let mut entries=Vec::new();let mut total=0usize;
    for _ in 0..count {
        if bytes.get(cursor..cursor+4)!=Some(b"PK\x01\x02") {return Err("Invalid ZIP directory entry".into())}
        let flags=u16_at(bytes,cursor+8)?;let method=u16_at(bytes,cursor+10)?;let crc=u32_at(bytes,cursor+16)?;let compressed=u32_at(bytes,cursor+20)? as usize;let size=u32_at(bytes,cursor+24)? as usize;
        let n=u16_at(bytes,cursor+28)? as usize;let extra=u16_at(bytes,cursor+30)? as usize;let comment=u16_at(bytes,cursor+32)? as usize;
        let attributes=u32_at(bytes,cursor+38)?;let local=u32_at(bytes,cursor+42)? as usize;
        let next=cursor.checked_add(46+n+extra+comment).filter(|v|*v<=directory_end).ok_or("Truncated ZIP directory")?;
        let raw_name=bytes.get(cursor+46..cursor+46+n).ok_or("Invalid ZIP filename")?;let filename=String::from_utf8_lossy(raw_name).into_owned();cursor=next;
        if filename.ends_with('/') || size==0 {continue}
        if filename.contains('\0') || filename.starts_with(['/', '\\']) || filename.split(['/', '\\']).any(|s|s=="..") || (attributes>>16)&0xf000==0xa000 {return Err("ZIP package contains unsafe paths or links".into())}
        if flags&1!=0 {return Err("Password-protected ZIP packages need an unencrypted export".into())}
        if ![0,8].contains(&method) {return Err("Unsupported ZIP compression; use Store or Deflate".into())}
        if size>MAX_ITEM || compressed>MAX_ITEM || compressed==0 || size>compressed.saturating_mul(200) {return Err("ZIP item exceeds the supported size or expansion limit".into())}
        if bytes.get(local..local+4)!=Some(b"PK\x03\x04") || u16_at(bytes,local+8)?!=method || u16_at(bytes,local+6)?!=flags {return Err("ZIP local header does not match its directory".into())}
        let local_n=u16_at(bytes,local+26)? as usize;let local_extra=u16_at(bytes,local+28)? as usize;
        if bytes.get(local+30..local+30+local_n)!=Some(raw_name) {return Err("ZIP filename does not match its directory".into())}
        let data=local.checked_add(30+local_n+local_extra).ok_or("Invalid ZIP offset")?;
        if data.checked_add(compressed).is_none_or(|n|n>directory_end-directory_size) || (method==0 && size!=compressed) {return Err("ZIP data exceeds its bounds".into())}
        total=total.checked_add(size).filter(|v|*v<=256*1024*1024).ok_or("ZIP package exceeds the total expansion limit")?;
        entries.push(entry(filename,size,method,Some(crc),vec![Span{offset:data,length:compressed}])?);
    }
    if cursor!=directory_end {return Err("ZIP directory length mismatch".into())}Ok(entries)
}
