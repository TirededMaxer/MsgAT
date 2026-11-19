// SPDX-License-Identifier: AGPL-3.0-only
mod identity;
mod contacts;
mod events;
mod activity;
mod live_location;
mod objects;
mod object_work;
mod object_cleanup;
mod session;
mod stickers;
mod web_preview;
mod rich_text;
mod capture;

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
    ParseContactCode { value: String },
    ContactSafety { local_id: String, local: identity::Certificate, peer_id: String, peer: identity::Certificate, scan: Option<String> },
    ContactTransition { state: contacts::State, action: contacts::Action },
    ActivityTransition { state: activity::State, action: activity::Action },
    LiveLocationTransition { state: Option<live_location::State>, event_kind: String, event_id: String, body: Value,
                             created_at: u64, now: u64 },
    CreateRevocation { seed: String },
    VerifyRevocation { proof: String },
    ProvisionIdentity { seed: String, prekey_count: u32, directory_epoch: Option<String> },
    RefreshIdentity { seed: String, local: session::LocalIdentity, prekey_count: u32, first_prekey_id: u32, signed_prekey_id: u32, pq_prekey_id: u32, directory_epoch: Option<String> },
    VerifyBundle { bundle: identity::SignedBundle, expected_uuid: String },
    Encrypt { local: session::LocalIdentity, peer: session::PeerState, bundle: Option<identity::SignedBundle>, selected_prekey: Option<u32>, plaintext: String },
    DescribeMessage { wire: session::WireMessage, expected_sender: String, expected_recipient: String },
    DecodeWire { bytes: String, expected_recipient: String, #[serde(default)] delivery: bool },
    Decrypt { local: session::LocalIdentity, peer: session::PeerState, wire: session::WireMessage, keys: session::ReceiveKeys,
              expected_event_id: Option<String> },
    CreateObjectWork {},
    ObjectWorkStatus { work_id: String },
    CancelObjectWork { work_id: String },
    CloseObjectWork { work_id: String },
    ReportObjectWork { work_id: String, total:u64, completed:u64 },
    CreateObject { seed: String, size: u64 },
    OpenCaptureFile {manifest:objects::Manifest,directory:String},
    ReadCaptureFile {capture_id:String,offset:u64,length:usize},
    WriteCaptureFile {capture_id:String,offset:u64,bytes:String},
    SyncCaptureFile {capture_id:String},
    ResizeCaptureFile {capture_id:String,size:u64},
    FreezeCaptureFile {capture_id:String},
    CaptureFileStatus {capture_id:String},
    CloseCaptureFile {capture_id:String},
    ImportCaptureFile {capture_id:String,manifest:objects::Manifest,directory:String,maximum_size:u64,work_id:Option<String>},
    SealObjectChunk { chunk: objects::Chunk },
    OpenObjectChunk { chunk: objects::Chunk },
    CheckObjectChunk { chunk: objects::Chunk },
    ValidateObjectManifest { manifest: objects::Manifest },
    ImportObjectFile { manifest: objects::Manifest, source: String, directory: String, maximum_size: u64, work_id: Option<String> },
    InspectStickerObject { manifest: objects::Manifest, directory: String },
    ReadObjectRange { manifest: objects::Manifest, directory: String, offset: u64, length: usize },
    RekeyObjectFile { source: objects::Manifest, manifest: objects::Manifest, directory: String, maximum_size: u64, work_id: Option<String> },
    SyncObjectPublication { directory: String, object_id: String },
    RemoveObjectFiles { directory: String, object_id: String },
    OpenStickerSource { source: String },
    CloseStickerSource { session: String },
    ReadStickerSource { session: String, id: usize, offset: usize, length: usize },
    InspectStickerChunk { session: String, id: usize, offset: usize, bytes: String, finish: bool },
    DiscardStickerInspection { session: String, id: usize },
    WebPreviewTarget { url: String },
    WebPreviewCandidate { text: String },
    WebPreviewPolicy { url: String, addresses: Vec<String> },
    InspectWebPage { url: String, html: String },
    ValidateApplicationEvent { bytes: String },
    RenderMessageText { text:String },
    CopyMessageText { text: String, revealed: Vec<u32>, placeholder: String },
    ReplyQuoteText { text: String },
    ReplyQuoteSelection { text: String, selection: String },
    LocateReplyQuote { text: String, preview: String },
    VisibleMessageUrls { text: String },
    SummarizeMessageText {text:String,limit:usize},
    FormatMessageSelection {text:String,start:u32,end:u32,kind:String},
    ReduceMessageChange { previous: Option<events::MessageChange>, change: events::MessageChange, #[serde(default)] incoming: bool },
}

fn dispatch(bytes: &[u8]) -> Result<Value, String> {
    let request: Request = serde_json::from_slice(bytes).map_err(|_| "Invalid security request")?;
    match request {
        Request::OpenCaptureFile{manifest,directory}=>capture::open(manifest,directory),
        Request::ReadCaptureFile{capture_id,offset,length}=>capture::read(capture_id,offset,length),
        Request::WriteCaptureFile{capture_id,offset,bytes}=>capture::write(capture_id,offset,bytes),
        Request::SyncCaptureFile{capture_id}=>capture::sync(capture_id),
        Request::ResizeCaptureFile{capture_id,size}=>capture::resize(capture_id,size),
        Request::FreezeCaptureFile{capture_id}=>capture::freeze(capture_id),
        Request::CaptureFileStatus{capture_id}=>capture::status(capture_id),
        Request::CloseCaptureFile{capture_id}=>capture::close(capture_id),
        Request::ImportCaptureFile{capture_id,manifest,directory,maximum_size,work_id}=>capture::import(capture_id,manifest,directory,maximum_size,work_id),
        Request::ParseContactCode { value } => contacts::parse_code(value),
        Request::ContactSafety { local_id, local, peer_id, peer, scan } => contacts::safety(local_id,local,peer_id,peer,scan),
        Request::ContactTransition { state, action } => contacts::transition(state,action),
        Request::ActivityTransition { state, action } => activity::transition(state,action),
        Request::LiveLocationTransition { state, event_kind, event_id, body, created_at, now } =>
            live_location::transition(state, &event_kind, &event_id, &body, created_at, now),
        Request::CreateRevocation { mut seed } => {
            let decoded=STANDARD.decode(&seed); seed.zeroize();
            let mut decoded=decoded.map_err(|_| "Invalid root seed")?;
            let result=identity::create_revocation(&decoded); decoded.zeroize(); result
        }
        Request::VerifyRevocation { proof } => identity::verify_revocation(&proof),
        Request::ProvisionIdentity { mut seed, prekey_count, directory_epoch } => {
            let decoded = STANDARD.decode(&seed);
            seed.zeroize();
            let mut decoded = decoded.map_err(|_| "Invalid root seed")?;
            let result = identity::provision(&decoded, prekey_count, directory_epoch);
            decoded.zeroize();
            result
        }
        Request::RefreshIdentity { mut seed, local, prekey_count, first_prekey_id, signed_prekey_id, pq_prekey_id, directory_epoch } => {
            let decoded=STANDARD.decode(&seed);
            seed.zeroize();
            let mut decoded=decoded.map_err(|_|"Invalid root seed")?;
            let result=identity::refresh(&decoded,&local,prekey_count,first_prekey_id,signed_prekey_id,pq_prekey_id,directory_epoch);
            decoded.zeroize();
            result
        }
        Request::VerifyBundle { bundle, expected_uuid } => {
            identity::verify_bundle(&bundle, &expected_uuid).map(|body| json!({"bundle":body}))
        }
        Request::Encrypt {local,peer,bundle,selected_prekey,plaintext} => session::encrypt(local,peer,bundle,selected_prekey,plaintext),
        Request::DescribeMessage {wire,expected_sender,expected_recipient} => session::describe(wire,expected_sender,expected_recipient),
        Request::DecodeWire {bytes,expected_recipient,delivery} => match session::decode_wire(bytes,expected_recipient) {
            Err(_) if delivery => Ok(json!({"disposition":"retry"})),
            result => result,
        },
        Request::Decrypt {local,peer,wire,keys,expected_event_id} => session::decrypt(local,peer,wire,keys,expected_event_id.as_deref()),
        Request::CreateObjectWork {} => object_work::create(),
        Request::ObjectWorkStatus {work_id} => object_work::status(&work_id),
        Request::CancelObjectWork {work_id} => object_work::cancel(&work_id),
        Request::CloseObjectWork {work_id} => object_work::close(&work_id),
        Request::ReportObjectWork {work_id,total,completed} => object_work::report(&work_id,total,completed),
        Request::CreateObject { mut seed, size } => {
            let decoded = STANDARD.decode(&seed);
            seed.zeroize();
            let mut decoded = decoded.map_err(|_| "Invalid root seed")?;
            let result = objects::create(&decoded, size);
            decoded.zeroize();
            result
        }
        Request::SealObjectChunk {chunk} => objects::crypt(chunk, true, true),
        Request::OpenObjectChunk {chunk} => objects::crypt(chunk, false, true),
        Request::CheckObjectChunk {chunk} => objects::crypt(chunk, false, false),
        Request::ValidateObjectManifest {manifest} => objects::validate(manifest),
        Request::ImportObjectFile {manifest,source,directory,maximum_size,work_id} => objects::import_file(manifest,source,directory,maximum_size,work_id),
        Request::InspectStickerObject {manifest,directory} => objects::inspect_sticker_object(manifest,directory),
        Request::ReadObjectRange {manifest,directory,offset,length} => objects::read_range(manifest,directory,offset,length),
        Request::RekeyObjectFile {source,manifest,directory,maximum_size,work_id} => objects::rekey_file(source,manifest,directory,maximum_size,work_id),
        Request::SyncObjectPublication {directory,object_id} => objects::sync_publication(&directory,&object_id),
        Request::RemoveObjectFiles {directory,object_id} => object_cleanup::remove(&directory,&object_id),
        Request::OpenStickerSource {source} => stickers::open(source),
        Request::CloseStickerSource {session} => stickers::close(session),
        Request::ReadStickerSource {session,id,offset,length} => stickers::read(session,id,offset,length),
        Request::InspectStickerChunk {session,id,offset,bytes,finish} => stickers::inspect_chunk(session,id,offset,bytes,finish),
        Request::DiscardStickerInspection {session,id} => stickers::discard_inspection(session,id),
        Request::WebPreviewTarget {url} => {let t=web_preview::target(&url)?;Ok(json!({"url":url,"host":t.host,"port":t.port,"secure":t.secure}))},
        Request::WebPreviewCandidate {text} => web_preview::candidate(text),
        Request::WebPreviewPolicy {url,addresses} => web_preview::policy(url,addresses),
        Request::InspectWebPage {url,html} => web_preview::inspect(url,html),
        Request::ValidateApplicationEvent {bytes} => events::validate(bytes),
        Request::RenderMessageText {text} => rich_text::render(text),
        Request::CopyMessageText { text, revealed, placeholder } => rich_text::copy_text(text, revealed, placeholder),
        Request::ReplyQuoteText { text } => rich_text::reply_quote_text(text),
        Request::ReplyQuoteSelection { text, selection } => rich_text::reply_quote_selection(text, selection),
        Request::LocateReplyQuote { text, preview } => rich_text::locate_reply_quote(text, preview),
        Request::VisibleMessageUrls { text } => rich_text::visible_urls(text),
        Request::SummarizeMessageText {text,limit} => rich_text::summarize(text,limit),
        Request::FormatMessageSelection {text,start,end,kind} => rich_text::format_selection(text,start,end,kind),
        Request::ReduceMessageChange {previous,change,incoming} => events::reduce(previous,change,incoming),
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

/// # Safety
/// data must point to length writable bytes with exclusive access.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn msgat_zero_bytes(data: *mut std::ffi::c_void, length: usize) {
    if data.is_null() || length == 0 { return; }
    let bytes = unsafe { std::slice::from_raw_parts_mut(data.cast::<u8>(), length) };
    bytes.zeroize();
}
