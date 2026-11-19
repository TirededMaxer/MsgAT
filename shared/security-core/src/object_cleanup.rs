// SPDX-License-Identifier: AGPL-3.0-only
//! The host checks encrypted database references while holding its transaction.
//! Only the three exact generated paths for one random object ID are removable.
use serde_json::{Value,json};
use std::io;

pub fn remove(directory: &str,id: &str)->Result<Value,String> {
    if id.len()!=64 || !id.bytes().all(|b|b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) {
        return Err("Invalid encrypted object identifier".into());
    }
    remove_owned(directory,id).map_err(|_|"Encrypted object cleanup will retry".to_owned())?;
    Ok(json!({"removed":true}))
}

#[cfg(unix)]
fn remove_owned(directory: &str,id: &str)->io::Result<()> {
    use std::{ffi::CString,os::fd::{AsRawFd,FromRawFd,OwnedFd}};
    let name=CString::new(directory).map_err(|_|io::Error::from(io::ErrorKind::InvalidInput))?;
    // SAFETY: CString is NUL terminated; ownership is transferred exactly once.
    let raw=unsafe {libc::open(name.as_ptr(),libc::O_RDONLY|libc::O_DIRECTORY|libc::O_NOFOLLOW|libc::O_CLOEXEC)};
    if raw<0 {let error=io::Error::last_os_error();return if error.kind()==io::ErrorKind::NotFound {Ok(())}else{Err(error)}}
    let root=unsafe {OwnedFd::from_raw_fd(raw)};
    let prefix=CString::new(&id[..2]).map_err(|_|io::Error::from(io::ErrorKind::InvalidInput))?;
    let raw=unsafe {libc::openat(root.as_raw_fd(),prefix.as_ptr(),libc::O_RDONLY|libc::O_DIRECTORY|libc::O_NOFOLLOW|libc::O_CLOEXEC)};
    if raw<0 {let error=io::Error::last_os_error();return if error.kind()==io::ErrorKind::NotFound {Ok(())}else{Err(error)}}
    let shard=unsafe {OwnedFd::from_raw_fd(raw)};
    for suffix in [".obj",".part",".obj.part"] {
        let leaf=CString::new(format!("{}{suffix}",&id[2..])).map_err(|_|io::Error::from(io::ErrorKind::InvalidInput))?;
        // unlinkat with flags=0 removes the entry itself, never a symlink target
        // or a directory; pinned directory descriptors avoid parent replacement.
        if unsafe {libc::unlinkat(shard.as_raw_fd(),leaf.as_ptr(),0)}!=0 {
            let error=io::Error::last_os_error();if error.kind()!=io::ErrorKind::NotFound {return Err(error)}
        }
    }
    if unsafe {libc::fsync(shard.as_raw_fd())}!=0 {return Err(io::Error::last_os_error())}
    Ok(())
}

#[cfg(windows)]
fn remove_owned(directory: &str,id: &str)->io::Result<()> {
    use std::{os::windows::{ffi::OsStrExt,io::{AsRawHandle,FromRawHandle,OwnedHandle}},path::Path,ptr};
    use windows_sys::Win32::{Foundation::INVALID_HANDLE_VALUE,Storage::FileSystem::*};
    fn open(path: &Path,access: u32,sharing: u32)->io::Result<OwnedHandle> {
        let mut name: Vec<u16>=path.as_os_str().encode_wide().collect();
        if name.contains(&0) {return Err(io::Error::from(io::ErrorKind::InvalidInput))}name.push(0);
        // OPEN_REPARSE_POINT opens the named entry rather than its target.
        let handle=unsafe {CreateFileW(name.as_ptr(),access,sharing,ptr::null(),OPEN_EXISTING,FILE_FLAG_BACKUP_SEMANTICS|FILE_FLAG_OPEN_REPARSE_POINT,ptr::null_mut())};
        if handle==INVALID_HANDLE_VALUE {return Err(io::Error::last_os_error())}
        Ok(unsafe {OwnedHandle::from_raw_handle(handle)})
    }
    fn attributes(handle: &OwnedHandle)->io::Result<u32> {
        let mut info=std::mem::MaybeUninit::<BY_HANDLE_FILE_INFORMATION>::uninit();
        if unsafe {GetFileInformationByHandle(handle.as_raw_handle(),info.as_mut_ptr())}==0 {return Err(io::Error::last_os_error())}
        Ok(unsafe {info.assume_init()}.dwFileAttributes)
    }
    fn pin(path: &Path)->io::Result<OwnedHandle> {
        let handle=open(path,FILE_READ_ATTRIBUTES,FILE_SHARE_READ|FILE_SHARE_WRITE)?;
        let attrs=attributes(&handle)?;
        if attrs&FILE_ATTRIBUTE_DIRECTORY==0 || attrs&FILE_ATTRIBUTE_REPARSE_POINT!=0 {return Err(io::Error::from(io::ErrorKind::PermissionDenied))}
        // Without FILE_SHARE_DELETE these directory entries cannot be renamed
        // while the generated child paths are opened.
        Ok(handle)
    }
    let root_path=Path::new(directory);
    let _root=match pin(root_path){Ok(value)=>value,Err(error) if error.kind()==io::ErrorKind::NotFound=>return Ok(()),Err(error)=>return Err(error)};
    let shard_path=root_path.join(&id[..2]);
    let _shard=match pin(&shard_path){Ok(value)=>value,Err(error) if error.kind()==io::ErrorKind::NotFound=>return Ok(()),Err(error)=>return Err(error)};
    for suffix in [".obj",".part",".obj.part"] {
        let handle=match open(&shard_path.join(format!("{}{suffix}",&id[2..])),DELETE|FILE_READ_ATTRIBUTES,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE) {
            Ok(value)=>value,Err(error) if error.kind()==io::ErrorKind::NotFound=>continue,Err(error)=>return Err(error)
        };
        if attributes(&handle)?&FILE_ATTRIBUTE_DIRECTORY!=0 {return Err(io::Error::from(io::ErrorKind::PermissionDenied))}
        let disposition=FILE_DISPOSITION_INFO {DeleteFile:true};
        if unsafe {SetFileInformationByHandle(handle.as_raw_handle(),FileDispositionInfo,(&disposition as *const FILE_DISPOSITION_INFO).cast(),std::mem::size_of::<FILE_DISPOSITION_INFO>() as u32)}==0 {return Err(io::Error::last_os_error())}
    }
    Ok(())
}

#[cfg(not(any(unix,windows)))]
fn remove_owned(_: &str,_: &str)->io::Result<()> {Err(io::Error::from(io::ErrorKind::Unsupported))}
