// SPDX-License-Identifier: AGPL-3.0-only
//! One application-event parser and message-change reducer for every native host.
use base64::{Engine as _, engine::general_purpose::STANDARD};
use serde::{Deserialize, Deserializer, Serialize, de::{self, MapAccess, SeqAccess, Visitor}};
use serde_json::{Map, Value, json};
use std::fmt;
use zeroize::Zeroizing;

/// Reject duplicate properties before a host JSON library can normalize them.
struct UniqueValue(Value);
impl<'de> Deserialize<'de> for UniqueValue {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        struct UniqueVisitor;
        impl<'de> Visitor<'de> for UniqueVisitor {
            type Value = UniqueValue;
            fn expecting(&self, output: &mut fmt::Formatter) -> fmt::Result { output.write_str("an unambiguous JSON value") }
            fn visit_bool<E: de::Error>(self, value: bool) -> Result<Self::Value, E> { Ok(UniqueValue(value.into())) }
            fn visit_i64<E: de::Error>(self, value: i64) -> Result<Self::Value, E> { Ok(UniqueValue(value.into())) }
            fn visit_u64<E: de::Error>(self, value: u64) -> Result<Self::Value, E> { Ok(UniqueValue(value.into())) }
            fn visit_f64<E: de::Error>(self, _: f64) -> Result<Self::Value, E> { Err(E::custom("Fractional event numbers are not supported")) }
            fn visit_str<E: de::Error>(self, value: &str) -> Result<Self::Value, E> { Ok(UniqueValue(value.into())) }
            fn visit_string<E: de::Error>(self, value: String) -> Result<Self::Value, E> { Ok(UniqueValue(value.into())) }
            fn visit_unit<E: de::Error>(self) -> Result<Self::Value, E> { Ok(UniqueValue(Value::Null)) }
            fn visit_seq<A: SeqAccess<'de>>(self, mut sequence: A) -> Result<Self::Value, A::Error> {
                let mut result = Vec::new();
                while let Some(UniqueValue(value)) = sequence.next_element()? { result.push(value); }
                Ok(UniqueValue(Value::Array(result)))
            }
            fn visit_map<A: MapAccess<'de>>(self, mut object: A) -> Result<Self::Value, A::Error> {
                let mut result = Map::new();
                while let Some(key) = object.next_key::<String>()? {
                    if result.contains_key(&key) { return Err(de::Error::custom("Duplicate event property")); }
                    let UniqueValue(value) = object.next_value()?; result.insert(key, value);
                }
                Ok(UniqueValue(Value::Object(result)))
            }
        }
        deserializer.deserialize_any(UniqueVisitor)
    }
}

#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Event {
    version: u8,
    event_id: String,
    kind: String,
    body: Value,
    created_at: u64,
}

pub fn uuid_v4(value: &str) -> bool {
    let bytes = value.as_bytes();
    bytes.len() == 36 && bytes.iter().enumerate().all(|(index, byte)| {
        if [8, 13, 18, 23].contains(&index) { *byte == b'-' }
        else { byte.is_ascii_digit() || (b'a'..=b'f').contains(byte) }
    }) && bytes[14] == b'4' && b"89ab".contains(&bytes[19])
}
fn account(value: &str) -> bool { value.len() == 64 && value.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) }
fn fields<'a>(body: &'a Value, required: &[&str], optional: &[&str]) -> Result<&'a Map<String, Value>, String> {
    let object = body.as_object().ok_or("Invalid event body")?;
    if required.iter().any(|key| !object.contains_key(*key)) || object.keys().any(|key| !required.contains(&key.as_str()) && !optional.contains(&key.as_str())) {
        return Err("Unsupported event properties".into());
    }
    Ok(object)
}
fn text<'a>(value: &'a Value, key: &str) -> Result<&'a str, String> { value[key].as_str().ok_or_else(|| "Invalid event string".into()) }
fn message_text(value: &str) -> bool { !value.trim().is_empty() && value.len() <= 48 * 1024 }
fn location_label(body: &Value) -> Result<(), String> {
    if let Some(label) = body.get("label") {
        let label = label.as_str().ok_or("Invalid location label")?;
        if label.trim().is_empty() || label.len() > 256 || label.chars().any(char::is_control) {
            return Err("Invalid location label".into());
        }
    }
    Ok(())
}
fn reply(body: &Value) -> Result<(), String> {
    if let Some(value) = body.get("reply") {
        fields(value, &["event_id", "author", "preview"], &[])?;
        if !uuid_v4(text(value, "event_id")?) || !account(text(value, "author")?) || text(value, "preview")?.len() > 4096 { return Err("Invalid reply metadata".into()); }
    }
    Ok(())
}

