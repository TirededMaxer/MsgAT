// SPDX-License-Identifier: AGPL-3.0-only
//! Process-local preparation counters. No names, paths, manifests or keys are stored.
//! Hosts close their handle only after the worker and staged-object cleanup finish.
use rand::Rng;
use serde_json::{Value,json};
use std::{collections::HashMap,sync::{Arc,Mutex,OnceLock,atomic::{AtomicBool,AtomicU64,Ordering}}};

pub struct Work {cancelled:AtomicBool,completed:AtomicU64,total:AtomicU64}
static WORK:OnceLock<Mutex<HashMap<String,Arc<Work>>>>=OnceLock::new();
fn registry()->&'static Mutex<HashMap<String,Arc<Work>>>{WORK.get_or_init(||Mutex::new(HashMap::new()))}
pub fn create()->Result<Value,String>{
    let mut jobs=registry().lock().map_err(|_|"Preparation state is unavailable")?;
    if jobs.len()>=16 {return Err("Too many active preparations".into());}
    let id=loop {
        let mut bytes=[0u8;32];rand::rng().fill(&mut bytes);
        let id=bytes.iter().map(|b|format!("{b:02x}")).collect::<String>();
        if !jobs.contains_key(&id){break id}
    };
    jobs.insert(id.clone(),Arc::new(Work{cancelled:AtomicBool::new(false),completed:AtomicU64::new(0),total:AtomicU64::new(0)}));
    Ok(json!({"work_id":id}))
}
pub fn resolve(id:Option<&str>)->Result<Option<Arc<Work>>,String>{
    let Some(id)=id else{return Ok(None)};
    if id.len()!=64 || !id.bytes().all(|b|b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) {return Err("Invalid preparation handle".into());}
    registry().lock().map_err(|_|"Preparation state is unavailable")?.get(id).cloned().map(Some).ok_or_else(||"Preparation has ended".into())
}
impl Work {
    pub fn check(&self)->Result<(),String>{if self.cancelled.load(Ordering::Acquire){Err("Preparation cancelled".into())}else{Ok(())}}
    pub fn report(&self,total:u64,completed:u64)->Result<(),String>{
        self.check()?;
        if total>super::objects::MAX_SIZE || completed>total {return Err("Invalid preparation progress".into());}
        self.total.store(total,Ordering::Release);self.completed.store(completed,Ordering::Release);Ok(())
    }
}
pub fn status(id:&str)->Result<Value,String>{
    let work=resolve(Some(id))?.ok_or("Preparation has ended")?;
    let total=work.total.load(Ordering::Acquire);
    Ok(json!({"cancelled":work.cancelled.load(Ordering::Acquire),"total":total,"completed":work.completed.load(Ordering::Acquire).min(total)}))
}
pub fn cancel(id:&str)->Result<Value,String>{
    let work=resolve(Some(id))?.ok_or("Preparation has ended")?;work.cancelled.store(true,Ordering::Release);Ok(json!({"cancelled":true}))
}
pub fn report(id:&str,total:u64,completed:u64)->Result<Value,String>{
    resolve(Some(id))?.ok_or("Preparation has ended")?.report(total,completed)?;Ok(json!({"reported":true}))
}
pub fn close(id:&str)->Result<Value,String>{
    let removed=registry().lock().map_err(|_|"Preparation state is unavailable")?.remove(id);
    // A worker retaining an Arc also stops if its owner closes unexpectedly.
    if let Some(work)=removed {work.cancelled.store(true,Ordering::Release);}
    Ok(json!({"closed":true}))
}
