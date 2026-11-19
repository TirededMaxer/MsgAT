// SPDX-License-Identifier: AGPL-3.0-only
//! One bounded contact-request state machine for all native hosts.
//! Request counters order requests from one identity; decisions bind the exact request UUID.
use crate::events::uuid_v4;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

#[derive(Clone, Default, Deserialize, Serialize, PartialEq)]
#[serde(default, deny_unknown_fields)]
pub struct State {
    outgoing: bool,
    incoming: bool,
    accepted: bool,
    blocked: bool,
    outbound_id: Option<String>,
    outbound_revision: u32,
    inbound_id: Option<String>,
    inbound_revision: u32,
    inbound_closed: bool,
    accepted_ids: Vec<String>,
    last_outcome: String,
}

#[derive(Deserialize)]
#[serde(tag="kind", rename_all="snake_case", deny_unknown_fields)]
pub enum Action {
    Request { id: String },
    ReceiveRequest { id: String, revision: u32 },
    Accept,
    ReceiveAccept { id: String },
    Decline,
    ReceiveDecline { id: String },
    Withdraw,
    ReceiveWithdraw { id: String, revision: u32 },
    Delete,
    ReceiveDelete { ids: Vec<String> },
    Block,
    Unblock,
}
fn validate_id(id:&str)->Result<(),String> {
    if !uuid_v4(id) {return Err("Invalid contact request identity".into())} Ok(())
}
fn add_acceptance(state:&mut State,id:String) {
    if !state.accepted_ids.contains(&id) {state.accepted_ids.push(id);state.accepted_ids.sort();}
    state.accepted=true;
}
fn end_relationship(state:&mut State) {
    state.accepted=false;state.accepted_ids.clear();state.incoming=false;
    state.outgoing=false;state.inbound_closed=true;
}

pub fn transition(mut state:State,action:Action)->Result<Value,String> {
    if state.accepted_ids.len()>2 || state.accepted_ids.iter().any(|id|!uuid_v4(id))
        || state.outbound_id.as_deref().is_some_and(|id|!uuid_v4(id))
        || state.inbound_id.as_deref().is_some_and(|id|!uuid_v4(id))
        || state.last_outcome.len()>32 {return Err("Invalid stored contact state".into())}
    let before=state.clone();
    match action {
        Action::Request{id} => {
            validate_id(&id)?;
            if state.blocked {return Err("Unblock this contact before sending a request".into())}
            if state.accepted {return Err("This contact is already accepted".into())}
            // An explicit resend supersedes the old request; transport retries retain their original envelope.
            state.outbound_revision=state.outbound_revision.checked_add(1).ok_or("Contact request counter is exhausted")?;
            state.outbound_id=Some(id);state.outgoing=true;state.last_outcome="pending".into();
        }
        Action::ReceiveRequest{id,revision} => {
            validate_id(&id)?;if revision==0 {return Err("Invalid request revision".into())}
            if !state.blocked && !state.accepted && revision>state.inbound_revision {
                state.inbound_id=Some(id);state.inbound_revision=revision;
                state.inbound_closed=false;state.incoming=true;
            }
        }
        Action::Accept => {
            if state.blocked || !state.incoming || state.inbound_closed {return Err("No contact request awaits acceptance".into())}
            let id=state.inbound_id.clone().ok_or("Ask this contact to send a new request")?;
            state.incoming=false;state.inbound_closed=true;add_acceptance(&mut state,id);
        }
        Action::ReceiveAccept{id} => {
            validate_id(&id)?;
            if !state.blocked && state.outgoing && state.outbound_id.as_deref()==Some(id.as_str()) {
                state.outgoing=false;state.last_outcome="accepted".into();add_acceptance(&mut state,id);
            }
        }
        Action::Decline => {
            if !state.incoming || state.inbound_closed {return Err("No contact request awaits a decision".into())}
            if state.inbound_id.is_none() {return Err("Ask this contact to send a new request".into())}
            state.incoming=false;state.inbound_closed=true;
        }
        Action::ReceiveDecline{id} => {
            validate_id(&id)?;
            if state.outgoing && state.outbound_id.as_deref()==Some(id.as_str()) {
                state.outgoing=false;state.last_outcome="declined".into();
            }
        }
        Action::Withdraw => {
            if !state.outgoing || state.accepted {return Err("No outgoing request can be withdrawn".into())}
            if state.outbound_id.is_none(){return Err("Send a new contact request first".into())}
            state.outgoing=false;state.last_outcome="withdrawn".into();
        }
        Action::ReceiveWithdraw{id,revision} => {
            validate_id(&id)?;if revision==0 {return Err("Invalid request revision".into())}
            if revision>state.inbound_revision || (revision==state.inbound_revision && state.inbound_id.as_deref()==Some(id.as_str())) {
                // Tombstones also cover withdrawal arriving before the original request.
                state.inbound_id=Some(id.clone());state.inbound_revision=revision;
                state.incoming=false;state.inbound_closed=true;
                if state.accepted_ids.contains(&id) {
                    state.accepted_ids.retain(|value|value!=&id);
                    state.accepted=!state.accepted_ids.is_empty();
                }
            }
        }
        Action::Delete => {end_relationship(&mut state);state.last_outcome="deleted".into();}
        Action::ReceiveDelete{ids} => {
            if ids.len()>2 || ids.iter().any(|id|!uuid_v4(id)){return Err("Invalid contact relationship binding".into())}
            let bound=ids.iter().any(|id|state.accepted_ids.contains(id));
            let legacy=ids.is_empty() && state.accepted_ids.is_empty() && state.accepted;
            if bound || legacy {end_relationship(&mut state);state.last_outcome="deleted".into();}
        }
        Action::Block => {
            state.blocked=true;state.outgoing=false;state.incoming=false;state.inbound_closed=true;
        }
        Action::Unblock => {state.blocked=false;}
    }
    if state.accepted_ids.len()>2 {return Err("Too many simultaneous contact bindings".into())}
    Ok(json!({"applied":state!=before,"state":state,"previous":before}))
}

