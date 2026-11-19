// SPDX-License-Identifier: AGPL-3.0-only
//! Local, read-only import catalogues. Never extracts source names to a destination path.
mod compound;
mod image;
mod zip;
use base64::{Engine as _,engine::general_purpose::STANDARD};
use rand::RngCore;
use sha2::{Digest,Sha256};
use serde_json::{Value,json};
use std::{collections::HashMap,fs::{self,File},io::{Read,Seek,SeekFrom},path::{Path,PathBuf},sync::{Mutex,OnceLock},time::{Duration,Instant,SystemTime}};
use zeroize::Zeroizing;

pub const MAX_ITEM:usize=8*1024*1024;
const MAX_PACKAGE:usize=64*1024*1024;
const MAX_ENTRIES:usize=4096;
#[derive(Clone)]
pub(super) struct Span {pub offset:usize,pub length:usize}
#[derive(Clone)]
pub(super) struct Entry {pub name:String,pub size:usize,pub method:u16,pub crc:Option<u32>,pub spans:Vec<Span>,file:Option<SourceFile>}
#[derive(Clone)]
struct SourceFile {path:PathBuf,root:PathBuf,size:u64,modified:Option<SystemTime>}
struct Session {bytes:Zeroizing<Vec<u8>>,entries:Vec<Entry>,used:Instant,inspections:HashMap<usize,Zeroizing<Vec<u8>>>}
static SESSIONS:OnceLock<Mutex<HashMap<String,Session>>>=OnceLock::new();
fn sessions()->&'static Mutex<HashMap<String,Session>> {SESSIONS.get_or_init(||Mutex::new(HashMap::new()))}
fn expire(map:&mut HashMap<String,Session>){map.retain(|_,s|s.used.elapsed()<Duration::from_secs(600));}
pub(super) fn u16_at(bytes:&[u8],offset:usize)->Result<u16,String> {Ok(u16::from_le_bytes(bytes.get(offset..offset+2).ok_or("Truncated sticker package")?.try_into().map_err(|_|"Truncated sticker package")?))}
pub(super) fn u32_at(bytes:&[u8],offset:usize)->Result<u32,String> {Ok(u32::from_le_bytes(bytes.get(offset..offset+4).ok_or("Truncated sticker package")?.try_into().map_err(|_|"Truncated sticker package")?))}
pub(super) fn u64_at(bytes:&[u8],offset:usize)->Result<u64,String> {Ok(u64::from_le_bytes(bytes.get(offset..offset+8).ok_or("Truncated sticker package")?.try_into().map_err(|_|"Truncated sticker package")?))}
pub(super) fn name(value:&str)->String {
    let mut out=String::new();for c in value.chars().filter(|c|!c.is_control() && !matches!(*c,'\u{202a}'..='\u{202e}'|'\u{2066}'..='\u{2069}')) {if out.len()+c.len_utf8()>512 {break}out.push(c)}out
}
pub(super) fn entry(value:String,size:usize,method:u16,crc:Option<u32>,spans:Vec<Span>)->Result<Entry,String> {
    if size==0 || size>MAX_ITEM {return Err("Sticker item exceeds the 8 MiB limit".into())}
    Ok(Entry{name:name(&value),size,method,crc,spans,file:None})
}
fn bounded_file(path:&Path)->Result<Zeroizing<Vec<u8>>,String> {
    let mut options=fs::OpenOptions::new();options.read(true);
    #[cfg(unix)] {use std::os::unix::fs::OpenOptionsExt;options.custom_flags(libc::O_NONBLOCK);}
    let mut input=options.open(path).map_err(|_|"Cannot read the selected sticker source")?;
    let mut bytes=Zeroizing::new(Vec::new());
    #[cfg(unix)] {
        use std::os::{fd::AsRawFd,unix::fs::FileTypeExt};
        if input.metadata().map_err(|_|"Cannot inspect the selected source")?.file_type().is_fifo() {
            let started=Instant::now();let mut block=Zeroizing::new(vec![0u8;65536]);
            loop {
                let remaining=Duration::from_secs(12).saturating_sub(started.elapsed());if remaining.is_zero(){return Err("Sticker provider timed out; save a local copy first".into())}
                let mut descriptor=libc::pollfd{fd:input.as_raw_fd(),events:libc::POLLIN,revents:0};
                let ready=unsafe{libc::poll(&mut descriptor,1,remaining.as_millis().min(1000) as i32)};
                if ready<0 {let error=std::io::Error::last_os_error();if error.kind()==std::io::ErrorKind::Interrupted {continue}return Err("Cannot read the selected sticker provider".into())}
                if ready==0 {continue}
                match input.read(&mut block) {
                    Ok(0)=>break,
                    Ok(n)=>{if bytes.len()+n>MAX_PACKAGE{return Err("Sticker package exceeds 64 MiB; select a smaller export".into())}bytes.extend_from_slice(&block[..n]);},
                    Err(error) if matches!(error.kind(),std::io::ErrorKind::Interrupted|std::io::ErrorKind::WouldBlock)=>continue,
                    Err(_)=>return Err("Cannot read the selected sticker provider".into())
                }
            }
            return Ok(bytes)
        }
    }
    input.by_ref().take((MAX_PACKAGE+1) as u64).read_to_end(&mut bytes).map_err(|_|"Cannot read the selected sticker source")?;
    if bytes.len()>MAX_PACKAGE {return Err("Sticker package exceeds 64 MiB; select a smaller export".into())}Ok(bytes)
}
fn folder(root:&Path)->Result<Vec<Entry>,String> {
    let root=root.canonicalize().map_err(|_|"Cannot read the selected folder")?;
    let mut pending=vec![(root.clone(),0usize)];let mut result=Vec::new();let mut visited=0;
    while let Some((directory,depth))=pending.pop() {
        if depth>12 {continue}
        for item in fs::read_dir(&directory).map_err(|_|"Cannot read the selected folder")? {
            let item=item.map_err(|_|"Cannot read a folder entry")?;visited+=1;if visited>20000 {return Err("Folder contains too many entries; select a subfolder".into())}
            let metadata=fs::symlink_metadata(item.path()).map_err(|_|"Cannot inspect a folder entry")?;
            if metadata.file_type().is_symlink(){continue}
            #[cfg(windows)] {use std::os::windows::fs::MetadataExt;if metadata.file_attributes()&0x400!=0 {continue}}
            if metadata.is_dir(){pending.push((item.path(),depth+1));continue}
            if !metadata.is_file() || metadata.len()==0 || metadata.len()>MAX_ITEM as u64 {continue}
            // Unknown suffixes are retained: old WeChat caches often use .dat or no suffix.
            if result.len()>=MAX_ENTRIES {return Err("Folder contains too many files; select a subfolder".into())}
            let relative=item.path().strip_prefix(&root).map_err(|_|"Invalid folder entry")?.to_string_lossy().into_owned();
            result.push(Entry{name:name(&relative),size:metadata.len() as usize,method:0,crc:None,spans:Vec::new(),file:Some(SourceFile{path:item.path(),root:root.clone(),size:metadata.len(),modified:metadata.modified().ok()})});
        }
    }
    result.sort_by(|a,b|a.name.cmp(&b.name));Ok(result)
}
pub fn open(source:String)->Result<Value,String> {
    {let mut map=sessions().lock().map_err(|_|"Sticker importer is unavailable")?;expire(&mut map);if map.len()>=2 {return Err("Close another sticker import first".into())}}
    let path=Path::new(&source);
    let (bytes,entries,format)=if path.is_dir() {(Zeroizing::new(Vec::new()),folder(path)?,"folder")}
    else {
        let bytes=bounded_file(path)?;
        let (entries,format)=if bytes.starts_with(&[0xd0,0xcf,0x11,0xe0,0xa1,0xb1,0x1a,0xe1]) {(compound::catalog(&bytes)?,"qq_eif")}
        else if bytes.starts_with(b"PK\x03\x04") || bytes.starts_with(b"PK\x05\x06") {(zip::catalog(&bytes)?,"zip")}
        else {if bytes.len()>MAX_ITEM {return Err("Sticker image exceeds 8 MiB".into())}(vec![entry(path.file_name().map(|v|v.to_string_lossy().into_owned()).unwrap_or_else(||"image".into()),bytes.len(),0,None,vec![Span{offset:0,length:bytes.len()}])?],"image")};
        (bytes,entries,format)
    };
    let mut random=[0u8;16];rand::rng().fill_bytes(&mut random);let token:String=random.iter().map(|v|format!("{v:02x}")).collect();
    let listed:Vec<Value>=entries.iter().enumerate().map(|(id,e)|json!({"id":id,"name":e.name,"size":e.size,"compressed_size":if e.file.is_some(){e.size}else{e.spans.iter().map(|s|s.length).sum::<usize>()},"method":e.method,"crc32":e.crc})).collect();
    let mut map=sessions().lock().map_err(|_|"Sticker importer is unavailable")?;expire(&mut map);if map.len()>=2{return Err("Close another sticker import first".into())}
    map.insert(token.clone(),Session{bytes,entries,used:Instant::now(),inspections:HashMap::new()});
    Ok(json!({"session":token,"format":format,"entries":listed}))
}
pub fn close(token:String)->Result<Value,String> {sessions().lock().map_err(|_|"Sticker importer is unavailable")?.remove(&token);Ok(json!({"closed":true}))}
pub fn read(token:String,id:usize,offset:usize,length:usize)->Result<Value,String> {
    if length>1024*1024 {return Err("Sticker read exceeds its limit".into())}
    let mut map=sessions().lock().map_err(|_|"Sticker importer is unavailable")?;expire(&mut map);
    let session=map.get_mut(&token).ok_or("Sticker import expired; select the source again")?;session.used=Instant::now();
    let item=session.entries.get(id).ok_or("Unknown sticker entry")?;
    let total=if item.file.is_some(){item.size}else{item.spans.iter().map(|s|s.length).sum()};
    let end=offset.checked_add(length).filter(|v|*v<=total).ok_or("Invalid sticker byte range")?;
    let mut out=Zeroizing::new(Vec::with_capacity(length));
    if let Some(source)=&item.file {
        let canonical=source.path.canonicalize().map_err(|_|"Sticker source changed")?;
        if !canonical.starts_with(&source.root) {return Err("Sticker source left the selected folder".into())}
        let mut options=fs::OpenOptions::new();options.read(true);
        #[cfg(unix)] {use std::os::unix::fs::OpenOptionsExt;options.custom_flags(libc::O_NOFOLLOW);}
        #[cfg(windows)] {use std::os::windows::fs::OpenOptionsExt;options.custom_flags(0x00200000);}
        let mut file=options.open(&source.path).map_err(|_|"Cannot reopen the selected sticker")?;let metadata=file.metadata().map_err(|_|"Cannot inspect the selected sticker")?;
        #[cfg(windows)] {use std::os::windows::fs::MetadataExt;if metadata.file_attributes()&0x400!=0 {return Err("Sticker source changed into a link".into())}}
        if !metadata.is_file() || metadata.len()!=source.size || metadata.modified().ok()!=source.modified {return Err("Sticker source changed; select the source again".into())}
        file.seek(SeekFrom::Start(offset as u64)).map_err(|_|"Cannot read sticker range")?;out.resize(length,0);file.read_exact(&mut out).map_err(|_|"Sticker source changed while reading")?;
    } else {
        let mut base=0usize;
        for span in &item.spans {
            let next=base.checked_add(span.length).ok_or("Invalid sticker stream")?;
            let start=offset.max(base);let stop=end.min(next);
            if start<stop {let pos=span.offset+start-base;out.extend_from_slice(session.bytes.get(pos..pos+stop-start).ok_or("Truncated sticker stream")?)}
            if next>=end {break}base=next;
        }
        if out.len()!=length {return Err("Truncated sticker stream".into())}
    }
    Ok(json!({"data":STANDARD.encode(&out)}))
}
/// Identification only; hosts still validate dimensions/frame counts with their image codec.
fn identify(prefix:String,size:u64)->Result<Value,String> {
    if size==0 || size>MAX_ITEM as u64 {return Err("Sticker image exceeds 8 MiB".into())}
    let prefix=STANDARD.decode(prefix).map_err(|_|"Invalid image prefix")?;if prefix.len()>65536 {return Err("Image prefix is too large".into())}
    if prefix.starts_with(b"wxgf") || prefix.starts_with(&[0x07,0x08,0x56,0x32]) {return Err("This WeChat cache needs a supported image export; its private encrypted container is not imported".into())}
    let signatures:&[(&[u8],&str,&str)]=&[(b"\x89PNG\r\n\x1a\n","image/png","png"),(b"GIF87a","image/gif","gif"),(b"GIF89a","image/gif","gif"),(b"\xff\xd8\xff","image/jpeg","jpg"),(b"BM","image/bmp","bmp")];
    for xor in 0..=255u8 {
        if let Some((_,mime,extension))=signatures.iter().find(|(magic,_,_)|prefix.len()>=magic.len() && prefix.iter().zip(magic.iter()).all(|(a,b)|a^xor==*b)) {return Ok(json!({"mime":mime,"extension":extension,"xor":xor}))}
        if prefix.len()>=12 && prefix[..4].iter().zip(b"RIFF").all(|(a,b)|a^xor==*b) && prefix[8..12].iter().zip(b"WEBP").all(|(a,b)|a^xor==*b) {return Ok(json!({"mime":"image/webp","extension":"webp","xor":xor}))}
    }
    Err("Unsupported sticker image or private cache container; export PNG, JPEG, GIF or WebP".into())
}

