// SPDX-License-Identifier: AGPL-3.0-only
//! Encrypted advisory activity, coalesced behind one business-acknowledged event.
use serde::{Deserialize,Serialize};
use serde_json::{Value,json};
use crate::events::uuid_v4;
const MAX_DATE:u64=253_402_214_399_999;
#[derive(Clone,Deserialize,Serialize)]
#[serde(deny_unknown_fields)]
pub struct Payload {pub counter:u32,pub online:Option<bool>,pub typing:bool,pub request:bool,pub expires_at:u64}
impl Payload {
    pub fn validate(&self,created:u64)->Result<(),String>{
        if self.counter==0 || created>MAX_DATE || self.expires_at<=created || self.expires_at>created.saturating_add(30_000) || self.expires_at>MAX_DATE {
            return Err("Invalid activity lifetime or counter".into());
        }
        Ok(())
    }
}
#[derive(Clone,Deserialize,Serialize)]
#[serde(deny_unknown_fields)]
pub struct Sent {pub event_id:String,pub created_at:u64,pub retry_at:u64,pub body:Payload}
#[derive(Default,Deserialize,Serialize)]
#[serde(default,deny_unknown_fields)]
pub struct State {
    pub sent_counter:u32,pub received_counter:u32,pub received_id:Option<String>,
    pub inflight:Option<Sent>,pub published:Option<Sent>,
}
#[derive(Deserialize)]
#[serde(tag="kind",rename_all="snake_case",deny_unknown_fields)]
pub enum Action {
    Publish {event_id:String,online:Option<bool>,typing:bool,request:bool,now:u64},
    Receive {event_id:String,body:Payload,created_at:u64,now:u64},
    Acknowledge {event_id:String},
    Unprepare,
    Retried {now:u64},
    Clear,
}
pub fn transition(mut state:State,action:Action)->Result<Value,String>{
    if state.received_id.as_ref().is_some_and(|id|!uuid_v4(id)) {return Err("Invalid received activity identifier".into())}
    for sent in [&state.inflight,&state.published].into_iter().flatten(){
        if !uuid_v4(&sent.event_id) || sent.body.counter>state.sent_counter {return Err("Invalid stored activity".into())}
        sent.body.validate(sent.created_at)?;
    }
    let mut send=None;let mut observation=None;let mut replaced=None;let mut acknowledged=None;
    match action {
        Action::Publish {event_id,online,typing,request,now} => {
            if !uuid_v4(&event_id) || now>MAX_DATE-30_000 {return Err("Invalid activity publication".into())}
            if state.inflight.is_none(){
                let changed=state.published.as_ref().is_some_and(|old|old.body.online!=online || old.body.typing!=typing || old.body.request!=request);
                let meaningful=online.is_some() || typing || request;
                let interval=if typing {4_000}else{12_000};
                let heartbeat=state.published.as_ref().is_none_or(|old|now<old.created_at || now-old.created_at>=interval);
                if changed || (meaningful && heartbeat){
                    let counter=state.sent_counter.checked_add(1).ok_or("Activity counter exhausted")?;
                    let event=Sent{event_id,created_at:now,retry_at:now.saturating_add(30_000),body:Payload{counter,online,typing,request,expires_at:now+30_000}};
                    state.sent_counter=counter;send=Some(event.clone());state.inflight=Some(event.clone());state.published=Some(event);
                }
            }
        }
        Action::Receive {event_id,body,created_at,now} => {
            if !uuid_v4(&event_id) || now>MAX_DATE {return Err("Invalid activity receipt".into())}
            body.validate(created_at)?;
            if body.counter>state.received_counter {
                replaced=state.received_id.replace(event_id);state.received_counter=body.counter;
                let valid=created_at<=now.saturating_add(5_000) && body.expires_at>now;
                let expires=if valid {(body.expires_at-now).min(30_000)}else{0};
                let typing_expires=if valid && body.typing {created_at.saturating_add(8_000).saturating_sub(now).min(expires).min(8_000)}else{0};
                observation=Some(json!({"online":if valid {body.online}else{None},"typing":typing_expires>0,"valid_for":expires,"typing_for":typing_expires,"request":valid && body.request}));
            }
        }
        Action::Acknowledge {event_id} => {
            if !uuid_v4(&event_id){return Err("Invalid activity acknowledgement".into())}
            if state.inflight.as_ref().is_some_and(|event|event.event_id==event_id){acknowledged=state.inflight.take().map(|event|event.event_id);}
        }
        Action::Unprepare=>{state.inflight=None;state.published=None;}
        Action::Retried {now}=>{if let Some(event)=&mut state.inflight {event.retry_at=now.saturating_add(30_000);}}
        Action::Clear=>{state.inflight=None;state.published=None;}
    }
    Ok(json!({"state":state,"send":send,"observation":observation,"replaced":replaced,"acknowledged":acknowledged}))
}