fn forwarded(body: &Value) -> Result<(),String> {
    if let Some(value)=body.get("forward") {
        fields(value,&["original_at"],&["name"])?;
        if value["original_at"].as_u64().is_none_or(|time|time>253_402_214_399_999) {return Err("Invalid forward date".into());}
        if let Some(name)=value.get("name") {
            let name=name.as_str().ok_or("Invalid forward name")?;
            if name.trim().is_empty() || name.len()>256 || name.chars().any(char::is_control) {return Err("Invalid forward name".into());}
        }
    }
    Ok(())
}

pub fn validate(encoded: String) -> Result<Value, String> {
    let encoded = Zeroizing::new(encoded);
    let bytes = Zeroizing::new(STANDARD.decode(encoded.as_bytes()).map_err(|_| "Invalid event encoding")?);
    let value = parse(&bytes)?;
    validate_event(value)
}

fn parse(bytes: &[u8]) -> Result<Value, String> {
    if bytes.is_empty() || bytes.len() > 64 * 1024 { return Err("Invalid application event size".into()); }
    let UniqueValue(value) = serde_json::from_slice(bytes).map_err(|_| "Ambiguous or malformed application event")?;
    Ok(value)
}

pub enum IncomingEvent { Apply(Value), Reject, Retry }

/// Only permanently reject a known-version event with an unambiguous authenticated ID.
pub fn inspect_incoming(bytes: &[u8], expected_id: &str) -> IncomingEvent {
    let Ok(value) = parse(bytes) else { return IncomingEvent::Retry };
    if !uuid_v4(expected_id) || value["event_id"].as_str() != Some(expected_id)
        || value["version"].as_u64() != Some(2) {
        return IncomingEvent::Retry;
    }
    match validate_event(value) {
        Ok(mut result) => IncomingEvent::Apply(result["event"].take()),
        Err(error) if error == UNSUPPORTED_EVENT => IncomingEvent::Retry,
        Err(_) => IncomingEvent::Reject,
    }
}

const UNSUPPORTED_EVENT: &str = "Unsupported application event";

