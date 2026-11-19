// SPDX-License-Identifier: AGPL-3.0-only
//! One padded, authenticated chunk format for all native clients. File names and
//! exact lengths belong to the encrypted manifest, never an object path/header.
use aes_gcm_siv::{Aes256GcmSiv, KeyInit, Nonce, aead::AeadInOut};
use base64::{Engine as _, engine::general_purpose::STANDARD};
use hkdf::Hkdf;
use rand::Rng;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use zeroize::{Zeroize, Zeroizing};
use std::{fs::{self, File, OpenOptions}, io::{Read, Seek, SeekFrom, Write}, path::{Path, PathBuf}};

pub const CHUNK_SIZE: u64 = 65_536;
pub const MAX_SIZE: u64 = 2 * 1024 * 1024 * 1024;
const DOMAIN: &[u8] = b"MsgAT/v2/object-chunk\0";

#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Manifest {
    version: u32,
    object_id: String,
    key: String,
    read_token: String,
    read_token_hash: String,
    size: u64,
    chunk_size: u64,
    chunk_count: u64,
    cipher_size: u64,
    cipher_sha256: String,
    sha256: String,
    filename: String,
    mime: String,
}

impl Drop for Manifest {
    fn drop(&mut self) { self.key.zeroize(); self.read_token.zeroize(); }
}

fn hex(bytes: &[u8]) -> String { bytes.iter().map(|byte|format!("{byte:02x}")).collect() }
fn valid_hex(value: &str) -> bool { value.len()==64 && value.bytes().all(|b|b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) }

pub fn validate(manifest: Manifest) -> Result<Value, String> {
    check_manifest(&manifest)?;
    Ok(json!({"manifest":manifest}))
}

fn check_manifest(manifest: &Manifest) -> Result<(), String> {
    let count=chunks(manifest.size)?;
    let key=Zeroizing::new(STANDARD.decode(&manifest.key).map_err(|_|"Invalid object key")?);
    let token=Zeroizing::new(STANDARD.decode(&manifest.read_token).map_err(|_|"Invalid object capability")?);
    if manifest.version!=2 || manifest.chunk_size!=CHUNK_SIZE || manifest.chunk_count!=count
        || manifest.cipher_size!=count*(CHUNK_SIZE+16) || key.len()!=32 || token.len()!=32
        || !valid_hex(&manifest.object_id) || !valid_hex(&manifest.cipher_sha256) || !valid_hex(&manifest.sha256)
        || manifest.read_token_hash!=hex(&Sha256::digest(&*token)) {
        return Err("Invalid encrypted object manifest".into());
    }
    if manifest.filename.is_empty() || manifest.filename.len()>1024 || manifest.filename=="." || manifest.filename==".."
        || manifest.filename.chars().any(|c|c.is_control() || c=='/' || c=='\\') {
        return Err("Invalid attachment filename".into());
    }
    let parts: Vec<_>=manifest.mime.split('/').collect();
    if manifest.mime.len()>127 || parts.len()!=2 || parts.iter().any(|part|part.is_empty()
        || !part.bytes().all(|b|b.is_ascii_alphanumeric() || b"!#$&^_+-.".contains(&b))) {
        return Err("Invalid attachment content type".into());
    }
    Ok(())
}