pub fn discard_inspection(token:String,id:usize)->Result<Value,String>{
    let mut map=sessions().lock().map_err(|_|"Sticker importer is unavailable")?;
    if let Some(session)=map.get_mut(&token){session.inspections.remove(&id);}Ok(json!({"discarded":true}))
}
pub fn inspect_chunk(token:String,id:usize,offset:usize,bytes:String,finish:bool)->Result<Value,String>{
    let bytes=Zeroizing::new(STANDARD.decode(bytes).map_err(|_|"Invalid inspection block")?);if bytes.len()>1024*1024{return Err("Inspection block exceeds its limit".into())}
    let mut map=sessions().lock().map_err(|_|"Sticker importer is unavailable")?;expire(&mut map);
    let session=map.get_mut(&token).ok_or("Sticker import expired; select its source again")?;session.used=Instant::now();
    let entry=session.entries.get(id).ok_or("Unknown sticker entry")?;
    if offset==0 {
        if !session.inspections.contains_key(&id) && session.inspections.len()>=2{return Err("Too many concurrent sticker inspections".into())}
        session.inspections.insert(id,Zeroizing::new(Vec::with_capacity(entry.size)));
    }
    let image=session.inspections.get_mut(&id).ok_or("Sticker inspection was not started")?;
    if offset!=image.len() || offset.checked_add(bytes.len()).is_none_or(|v|v>entry.size){session.inspections.remove(&id);return Err("Invalid sticker inspection order".into())}
    image.extend_from_slice(&bytes);if !finish{return Ok(json!({"received":image.len()}))}
    let mut image=session.inspections.remove(&id).ok_or("Missing sticker inspection")?;
    if image.len()!=entry.size{return Err("Sticker image length mismatch".into())}
    if let Some(expected)=entry.crc {if crc_state(u32::MAX,&image)^u32::MAX!=expected{return Err("Sticker ZIP checksum mismatch".into())}}
    let identified=identify(STANDARD.encode(&image[..image.len().min(65536)]),image.len() as u64)?;
    let xor=identified["xor"].as_u64().ok_or("Invalid image identity")? as u8;for byte in image.iter_mut(){*byte^=xor}
    let mime=identified["mime"].as_str().ok_or("Invalid image identity")?;let(width,height,frames)=image::inspect(&image,mime)?;
    let sha256:String=Sha256::digest(&*image).iter().map(|v|format!("{v:02x}")).collect();
    Ok(json!({"mime":mime,"extension":identified["extension"],"xor":xor,"sha256":sha256,"width":width,"height":height,"frames":frames}))
}
fn crc_state(state:u32,bytes:&[u8])->u32{
    static TABLE:OnceLock<[u32;256]>=OnceLock::new();let table=TABLE.get_or_init(||std::array::from_fn(|i|{let mut c=i as u32;for _ in 0..8 {c=if c&1==1 {0xedb88320^(c>>1)}else{c>>1}}c}));
    let mut crc=state;for byte in bytes {crc=table[((crc^*byte as u32)&255) as usize]^(crc>>8)}crc
}

pub(crate) fn inspect_plain(bytes:&[u8],mime:&str)->Result<Value,String>{
    let identified=identify(STANDARD.encode(&bytes[..bytes.len().min(65536)]),bytes.len() as u64)?;
    if identified["xor"].as_u64()!=Some(0) || identified["mime"].as_str()!=Some(mime){return Err("Sticker format does not match its manifest".into())}
    let(width,height,frames)=image::inspect(bytes,mime)?;
    Ok(json!({"width":width,"height":height,"frames":frames}))
}