fn validate_event(value: Value) -> Result<Value, String> {
    let event: Event = serde_json::from_value(value).map_err(|_| "Invalid application event fields")?;
    if event.version != 2 || !uuid_v4(&event.event_id) || event.created_at > 253_402_214_399_999 { return Err("Invalid application event identity or timestamp".into()); }
    if let Some(duration) = event.body.get("expire_after_ms") {
        if !matches!(duration.as_u64(), Some(60_000 | 3_600_000 | 86_400_000 | 604_800_000 | 2_592_000_000)) {
            return Err("Invalid message expiration duration".into());
        }
    }
    match event.kind.as_str() {
        "contact.request" | "contact.accept" | "contact.profile" => {
            fields(&event.body, &["nickname"], &["revision","avatar","request_id","request_revision"])?;
            match event.kind.as_str() {
                "contact.request" => {if !uuid_v4(text(&event.body,"request_id")?) || event.body["request_revision"].as_u64().is_none_or(|r|r==0 || r>u32::MAX as u64){return Err("Invalid contact request binding".into())}},
                "contact.accept" => {if !uuid_v4(text(&event.body,"request_id")?) || event.body.get("request_revision").is_some(){return Err("Invalid contact acceptance binding".into())}},
                _ => {if event.body.get("request_id").is_some() || event.body.get("request_revision").is_some(){return Err("Unexpected contact request binding".into())}}
            }
            let name = text(&event.body, "nickname")?;
            if name.trim().is_empty() || name.len() > 256 { return Err("Invalid contact nickname".into()); }
            if event.body.get("revision").is_some() || event.body.get("avatar").is_some() {
                fields(&event.body,&["nickname","revision","avatar"],&["request_id","request_revision"])?;
                if event.body["revision"].as_u64().is_none_or(|value|value==0 || value>u32::MAX as u64) {return Err("Invalid profile revision".into());}
                if !event.body["avatar"].is_null() {
                    let avatar: crate::objects::Manifest=serde_json::from_value(event.body["avatar"].clone()).map_err(|_|"Invalid avatar manifest")?;
                    crate::objects::validate(avatar)?;
                    if event.body["avatar"]["size"].as_u64().is_none_or(|size|size>512*1024)
                        || !matches!(event.body["avatar"]["mime"].as_str(),Some("image/jpeg"|"image/png")) {return Err("Avatar exceeds the supported image limits".into());}
                }
            }
        }
        "contact.decline" => {fields(&event.body,&["request_id"],&[])?;if !uuid_v4(text(&event.body,"request_id")?){return Err("Invalid contact decision".into())}}
        "contact.withdraw" => {fields(&event.body,&["request_id","request_revision"],&[])?;if !uuid_v4(text(&event.body,"request_id")?) || event.body["request_revision"].as_u64().is_none_or(|r|r==0 || r>u32::MAX as u64){return Err("Invalid contact withdrawal".into())}}
        "contact.delete" => {
            fields(&event.body,&[],&["relationship_ids"])?;
            if let Some(ids)=event.body.get("relationship_ids") {
                let ids=ids.as_array().ok_or("Invalid contact relationship binding")?;
                if ids.len()>2 || ids.iter().any(|id|id.as_str().is_none_or(|id|!uuid_v4(id))){return Err("Invalid contact relationship binding".into())}
            }
        }
        "chat.text" => {
            fields(&event.body, &["text"], &["reply","forward","preview","silent","expire_after_ms"])?;
            if !message_text(text(&event.body, "text")?) { return Err("Invalid message text".into()); }
            if let Some(preview)=event.body.get("preview") {crate::web_preview::validate(preview,text(&event.body,"text")?)?;}
            if event.body.get("silent").is_some_and(|value|value.as_bool().is_none()) {return Err("Invalid silent delivery flag".into());}
            reply(&event.body)?;
            forwarded(&event.body)?;
        }
        "chat.contact" => {
            fields(&event.body, &["account", "name"], &["reply", "forward", "silent", "expire_after_ms"])?;
            let name = text(&event.body, "name")?;
            if !account(text(&event.body, "account")?)
                || name.trim().is_empty()
                || name.len() > 256
                || name.chars().any(char::is_control)
            {
                return Err("Invalid contact card".into());
            }
            if event.body.get("silent").is_some_and(|value| value.as_bool().is_none()) {
                return Err("Invalid silent delivery flag".into());
            }
            reply(&event.body)?;
            forwarded(&event.body)?;
        }
        "chat.location" => {
            fields(&event.body, &["latitude_e6", "longitude_e6"], &["label", "reply", "forward", "silent", "expire_after_ms"])?;
            crate::live_location::validate_point(&event.body)?;
            location_label(&event.body)?;
            if event.body.get("silent").is_some_and(|value| value.as_bool().is_none()) {
                return Err("Invalid silent delivery flag".into());
            }
            reply(&event.body)?;
            forwarded(&event.body)?;
        }
        "chat.location.start" => {
            fields(&event.body, &["latitude_e6", "longitude_e6", "expires_at"], &["label", "reply", "silent", "expire_after_ms"])?;
            crate::live_location::validate(&event.kind, &event.event_id, &event.body, event.created_at)?;
            location_label(&event.body)?;
            if event.body.get("silent").is_some_and(|value| value.as_bool().is_none()) {
                return Err("Invalid silent delivery flag".into());
            }
            reply(&event.body)?;
        }
        "chat.location.update" | "chat.location.end" => {
            let required: &[&str] = if event.kind == "chat.location.update" {
                &["message_id", "started_at", "expires_at", "revision", "latitude_e6", "longitude_e6"]
            } else {
                &["message_id", "started_at", "expires_at", "revision"]
            };
            fields(&event.body, required, &[])?;
            crate::live_location::validate(&event.kind, &event.event_id, &event.body, event.created_at)?;
        }
        "chat.attachment" => {
            fields(&event.body, &["object", "caption"], &["reply","forward","sticker","silent","album","expire_after_ms"])?;
            if text(&event.body, "caption")?.len() > 48 * 1024 { return Err("Invalid attachment caption".into()); }
            let manifest: crate::objects::Manifest = serde_json::from_value(event.body["object"].clone()).map_err(|_| "Invalid attachment manifest")?;
            crate::objects::validate(manifest)?;
            if let Some(album) = event.body.get("album") {
                fields(album, &["id", "index", "count"], &[])?;
                let id = text(album, "id")?;
                let count = album["count"].as_u64().filter(|value| (2..=10).contains(value))
                    .ok_or("Invalid album item count")?;
                let index = album["index"].as_u64().filter(|value| *value < count)
                    .ok_or("Invalid album item index")?;
                let mime = event.body["object"]["mime"].as_str().ok_or("Missing album media type")?;
                if !uuid_v4(id) || id == event.event_id.as_str()
                    || !(mime.starts_with("image/") || mime.starts_with("video/"))
                    || event.body.get("sticker").is_some() || event.body.get("forward").is_some()
                    || (index != 0 && event.body.get("reply").is_some()) {
                    return Err("Invalid album attachment".into());
                }
            }
            if let Some(sticker)=event.body.get("sticker") {
                if sticker.as_bool()!=Some(true) || event.body["object"]["size"].as_u64().is_none_or(|s|s==0 || s>8*1024*1024)
                    || !matches!(event.body["object"]["mime"].as_str(),Some("image/png"|"image/jpeg"|"image/gif"|"image/webp"|"image/bmp")) {return Err("Invalid sticker attachment".into());}
            }
            if event.body.get("silent").is_some_and(|value|value.as_bool().is_none()) {return Err("Invalid silent delivery flag".into());}
            reply(&event.body)?;
            forwarded(&event.body)?;
        }
        "chat.activity" => {
            fields(&event.body,&["counter","online","typing","request","expires_at"],&[])?;
            let body:crate::activity::Payload=serde_json::from_value(event.body.clone()).map_err(|_|"Invalid activity body")?;
            body.validate(event.created_at)?;
        }
        "chat.receipt" => {
            fields(&event.body, &["message_id", "state"], &[])?;
            if !uuid_v4(text(&event.body, "message_id")?) || !["delivered", "read"].contains(&text(&event.body, "state")?) { return Err("Invalid receipt".into()); }
        }
        "chat.pin" => {
            fields(&event.body,&["message_id","author","sequence","pinned","observed"],&["notify"])?;
            if !uuid_v4(text(&event.body,"message_id")?) || event.body["message_id"].as_str()==Some(event.event_id.as_str())
                || !account(text(&event.body,"author")?) || event.body["sequence"].as_u64().is_none_or(|n|n==0 || n>u32::MAX as u64) {
                return Err("Invalid message pin identity".into());
            }
            let pinned=event.body["pinned"].as_bool().ok_or("Invalid message pin state")?;
            let observed=event.body["observed"].as_array().ok_or("Invalid observed pin clocks")?;
            if observed.len()>2 || (pinned && !observed.is_empty()) {return Err("Invalid observed pin clocks".into());}
            let mut actors=std::collections::HashSet::new();
            for clock in observed {
                fields(clock,&["actor","sequence"],&[])?;
                let actor=text(clock,"actor")?;
                if !account(actor) || !actors.insert(actor) || clock["sequence"].as_u64().is_none_or(|n|n==0 || n>u32::MAX as u64) {
                    return Err("Invalid observed pin clock".into());
                }
            }
            if event.body.get("notify").is_some_and(|v|v.as_bool().is_none() || (!pinned && v.as_bool()==Some(true))) {
                return Err("Invalid pin notification flag".into());
            }
        }
        "chat.edit" | "chat.revoke" => {
            let editing = event.kind == "chat.edit";
            fields(&event.body, if editing { &["message_id", "revision", "text"] } else { &["message_id", "revision"] },
                if editing { &["caption"] } else { &[] })?;
            if !uuid_v4(text(&event.body, "message_id")?) || event.body["message_id"].as_str() == Some(event.event_id.as_str()) { return Err("Invalid message change target".into()); }
            let revision = event.body["revision"].as_u64().ok_or("Invalid message revision")?;
            let caption = match event.body.get("caption") {
                None => false,
                Some(value) => value.as_bool().ok_or("Invalid caption edit flag")?,
            };
            if revision == 0 || revision > u32::MAX as u64 { return Err("Invalid message change".into()); }
            if editing && !edit_text(text(&event.body, "text")?, caption) { return Err("Invalid message change".into()); }
        }
        "call.offer" | "call.answer" | "call.ice" | "call.challenge" | "call.confirm" | "call.reject" | "call.end" | "call.busy" => {
            let required: &[&str] = match event.kind.as_str() {
                "call.offer" => &["call_id", "expires_at", "sdp", "video"],
                "call.answer" => &["call_id", "expires_at", "sdp"],
                "call.ice" => &["call_id", "expires_at", "sdp_mid", "mline_index", "candidate"],
                "call.challenge" | "call.confirm" => &["call_id", "expires_at", "nonce"],
                _ => &["call_id", "expires_at"],
            };
            fields(&event.body, required, &[])?;
            let expires = event.body["expires_at"].as_u64().ok_or("Invalid call deadline")?;
            if !uuid_v4(text(&event.body, "call_id")?) || expires <= event.created_at || expires > event.created_at + 120_000 {
                return Err("Invalid call identity or deadline".into());
            }
            match event.kind.as_str() {
                "call.offer" | "call.answer" => {
                    let sdp = text(&event.body, "sdp")?;
                    if !message_text(sdp) { return Err("Invalid call description".into()); }
                    if event.kind == "call.offer" {
                        let video = event.body["video"].as_bool().ok_or("Invalid call media type")?;
                        let offered_video = sdp.lines().any(|line| line.starts_with("m=video "));
                        if video != offered_video { return Err("Call media type does not match its offer".into()); }
                    }
                }
                "call.ice" => {
                    let index = event.body["mline_index"].as_u64().ok_or("Invalid ICE line")?;
                    if text(&event.body, "sdp_mid")?.len() > 64 || index > 255 || text(&event.body, "candidate")?.is_empty()
                        || text(&event.body, "candidate")?.len() > 4096 { return Err("Invalid ICE candidate".into()); }
                }
                "call.challenge" | "call.confirm" => {
                    let nonce = STANDARD.decode(text(&event.body, "nonce")?).map_err(|_| "Invalid call challenge")?;
                    if nonce.len() != 32 { return Err("Invalid call challenge".into()); }
                }
                _ => {}
            }
        }
        _ => return Err(UNSUPPORTED_EVENT.into()),
    }
    Ok(json!({"event":event}))
}