/// Signal's standard numeric/scannable fingerprint, after validating both root bindings.
pub fn safety(local_id:String,local:crate::identity::Certificate,peer_id:String,peer:crate::identity::Certificate,scan:Option<String>)->Result<Value,String>{
    if local_id==peer_id {return Err("Choose another identity to compare".into())}
    let local_key=crate::identity::verify_certificate(&local,&local_id)?;
    let peer_key=crate::identity::verify_certificate(&peer,&peer_id)?;
    let value=libsignal_protocol::Fingerprint::new(2,5200,local_id.as_bytes(),&local_key,peer_id.as_bytes(),&peer_key).map_err(|e|e.to_string())?;
    use base64::Engine as _;
    let matches=scan.map(|encoded|{
        if encoded.len()>1024{return Err("Safety code is too large".to_string())}
        let bytes=base64::engine::general_purpose::STANDARD.decode(encoded).map_err(|_|"Invalid safety code".to_string())?;
        value.scannable.compare(&bytes).map_err(|e|e.to_string())
    }).transpose()?;
    Ok(json!({"matches":matches,"display":value.display_string().map_err(|e|e.to_string())?,"scannable":base64::engine::general_purpose::STANDARD.encode(value.scannable.serialize().map_err(|e|e.to_string())?)}))
}

/// QR payloads contain public identity/fingerprint data only, and cannot change a relay endpoint.
pub fn parse_code(value:String)->Result<Value,String>{
    use base64::Engine as _;
    let value=value.trim();if value.len()>1024{return Err("Contact code exceeds its limit".into())}
    let id=value.strip_prefix("msgat://contact/").unwrap_or(value);
    if id.len()==64 && id.bytes().all(|c|c.is_ascii_hexdigit() && !c.is_ascii_uppercase()) {return Ok(json!({"kind":"contact","id":id}))}
    if let Some(encoded)=value.strip_prefix("msgat://verify/") {
        let bytes=base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(encoded).map_err(|_|"Invalid safety QR code")?;
        if bytes.is_empty() || bytes.len()>512{return Err("Invalid safety QR code length".into())}
        libsignal_protocol::ScannableFingerprint::deserialize(&bytes).map_err(|e|e.to_string())?;
        return Ok(json!({"kind":"verify","scannable":base64::engine::general_purpose::STANDARD.encode(bytes)}))
    }
    Err("This is not a MsgAT contact or safety QR code".into())
}