/// Recording staging derives a distinct key from a root-owned candidate that
/// its host has already registered in media_staging. It is never a wire manifest.
pub(crate) fn capture_material(manifest:Manifest)->Result<(String,Zeroizing<Vec<u8>>,u64),String>{
    check_manifest(&manifest)?;
    let key=Zeroizing::new(STANDARD.decode(&manifest.key).map_err(|_|"Invalid capture material")?);
    Ok((manifest.object_id.clone(),key,manifest.size))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Chunk {
    key: String,
    object_id: String,
    size: u64,
    index: u32,
    data: String,
}

impl Drop for Chunk {
    fn drop(&mut self) { self.key.zeroize(); self.data.zeroize(); }
}

fn chunks(size: u64) -> Result<u64, String> {
    if size > MAX_SIZE { return Err("Object exceeds the supported size limit".into()); }
    Ok(size.div_ceil(CHUNK_SIZE).max(1))
}

pub fn create(seed: &[u8], size: u64) -> Result<Value, String> {
    if seed.len() != 32 { return Err("Invalid root seed".into()); }
    let count = chunks(size)?;
    let mut object_id = [0u8; 32];
    let mut token = Zeroizing::new([0u8; 32]);
    let mut key = Zeroizing::new([0u8; 32]);
    let mut secret_salt = Zeroizing::new([0u8; 32]);
    let mut rng = rand::rng();
    rng.fill(&mut object_id);
    rng.fill(&mut *token);
    rng.fill(&mut *secret_salt);
    let hierarchy = Hkdf::<Sha256>::new(Some(b"MsgAT/v2/root-key-hierarchy"), seed);
    let mut info = b"objects/content/".to_vec();
    info.extend_from_slice(&object_id);
    // The public object ID alone must not let a later root leak reconstruct
    // historical network attachment keys. This independent secret is discarded;
    // the resulting content key survives only inside SQLCipher and Signal events.
    info.extend_from_slice(&*secret_salt);
    hierarchy.expand(&info, &mut *key).map_err(|_| "Object key derivation failed")?;
    let id = hex(&object_id);
    Ok(json!({"version":2,"object_id":id,"key":STANDARD.encode(*key),
        "read_token":STANDARD.encode(*token),"read_token_hash":hex(&Sha256::digest(*token)),"size":size,"chunk_size":CHUNK_SIZE,
        "chunk_count":count,"cipher_size":count*(CHUNK_SIZE+16)}))
}

pub fn crypt(mut request: Chunk, encrypt: bool, expose_data: bool) -> Result<Value, String> {
    let decoded = STANDARD.decode(&request.key);
    request.key.zeroize();
    let key = Zeroizing::new(decoded.map_err(|_| "Invalid object key")?);
    if key.len() != 32 { return Err("Invalid object key".into()); }
    if !valid_hex(&request.object_id) {
        return Err("Invalid object identifier".into());
    }
    let id: Vec<u8> = request.object_id.as_bytes().chunks_exact(2).map(|pair| {
        let nibble = |b: u8| if b <= b'9' { b-b'0' } else { b-b'a'+10 };
        (nibble(pair[0]) << 4) | nibble(pair[1])
    }).collect();
    // Reject oversized Base64 before allocating decoded input.
    if request.data.len() > ((CHUNK_SIZE as usize+16).div_ceil(3))*4 {
        request.data.zeroize();
        return Err("Object chunk exceeds its size limit".into());
    }
    let decoded = STANDARD.decode(&request.data);
    request.data.zeroize();
    let mut data = Zeroizing::new(decoded.map_err(|_| "Invalid object chunk encoding")?);
    transform(&key,&id,request.size,request.index,&mut data,encrypt)?;
    if expose_data { Ok(json!({"data":STANDARD.encode(&*data)})) } else { Ok(json!({"valid":true})) }
}

fn transform(key: &[u8], id: &[u8], size: u64, index: u32, data: &mut Vec<u8>, encrypt: bool) -> Result<(),String> {
    let count=chunks(size)?;
    if u64::from(index)>=count { return Err("Invalid object chunk index".into()); }
    let expected=if u64::from(index)+1<count { CHUNK_SIZE } else { size-u64::from(index)*CHUNK_SIZE } as usize;
    let mut nonce = [0u8; 12];
    nonce[8..].copy_from_slice(&index.to_be_bytes());
    let mut aad = DOMAIN.to_vec();
    aad.push(2);
    aad.extend_from_slice(&id);
    aad.extend_from_slice(&size.to_be_bytes());
    aad.extend_from_slice(&CHUNK_SIZE.to_be_bytes());
    aad.extend_from_slice(&index.to_be_bytes());
    let cipher = Aes256GcmSiv::new_from_slice(key).map_err(|_| "Invalid object key")?;
    if encrypt {
        if data.len() != expected { return Err("Incorrect object chunk length".into()); }
        data.resize(CHUNK_SIZE as usize, 0);
        cipher.encrypt_in_place(&Nonce::from(nonce), &aad, &mut *data).map_err(|_| "Object encryption failed")?;
    } else {
        if data.len() != CHUNK_SIZE as usize+16 { return Err("Incorrect encrypted object chunk length".into()); }
        cipher.decrypt_in_place(&Nonce::from(nonce), &aad, &mut *data).map_err(|_| "Object authentication failed")?;
        if data.len() != CHUNK_SIZE as usize || data[expected..].iter().any(|byte| *byte != 0) {
            return Err("Invalid object padding".into());
        }
        data[expected..].zeroize();
        data.truncate(expected);
    }
    Ok(())
}

pub(crate) fn object_path(directory: &str, id: &str) -> Result<PathBuf,String> {
    if !valid_hex(id) { return Err("Invalid object identifier".into()); }
    Ok(Path::new(directory).join(&id[..2]).join(format!("{}.obj",&id[2..])))
}

pub(crate) fn sync_publication(directory: &str, id: &str) -> Result<Value, String> {
    let target = object_path(directory, id)?;
    if !fs::symlink_metadata(&target).map_err(|_| "Cannot inspect the published object")?.is_file() {
        return Err("The published object is not a regular file".into());
    }
    #[cfg(unix)]
    {
        // Shards and object-root directories are created lazily. Persist their
        // directory entries as well as the final filename before vault commit.
        for parent in target.ancestors().skip(1).take(4) {
            File::open(parent).and_then(|entry| entry.sync_all()).map_err(|_| "Cannot persist the object directory")?;
        }
    }
    Ok(json!({"ok":true}))
}

fn id_bytes(id: &str) -> Vec<u8> {
    id.as_bytes().chunks_exact(2).map(|p| {
        let n=|b:u8| if b<=b'9' { b-b'0' } else { b-b'a'+10 };
        (n(p[0])<<4)|n(p[1])
    }).collect()
}

/// Imports a user-selected regular file without creating an application-owned
/// plaintext copy. The returned manifest must be committed to SQLCipher by its host.
pub fn import_file(manifest: Manifest, source: String, directory: String, maximum_size: u64, work_id:Option<String>) -> Result<Value,String> {
    let work=super::object_work::resolve(work_id.as_deref())?;
    if let Some(work)=&work {work.report(manifest.size,0)?;}
    check_manifest(&manifest)?;
    if maximum_size==0 || maximum_size>MAX_SIZE { return Err("Invalid attachment size policy".into()); }
    let mut input=File::open(source).map_err(|_|"Cannot open the selected attachment")?;
    let metadata=input.metadata().map_err(|_|"Cannot inspect the selected attachment")?;
    if !metadata.is_file() || metadata.len()>maximum_size || metadata.len()!=manifest.size { return Err("Unsupported, changed or oversized attachment source".into()); }
    let size=manifest.size;
    let count=manifest.chunk_count;
    persist_chunks(manifest,&directory,None,work.as_deref(),|index,buffer| {
        let length=if index+1<count {CHUNK_SIZE} else {size-index*CHUNK_SIZE} as usize;
        buffer.resize(length,0);
        input.read_exact(buffer).map_err(|_|"The selected attachment changed while being read")?;
        if index+1==count { require_eof(&mut input)?; }
        Ok(())
    })
}

/// Makes an independently encrypted object for forwarding or a local saved copy.
/// The plaintext exists only in a zeroizing chunk buffer, never in a staging file.
pub fn rekey_file(source: Manifest, manifest: Manifest, directory: String, maximum_size: u64, work_id:Option<String>) -> Result<Value,String> {
    let work=super::object_work::resolve(work_id.as_deref())?;
    if let Some(work)=&work {work.report(manifest.size,0)?;}
    check_manifest(&source)?; check_manifest(&manifest)?;
    if maximum_size==0 || maximum_size>MAX_SIZE || source.size>maximum_size || manifest.size!=source.size
        || manifest.object_id==source.object_id || manifest.key==source.key || manifest.read_token==source.read_token {
        return Err("Invalid independent object copy".into());
    }
    let mut input=File::open(object_path(&directory,&source.object_id)?).map_err(|_|"Download the attachment before copying it")?;
    let metadata=input.metadata().map_err(|_|"Cannot inspect the encrypted attachment")?;
    if !metadata.is_file() || metadata.len()!=source.cipher_size {return Err("Truncated or oversized encrypted object".into());}
    let old_key=Zeroizing::new(STANDARD.decode(&source.key).map_err(|_|"Invalid object key")?);
    let old_id=id_bytes(&source.object_id);
    let target=object_path(&directory,&manifest.object_id)?;
    let mut old_cipher_hash=Sha256::new();
    let result=persist_chunks(manifest,&directory,Some(&source.sha256),work.as_deref(),|index,buffer| {
        buffer.resize(CHUNK_SIZE as usize+16,0);
        input.read_exact(buffer).map_err(|_|"Cannot read the encrypted attachment")?;
        old_cipher_hash.update(&*buffer);
        transform(&old_key,&old_id,source.size,index as u32,buffer,false)?;
        if index+1==source.chunk_count {require_eof(&mut input)?;}
        Ok(())
    })?;
    if hex(&old_cipher_hash.finalize())!=source.cipher_sha256 {
        let _=fs::remove_file(target);
        return Err("Encrypted attachment digest does not match".into());
    }
    Ok(result)
}

fn require_eof(input: &mut File) -> Result<(),String> {
    let mut extra=Zeroizing::new([0u8;1]);
    if input.read(&mut *extra).map_err(|_|"Cannot finish reading the attachment")?!=0 {
        return Err("The attachment changed while being read".into());
    }
    Ok(())
}

pub(crate) fn import_capture<F>(manifest:Manifest,directory:&str,source_id:&str,size:u64,maximum_size:u64,work:Option<&super::object_work::Work>,read:F)->Result<Value,String>
where F:FnMut(u64,&mut Vec<u8>)->Result<(),String>{
    check_manifest(&manifest)?;
    if maximum_size==0 || maximum_size>MAX_SIZE || size>maximum_size || manifest.size!=size || manifest.object_id==source_id{return Err("Invalid recording import target".into())}
    if let Some(work)=work{work.report(size,0)?;}
    persist_chunks(manifest,directory,None,work,read)
}

fn persist_chunks<F>(mut manifest: Manifest,directory: &str,expected_plain_hash: Option<&str>,work:Option<&super::object_work::Work>,mut read: F) -> Result<Value,String>
where F: FnMut(u64,&mut Vec<u8>) -> Result<(),String> {
    let key=Zeroizing::new(STANDARD.decode(&manifest.key).map_err(|_|"Invalid object key")?);
    let id=id_bytes(&manifest.object_id);
    let target=object_path(directory,&manifest.object_id)?;
    let parent=target.parent().ok_or("Invalid object directory")?;
    let mut builder=fs::DirBuilder::new();
    builder.recursive(true);
    #[cfg(unix)] { use std::os::unix::fs::DirBuilderExt; builder.mode(0o700); }
    builder.create(parent).map_err(|_|"Cannot create the encrypted object directory")?;
    if fs::symlink_metadata(parent).map_err(|_|"Cannot inspect the object directory")?.file_type().is_symlink() {
        return Err("Encrypted object directory cannot be a symbolic link".into());
    }
    let temporary=target.with_extension("part");
    let mut options=OpenOptions::new(); options.create_new(true).write(true);
    #[cfg(unix)] { use std::os::unix::fs::OpenOptionsExt; options.mode(0o600); }
    let mut output=options.open(&temporary).map_err(|_|"Cannot create the encrypted object")?;
    let mut finalized=false;
    let result=(|| -> Result<Value,String> {
        let mut plain_hash=Sha256::new(); let mut cipher_hash=Sha256::new();
        let mut buffer=Zeroizing::new(Vec::with_capacity(CHUNK_SIZE as usize+16));
        for index in 0..manifest.chunk_count {
            if let Some(work)=work {work.check()?;}
            read(index,&mut buffer)?;
            plain_hash.update(&*buffer);
            transform(&key,&id,manifest.size,index as u32,&mut buffer,true)?;
            cipher_hash.update(&*buffer);
            output.write_all(&buffer).map_err(|_|"Cannot write the encrypted object")?;
            buffer.zeroize(); buffer.clear();
            if let Some(work)=work {work.report(manifest.size,((index+1)*CHUNK_SIZE).min(manifest.size))?;}
        }
        manifest.sha256=hex(&plain_hash.finalize()); manifest.cipher_sha256=hex(&cipher_hash.finalize());
        if expected_plain_hash.is_some_and(|expected|expected!=manifest.sha256) {return Err("Attachment plaintext digest does not match".into());}
        if let Some(work)=work {work.check()?;}
        output.sync_all().map_err(|_|"Cannot persist the encrypted object")?;
        if let Some(work)=work {work.check()?;}
        drop(output);
        if target.exists() { return Err("Encrypted object identifier collision".into()); }
        fs::hard_link(&temporary,&target).map_err(|_|"Cannot finalize the encrypted object")?;
        finalized=true;
        fs::remove_file(&temporary).map_err(|_|"Cannot remove encrypted staging data")?;
        sync_publication(directory,&manifest.object_id)?;
        Ok(json!({"manifest":manifest}))
    })();
    if result.is_err() {let _=fs::remove_file(&temporary);if finalized {let _=fs::remove_file(&target);}}
    result
}

/// Returns at most 1 MiB of authenticated plaintext for native image decoders or
/// range-capable media players. No decrypted file or disk cache is produced.
fn read_plaintext(manifest: &Manifest, directory: &str, offset: u64, length: usize) -> Result<Zeroizing<Vec<u8>>,String> {
    check_manifest(&manifest)?;
    if length>8*1024*1024 || offset>manifest.size || length as u64>manifest.size-offset { return Err("Invalid object read range".into()); }
    let mut output=Zeroizing::new(Vec::with_capacity(length));
    if length==0 { return Ok(output); }
    let mut input=File::open(object_path(&directory,&manifest.object_id)?).map_err(|_|"Encrypted object is unavailable")?;
    if input.metadata().map_err(|_|"Cannot inspect the encrypted object")?.len()!=manifest.cipher_size {
        return Err("Truncated or oversized encrypted object".into());
    }
    let key=Zeroizing::new(STANDARD.decode(&manifest.key).map_err(|_|"Invalid object key")?);
    let id=id_bytes(&manifest.object_id);
    let end=offset+length as u64;
    let mut buffer=Zeroizing::new(vec![0u8;CHUNK_SIZE as usize+16]);
    for index in offset/CHUNK_SIZE..=(end-1)/CHUNK_SIZE {
        buffer.resize(CHUNK_SIZE as usize+16,0);
        input.seek(SeekFrom::Start(index*(CHUNK_SIZE+16))).map_err(|_|"Cannot seek the encrypted object")?;
        input.read_exact(&mut buffer).map_err(|_|"Cannot read the encrypted object")?;
        transform(&key,&id,manifest.size,index as u32,&mut buffer,false)?;
        let start=offset.saturating_sub(index*CHUNK_SIZE) as usize;
        let stop=(end-index*CHUNK_SIZE).min(buffer.len() as u64) as usize;
        output.extend_from_slice(&buffer[start..stop]);
        buffer.zeroize();
    }
    Ok(output)
}
pub fn read_range(manifest: Manifest,directory: String,offset: u64,length: usize)->Result<Value,String>{
    if length>1024*1024{return Err("Object read exceeds its limit".into())}
    let data=read_plaintext(&manifest,&directory,offset,length)?;Ok(json!({"data":STANDARD.encode(&*data)}))
}
pub fn inspect_sticker_object(manifest: Manifest,directory: String)->Result<Value,String>{
    if manifest.size==0 || manifest.size>8*1024*1024{return Err("Sticker size exceeds its limit".into())}
    let bytes=read_plaintext(&manifest,&directory,0,manifest.size as usize)?;
    if hex(&Sha256::digest(&*bytes))!=manifest.sha256{return Err("Sticker content checksum mismatch".into())}
    crate::stickers::inspect_plain(&bytes,&manifest.mime)
}