#[derive(Clone, Deserialize, Serialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct MessageChange {
    pub revision: u32,
    pub revoked: bool,
    pub text: Option<String>,
    #[serde(default, skip_serializing_if = "is_false")]
    pub caption: bool,
}
fn is_false(value: &bool) -> bool { !*value }
fn edit_text(text: &str, caption: bool) -> bool {
    if caption { text.len() <= 48 * 1024 } else { message_text(text) }
}
impl MessageChange {
    fn check(&self) -> Result<(), String> {
        if self.revision == 0 || (self.revoked && (self.text.is_some() || self.caption))
            || (!self.revoked && !self.text.as_deref().is_some_and(|text| edit_text(text, self.caption))) {
            return Err("Invalid saved message change".into());
        }
        Ok(())
    }
}
pub fn reduce(previous: Option<MessageChange>, mut change: MessageChange, incoming: bool) -> Result<Value, String> {
    change.check()?;
    if let Some(old) = previous.as_ref() {
        old.check()?;
        if old.revoked { return Ok(json!({"change":old,"changed":false})); }
        if change.revoked { change.revision = change.revision.max(old.revision); }
        else if change.revision < old.revision { return Ok(json!({"change":old,"changed":false})); }
        else if change.revision == old.revision {
            if &change != old {
                if incoming { return Ok(json!({"change":old,"changed":false,"conflict":true})); }
                return Err("Conflicting message revision".into());
            }
            return Ok(json!({"change":old,"changed":false}));
        }
    }
    Ok(json!({"change":change,"changed":true}))
}
