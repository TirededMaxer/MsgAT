// SPDX-License-Identifier: AGPL-3.0-only
//! Seekable encrypted recording staging. No plaintext file, path-supplied key,
//! or recoverable process-global recording state is exposed to another process.
use aes_gcm_siv::{Aes256GcmSiv,KeyInit,Nonce,aead::AeadInOut};
use base64::{Engine as _,engine::general_purpose::STANDARD};
use hkdf::Hkdf;
use rand::Rng;
use serde_json::{Value,json};
use sha2::Sha256;
use std::{collections::HashMap,fs::{self,File,OpenOptions},io::{Read,Seek,SeekFrom,Write},path::Path,sync::{Arc,Mutex,OnceLock}};
use zeroize::{Zeroize,Zeroizing};

const BLOCK:usize=65_536;
const RECORD:u64=BLOCK as u64+12+16;
const MAXIMUM:u64=100*1024*1024;
const IO_LIMIT:usize=256*1024;
const DOMAIN:&[u8]=b"MsgAT/v2/capture-block\0";

struct Page{index:usize,bytes:Zeroizing<Vec<u8>>,dirty:bool}
struct Capture {
    file:Option<File>,id:String,key:Zeroizing<[u8;32]>,capacity:u64,size:u64,
    versions:Vec<u64>,page:Option<Page>,frozen:bool,failed:bool,
}
static CAPTURES:OnceLock<Mutex<HashMap<String,Arc<Mutex<Capture>>>>>=OnceLock::new();
fn registry()->&'static Mutex<HashMap<String,Arc<Mutex<Capture>>>>{CAPTURES.get_or_init(||Mutex::new(HashMap::new()))}
fn valid_handle(id:&str)->bool{id.len()==64 && id.bytes().all(|b|b.is_ascii_digit() || (b'a'..=b'f').contains(&b))}
fn lookup(id:&str)->Result<Arc<Mutex<Capture>>,String>{
    if !valid_handle(id){return Err("Invalid recording handle".into())}
    registry().lock().map_err(|_|"Recording state is unavailable")?.get(id).cloned().ok_or_else(||"The recording has ended".into())
}
fn checked_directory(path:&Path)->Result<(),String>{
    let metadata=fs::symlink_metadata(path).map_err(|_|"Cannot inspect the encrypted recording directory")?;
    if !metadata.is_dir() || metadata.file_type().is_symlink(){return Err("Invalid encrypted recording directory".into())}
    #[cfg(windows)]{use std::os::windows::fs::MetadataExt;if metadata.file_attributes()&0x400!=0{return Err("Invalid encrypted recording directory".into())}}
    Ok(())
}
pub fn open(manifest:super::objects::Manifest,directory:String)->Result<Value,String>{
    let (id,material,capacity)=super::objects::capture_material(manifest)?;
    if capacity==0 || capacity>MAXIMUM{return Err("Invalid recording capacity".into())}
    let mut map=registry().lock().map_err(|_|"Recording state is unavailable")?;
    if map.len()>=4{return Err("Finish or discard an existing recording first".into())}
    let target=super::objects::object_path(&directory,&id)?.with_extension("part");
    let parent=target.parent().ok_or("Invalid recording directory")?;
    let mut directories=fs::DirBuilder::new();directories.recursive(true);
    #[cfg(unix)]{use std::os::unix::fs::DirBuilderExt;directories.mode(0o700);}
    directories.create(&directory).map_err(|_|"Cannot create the encrypted recording directory")?;checked_directory(Path::new(&directory))?;
    directories.create(parent).map_err(|_|"Cannot create the encrypted recording shard")?;checked_directory(parent)?;
    let mut options=OpenOptions::new();options.create_new(true).read(true).write(true);
    #[cfg(unix)]{use std::os::unix::fs::OpenOptionsExt;options.mode(0o600).custom_flags(libc::O_NOFOLLOW|libc::O_CLOEXEC);}
    #[cfg(windows)]{use std::os::windows::fs::OpenOptionsExt;options.share_mode(0).custom_flags(0x00200000);}
    let mut key=Zeroizing::new([0u8;32]);
    Hkdf::<Sha256>::new(Some(b"MsgAT/v2/capture-key"),&material).expand(id.as_bytes(),&mut *key).map_err(|_|"Recording key derivation failed")?;
    let file=options.open(target).map_err(|_|"Cannot create encrypted recording staging")?;
    let handle=loop{let mut random=[0u8;32];rand::rng().fill(&mut random);let value=random.iter().map(|b|format!("{b:02x}")).collect::<String>();if !map.contains_key(&value){break value}};
    map.insert(handle.clone(),Arc::new(Mutex::new(Capture{file:Some(file),id,key,capacity,size:0,versions:vec![0;capacity.div_ceil(BLOCK as u64) as usize],page:None,frozen:false,failed:false})));
    Ok(json!({"capture_id":handle,"capacity":capacity,"size":0}))
}
impl Capture {
    fn check(&self)->Result<(),String>{if self.file.is_none(){Err("The recording has ended".into())}else if self.failed{Err("The recording staging is unavailable".into())}else{Ok(())}}
    fn aad(&self,index:usize,version:u64)->Vec<u8>{
        let mut data=DOMAIN.to_vec();data.extend_from_slice(self.id.as_bytes());data.extend_from_slice(&self.capacity.to_be_bytes());data.extend_from_slice(&(index as u64).to_be_bytes());data.extend_from_slice(&version.to_be_bytes());data
    }
    fn flush_page(&mut self)->Result<(),String>{
        self.check()?;
        let Some(mut page)=self.page.take() else{return Ok(())};
        if !page.dirty{self.page=Some(page);return Ok(())}
        let result=(||->Result<(),String>{
            let version=self.versions[page.index].checked_add(1).ok_or("Recording page version exhausted")?;
            let cipher=Aes256GcmSiv::new_from_slice(&*self.key).map_err(|_|"Invalid recording key")?;
            let mut nonce=[0u8;12];rand::rng().fill(&mut nonce);
            let aad=self.aad(page.index,version);let mut encrypted=Zeroizing::new(page.bytes.to_vec());
            cipher.encrypt_in_place(&Nonce::from(nonce),&aad,&mut *encrypted).map_err(|_|"Recording encryption failed")?;
            let file=self.file.as_mut().ok_or("The recording has ended")?;
            file.seek(SeekFrom::Start(page.index as u64*RECORD)).map_err(|_|"Cannot seek recording staging")?;
            file.write_all(&nonce).and_then(|_|file.write_all(&encrypted)).map_err(|_|"Cannot write recording staging")?;
            self.versions[page.index]=version;page.dirty=false;Ok(())
        })();
        self.page=Some(page);
        if result.is_err(){self.failed=true;}
        result
    }
    fn ensure_page(&mut self,index:usize)->Result<(),String>{
        self.check()?;
        if self.page.as_ref().is_some_and(|page|page.index==index){return Ok(())}
        self.flush_page()?;self.page=None;
        let version=*self.versions.get(index).ok_or("Recording offset exceeds its limit")?;
        let bytes=if version==0{Zeroizing::new(vec![0;BLOCK])}else{
            let result=(||->Result<Zeroizing<Vec<u8>>,String>{
                let mut nonce=[0u8;12];let mut encrypted=Zeroizing::new(vec![0u8;BLOCK+16]);
                let file=self.file.as_mut().ok_or("The recording has ended")?;
                file.seek(SeekFrom::Start(index as u64*RECORD)).map_err(|_|"Cannot seek recording staging")?;
                file.read_exact(&mut nonce).and_then(|_|file.read_exact(&mut encrypted)).map_err(|_|"Cannot read recording staging")?;
                let cipher=Aes256GcmSiv::new_from_slice(&*self.key).map_err(|_|"Invalid recording key")?;
                cipher.decrypt_in_place(&Nonce::from(nonce),&self.aad(index,version),&mut *encrypted).map_err(|_|"Recording authentication failed")?;
                if encrypted.len()!=BLOCK{return Err("Invalid recording block".into())}
                Ok(encrypted)
            })();
            if result.is_err(){self.failed=true;}
            result?
        };
        self.page=Some(Page{index,bytes,dirty:false});Ok(())
    }
    fn write(&mut self,offset:u64,bytes:&[u8])->Result<(),String>{
        self.check()?;if self.frozen{return Err("The recording is read-only".into())}
        let end=offset.checked_add(bytes.len() as u64).filter(|end|*end<=self.capacity).ok_or("The recording size limit was reached")?;
        if bytes.is_empty(){return Ok(())}
        let mut cursor=0;
        while cursor<bytes.len(){
            let position=offset+cursor as u64;let index=(position/BLOCK as u64) as usize;let within=(position%BLOCK as u64) as usize;let length=(BLOCK-within).min(bytes.len()-cursor);
            self.ensure_page(index)?;let page=self.page.as_mut().unwrap();page.bytes[within..within+length].copy_from_slice(&bytes[cursor..cursor+length]);page.dirty=true;cursor+=length;
        }
        self.size=self.size.max(end);Ok(())
    }
    fn read(&mut self,offset:u64,length:usize)->Result<Zeroizing<Vec<u8>>,String>{
        self.check()?;let length=length.min(self.size.saturating_sub(offset) as usize);let mut output=Zeroizing::new(vec![0u8;length]);let mut cursor=0;
        while cursor<length{
            let position=offset+cursor as u64;let index=(position/BLOCK as u64) as usize;let within=(position%BLOCK as u64) as usize;let count=(BLOCK-within).min(length-cursor);
            self.ensure_page(index)?;output[cursor..cursor+count].copy_from_slice(&self.page.as_ref().unwrap().bytes[within..within+count]);cursor+=count;
        }
        Ok(output)
    }
    fn resize(&mut self,size:u64)->Result<(),String>{
        self.check()?;if self.frozen{return Err("The recording is read-only".into())}
        if size>self.capacity{return Err("The recording size limit was reached".into())}
        if size<self.size {
            // Keep monotonically increasing page versions. A truncation must not
            // make a previously authenticated page/version acceptable again.
            for index in (size/BLOCK as u64) as usize..self.size.div_ceil(BLOCK as u64) as usize {
                let cached=self.page.as_ref().is_some_and(|page|page.index==index);
                if self.versions[index]==0 && !cached{continue}
                self.ensure_page(index)?;
                let start=if index==(size/BLOCK as u64) as usize{(size%BLOCK as u64) as usize}else{0};
                let page=self.page.as_mut().unwrap();page.bytes[start..].zeroize();page.dirty=true;
            }
        }
        self.size=size;Ok(())
    }
    fn sync(&mut self)->Result<(),String>{
        self.flush_page()?;
        if self.file.as_mut().ok_or("The recording has ended")?.sync_data().is_err(){self.failed=true;return Err("Cannot flush recording staging".into())}
        Ok(())
    }
}
pub fn write(id:String,offset:u64,bytes:String)->Result<Value,String>{
    let bytes=Zeroizing::new(bytes);if bytes.len()>IO_LIMIT.div_ceil(3)*4{return Err("Recording write exceeds its request limit".into())}
    let data=Zeroizing::new(STANDARD.decode(bytes.as_bytes()).map_err(|_|"Invalid recording bytes")?);
    if data.len()>IO_LIMIT{return Err("Recording write exceeds its request limit".into())}
    let source=lookup(&id)?;let mut state=source.lock().map_err(|_|"Recording state is unavailable")?;state.write(offset,&data)?;Ok(json!({"written":data.len(),"size":state.size}))
}
pub fn read(id:String,offset:u64,length:usize)->Result<Value,String>{
    if length>IO_LIMIT{return Err("Recording read exceeds its request limit".into())}
    let source=lookup(&id)?;let mut state=source.lock().map_err(|_|"Recording state is unavailable")?;let bytes=state.read(offset,length)?;Ok(json!({"bytes":STANDARD.encode(&*bytes),"length":bytes.len()}))
}
pub fn sync(id:String)->Result<Value,String>{let source=lookup(&id)?;source.lock().map_err(|_|"Recording state is unavailable")?.sync()?;Ok(json!({"synced":true}))}
pub fn resize(id:String,size:u64)->Result<Value,String>{
    let source=lookup(&id)?;let mut state=source.lock().map_err(|_|"Recording state is unavailable")?;state.resize(size)?;Ok(json!({"size":state.size}))
}
pub fn freeze(id:String)->Result<Value,String>{
    let source=lookup(&id)?;let mut state=source.lock().map_err(|_|"Recording state is unavailable")?;
    if state.size==0{return Err("The recording is empty".into())}state.sync()?;state.frozen=true;Ok(json!({"size":state.size,"frozen":true}))
}
pub fn status(id:String)->Result<Value,String>{
    let source=lookup(&id)?;let state=source.lock().map_err(|_|"Recording state is unavailable")?;state.check()?;
    Ok(json!({"size":state.size,"capacity":state.capacity,"frozen":state.frozen,"failed":state.failed}))
}
pub fn close(id:String)->Result<Value,String>{
    if !valid_handle(&id){return Err("Invalid recording handle".into())}
    let source=registry().lock().map_err(|_|"Recording state is unavailable")?.remove(&id);
    if let Some(source)=source{let mut state=source.lock().map_err(|_|"Recording state is unavailable")?;state.file=None;state.page=None;state.key.zeroize();state.failed=true;}
    Ok(json!({"closed":true}))
}
pub fn import(id:String,manifest:super::objects::Manifest,directory:String,maximum_size:u64,work_id:Option<String>)->Result<Value,String>{
    let work=super::object_work::resolve(work_id.as_deref())?;
    let source=lookup(&id)?;let mut state=source.lock().map_err(|_|"Recording state is unavailable")?;
    state.check()?;if !state.frozen{return Err("Finish the recording before importing it".into())}
    let size=state.size;let source_id=state.id.clone();
    super::objects::import_capture(manifest,&directory,&source_id,size,maximum_size,work.as_deref(),|index,output|{
        let offset=index*BLOCK as u64;
        let bytes=state.read(offset,(size-offset).min(BLOCK as u64) as usize)?;
        output.clear();output.extend_from_slice(&bytes);Ok(())
    })
}
