// SPDX-License-Identifier: AGPL-3.0-only
//! A live location retains one point and is bound to its original message and lifetime.
use crate::events::uuid_v4;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

const MAX_DATE: u64 = 253_402_214_399_999;
const MAX_LIFETIME: u64 = 8 * 60 * 60 * 1000;
const CLOCK_SKEW: u64 = 2 * 60 * 1000;
const MAX_REVISION: u32 = 4096;

#[derive(Clone, Copy, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
struct Point {
    latitude_e6: i32,
    longitude_e6: i32,
}

impl Point {
    fn valid(self) -> bool {
        (-90_000_000..=90_000_000).contains(&self.latitude_e6)
            && (-180_000_000..=180_000_000).contains(&self.longitude_e6)
    }

    fn parse(body: &Value) -> Result<Self, String> {
        let coordinate = |key: &str| -> Result<i32, String> {
            body[key].as_i64().and_then(|value| i32::try_from(value).ok())
                .ok_or_else(|| "Invalid location coordinate".into())
        };
        let point = Self { latitude_e6: coordinate("latitude_e6")?, longitude_e6: coordinate("longitude_e6")? };
        if point.valid() { Ok(point) } else { Err("Invalid location coordinates".into()) }
    }
}

pub fn validate_point(body: &Value) -> Result<(), String> {
    Point::parse(body).map(|_| ())
}

fn valid_lifetime(started_at: u64, expires_at: u64) -> bool {
    started_at < expires_at && expires_at <= MAX_DATE && expires_at - started_at <= MAX_LIFETIME
}

#[derive(Clone, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct State {
    started_at: u64,
    expires_at: u64,
    revision: u32,
    anchored: bool,
    ended: bool,
    point: Option<Point>,
}

impl State {
    fn valid(&self) -> bool {
        valid_lifetime(self.started_at, self.expires_at)
            && self.revision <= MAX_REVISION
            && (self.anchored || self.revision > 0)
            && self.point.is_none_or(Point::valid)
            && (self.point.is_some() || (self.ended && !self.anchored))
    }

    fn matches(&self, change: &Change) -> bool {
        self.started_at == change.started_at && self.expires_at == change.expires_at
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Operation { Start, Update, End }

struct Change {
    operation: Operation,
    started_at: u64,
    expires_at: u64,
    revision: u32,
    point: Option<Point>,
}

impl Change {
    fn parse(kind: &str, event_id: &str, body: &Value, created_at: u64) -> Result<Self, String> {
        if !uuid_v4(event_id) || created_at > MAX_DATE { return Err("Invalid live location event".into()); }
        let (operation, started_at, revision) = if kind == "chat.location.start" {
            (Operation::Start, created_at, 0)
        } else {
            let operation = match kind {
                "chat.location.update" => Operation::Update,
                "chat.location.end" => Operation::End,
                _ => return Err("Invalid live location operation".into()),
            };
            let target = body["message_id"].as_str().ok_or("Invalid live location message")?;
            if target == event_id || !uuid_v4(target) { return Err("Invalid live location message".into()); }
            let started_at = body["started_at"].as_u64().ok_or("Invalid live location start time")?;
            let revision = body["revision"].as_u64().filter(|value| (1..=u64::from(MAX_REVISION)).contains(value))
                .ok_or("Invalid live location revision")? as u32;
            (operation, started_at, revision)
        };
        let expires_at = body["expires_at"].as_u64().ok_or("Invalid live location expiry")?;
        if !valid_lifetime(started_at, expires_at) || created_at < started_at || created_at >= expires_at {
            return Err("Invalid live location lifetime".into());
        }
        let point = if operation == Operation::End { None } else { Some(Point::parse(body)?) };
        Ok(Self { operation, started_at, expires_at, revision, point })
    }
}

pub fn validate(kind: &str, event_id: &str, body: &Value, created_at: u64) -> Result<(), String> {
    Change::parse(kind, event_id, body, created_at).map(|_| ())
}

pub fn transition(current: Option<State>, kind: &str, event_id: &str, body: &Value,
                  created_at: u64, now: u64) -> Result<Value, String> {
    if now > MAX_DATE || current.as_ref().is_some_and(|state| !state.valid()) {
        return Err("Invalid stored live location state or clock".into());
    }
    let change = Change::parse(kind, event_id, body, created_at)?;
    let within_lifetime = created_at <= now.saturating_add(CLOCK_SKEW) && now < change.expires_at;
    let mut next = current.clone();
    match change.operation {
        Operation::Start => {
            if !next.as_ref().is_some_and(|state| state.anchored) {
                let pending = next.filter(|state| state.matches(&change));
                next = Some(State {
                    started_at: change.started_at,
                    expires_at: change.expires_at,
                    revision: pending.as_ref().map_or(0, |state| state.revision),
                    anchored: true,
                    ended: !within_lifetime || pending.as_ref().is_some_and(|state| state.ended),
                    point: pending.and_then(|state| state.point).or(change.point),
                });
            }
        }
        Operation::Update | Operation::End if within_lifetime => {
            let end = change.operation == Operation::End;
            match next.as_mut() {
                Some(state) if state.matches(&change) && !state.ended && (end || change.revision > state.revision) => {
                    state.revision = state.revision.max(change.revision);
                    if end { state.ended = true; }
                    else { state.point = change.point; }
                }
                None => {
                    next = Some(State {
                        started_at: change.started_at,
                        expires_at: change.expires_at,
                        revision: change.revision,
                        anchored: false,
                        ended: end,
                        point: change.point,
                    });
                }
                _ => {}
            }
        }
        _ => {}
    }
    Ok(json!({"changed": next != current, "state": next}))
}
