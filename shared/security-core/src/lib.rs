// SPDX-License-Identifier: AGPL-3.0-only
mod identity;
mod session;

use base64::{Engine as _, engine::general_purpose::STANDARD};
use serde::Deserialize;
use serde_json::{Value, json};
use std::ptr;
use zeroize::Zeroize;

const MAX_REQUEST: usize = 4 * 1024 * 1024;

#[repr(C)]
pub struct MsgatBuffer {
    pub data: *mut u8,
    pub length: usize,
}

#[derive(Deserialize)]
#[serde(tag = "operation", rename_all = "snake_case", deny_unknown_fields)]
enum Request {
    ProvisionIdentity { seed: String, prekey_count: u32 },
    RefreshIdentity { seed: String, local: session::LocalIdentity, prekey_count: u32, first_prekey_id: u32, signed_prekey_id: u32, pq_prekey_id: u32 },
    VerifyBundle { bundle: identity::SignedBundle, expected_uuid: String },
    Encrypt { local: session::LocalIdentity, peer: session::PeerState, bundle: Option<identity::SignedBundle>, selected_prekey: Option<u32>, plaintext: String },
    DescribeMessage { wire: session::WireMessage, expected_sender: String, expected_recipient: String },
    DecodeWire { bytes: String, expected_recipient: String },
    Decrypt { local: session::LocalIdentity, peer: session::PeerState, wire: session::WireMessage, keys: session::ReceiveKeys },
}

fn dispatch(bytes: &[u8]) -> Result<Value, String> {
    let request: Request = serde_json::from_slice(bytes).map_err(|_| "Invalid security request")?;
    match request {
        Request::ProvisionIdentity { mut seed, prekey_count } => {
            let decoded = STANDARD.decode(&seed);
            seed.zeroize();
            let mut decoded = decoded.map_err(|_| "Invalid root seed")?;
            let result = identity::provision(&decoded, prekey_count);
            decoded.zeroize();
            result
        }
        Request::RefreshIdentity { mut seed, local, prekey_count, first_prekey_id, signed_prekey_id, pq_prekey_id } => {
            let decoded=STANDARD.decode(&seed);
            seed.zeroize();
            let mut decoded=decoded.map_err(|_|"Invalid root seed")?;
            let result=identity::refresh(&decoded,&local,prekey_count,first_prekey_id,signed_prekey_id,pq_prekey_id);
            decoded.zeroize();
            result
        }
        Request::VerifyBundle { bundle, expected_uuid } => {
            identity::verify_bundle(&bundle, &expected_uuid).map(|body| json!({"bundle":body}))
        }
        Request::Encrypt {local,peer,bundle,selected_prekey,plaintext} => session::encrypt(local,peer,bundle,selected_prekey,plaintext),
        Request::DescribeMessage {wire,expected_sender,expected_recipient} => session::describe(wire,expected_sender,expected_recipient),
        Request::DecodeWire {bytes,expected_recipient} => session::decode_wire(bytes,expected_recipient),
        Request::Decrypt {local,peer,wire,keys} => session::decrypt(local,peer,wire,keys),
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn msgat_security_abi_version() -> u32 { 2 }

/// # Safety
/// Input must be readable for length bytes. Output must point to writable,
/// initially empty storage and may not alias input. Host calls are serialized
/// per identity; persistence of a returned operation is the host's transaction.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn msgat_security_call(input: *const u8, length: usize, output: *mut MsgatBuffer) -> i32 {
    if input.is_null() || output.is_null() || length == 0 || length > MAX_REQUEST { return -1; }
    unsafe { ptr::write(output, MsgatBuffer { data: ptr::null_mut(), length: 0 }); }
    let bytes = unsafe { std::slice::from_raw_parts(input, length) };
    let result = std::panic::catch_unwind(|| dispatch(bytes));
    let response = match result {
        Ok(Ok(value)) => json!({"ok":true,"result":value}),
        Ok(Err(error)) => json!({"ok":false,"error":error}),
        Err(_) => json!({"ok":false,"error":"Security operation failed"}),
    };
    let Ok(bytes) = serde_json::to_vec(&response) else { return -2; };
    let mut bytes = bytes.into_boxed_slice();
    let buffer = MsgatBuffer { data: bytes.as_mut_ptr(), length: bytes.len() };
    std::mem::forget(bytes);
    unsafe { ptr::write(output, buffer); }
    0
}

/// # Safety
/// buffer must be writable and contain only an allocation returned by this
/// library. No other thread may retain or access its data after this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn msgat_buffer_free(buffer: *mut MsgatBuffer) {
    if buffer.is_null() { return; }
    let value = unsafe { ptr::replace(buffer, MsgatBuffer { data: ptr::null_mut(), length: 0 }) };
    if value.data.is_null() { return; }
    let mut bytes = unsafe { Box::from_raw(ptr::slice_from_raw_parts_mut(value.data, value.length)) };
    bytes.zeroize();
}
