// SPDX-License-Identifier: AGPL-3.0-only
//! One-peer, transactional libsignal operations. The host must persist the returned
//! session delta with its message/outbox write before transmitting or acknowledging.
use crate::identity::{Certificate, SignedBundle, verify_bundle, verify_certificate};
use async_trait::async_trait;
use base64::{Engine as _, engine::general_purpose::STANDARD};
use libsignal_protocol::{self as signal, DeviceId, Direction, GenericSignedPreKey,
    IdentityChange, IdentityKey, IdentityKeyPair, IdentityKeyStore, KyberPreKeyId,
    KyberPreKeyRecord, KyberPreKeyStore, PreKeyId, PreKeyRecord, PreKeyStore,
    ProtocolAddress, PublicKey, SessionRecord, SessionStore, SignalProtocolError,
    SignedPreKeyId, SignedPreKeyRecord, SignedPreKeyStore};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::time::SystemTime;
use zeroize::Zeroizing;
use prost::Message as _;

type ProtocolResult<T> = Result<T, SignalProtocolError>;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LocalIdentity {
    pub uuid: String,
    pub registration_id: u32,
    pub identity_key_pair: String,
    pub certificate: Certificate,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PeerState {
    pub uuid: String,
    pub certificate: Certificate,
    pub session_record: Option<String>,
    pub trusted_identity_key: Option<String>,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct WireMessage {
    pub version: u32,
    pub recipient_uuid: String,
    pub sender_certificate: Certificate,
    pub message_type: u8,
    pub ciphertext: String,
}

#[derive(prost::Message)]
struct BinaryEnvelope {
    #[prost(uint32, tag="1")] version: u32,
    #[prost(string, tag="2")] recipient_uuid: String,
    #[prost(bytes="vec", tag="3")] root_public_key: Vec<u8>,
    #[prost(bytes="vec", tag="4")] identity_key: Vec<u8>,
    #[prost(bytes="vec", tag="5")] signature: Vec<u8>,
    #[prost(uint32, tag="6")] message_type: u32,
    #[prost(bytes="vec", tag="7")] ciphertext: Vec<u8>,
}

fn encode_wire(wire: &WireMessage) -> Result<Vec<u8>,String> {
    let envelope=BinaryEnvelope {
        version:wire.version,recipient_uuid:wire.recipient_uuid.clone(),
        root_public_key:decode(&wire.sender_certificate.root_public_key,32)?,
        identity_key:decode(&wire.sender_certificate.identity_key,64)?,
        signature:decode(&wire.sender_certificate.signature,64)?,message_type:wire.message_type.into(),
        ciphertext:decode(&wire.ciphertext,256*1024)?,
    }.encode_to_vec();
    if envelope.len()>262000 { return Err("Message exceeds the transport frame limit".into()); }
    Ok(envelope)
}

pub fn decode_wire(encoded: String, recipient: String) -> Result<Value,String> {
    let bytes=decode(&encoded,262000)?;
    let envelope=BinaryEnvelope::decode(bytes.as_slice()).map_err(|_|"Invalid binary message envelope")?;
    if envelope.version!=2 || envelope.recipient_uuid!=recipient || envelope.message_type>255 {
        return Err("Unsupported or misaddressed binary message envelope".into());
    }
    let sender=crate::identity::hex_uuid(&envelope.root_public_key);
    let wire=WireMessage {version:envelope.version,recipient_uuid:envelope.recipient_uuid,
        sender_certificate:Certificate {root_public_key:STANDARD.encode(envelope.root_public_key),
            identity_key:STANDARD.encode(envelope.identity_key),signature:STANDARD.encode(envelope.signature)},
        message_type:envelope.message_type as u8,ciphertext:STANDARD.encode(envelope.ciphertext)};
    let description=describe(wire.clone(),sender.clone(),recipient)?;
    Ok(json!({"sender_uuid":sender,"wire_message":wire,"prekeys":description}))
}

#[derive(Default, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct ReceiveKeys {
    pub prekey: Option<String>,
    pub signed_prekey: Option<String>,
    pub pq_prekey: Option<String>,
    pub pq_last_resort: bool,
    pub pq_already_used: bool,
}

struct IdentitySlot {
    local: IdentityKeyPair,
    registration_id: u32,
    remote: ProtocolAddress,
    trusted: IdentityKey,
}

#[async_trait(?Send)]
impl IdentityKeyStore for IdentitySlot {
    async fn get_identity_key_pair(&self) -> ProtocolResult<IdentityKeyPair> { Ok(self.local) }
    async fn get_local_registration_id(&self) -> ProtocolResult<u32> { Ok(self.registration_id) }
    async fn save_identity(&mut self, address: &ProtocolAddress, identity: &IdentityKey) -> ProtocolResult<IdentityChange> {
        if address != &self.remote || identity != &self.trusted {
            return Err(SignalProtocolError::UntrustedIdentity(address.clone()));
        }
        Ok(IdentityChange::NewOrUnchanged)
    }
    async fn is_trusted_identity(&self, address: &ProtocolAddress, identity: &IdentityKey, _: Direction) -> ProtocolResult<bool> {
        Ok(address == &self.remote && identity == &self.trusted)
    }
    async fn get_identity(&self, address: &ProtocolAddress) -> ProtocolResult<Option<IdentityKey>> {
        Ok((address == &self.remote).then_some(self.trusted))
    }
}

struct SessionSlot { address: ProtocolAddress, record: Option<SessionRecord> }

#[async_trait(?Send)]
impl SessionStore for SessionSlot {
    async fn load_session(&self, address: &ProtocolAddress) -> ProtocolResult<Option<SessionRecord>> {
        if address != &self.address { return Err(SignalProtocolError::InvalidArgument("Unexpected session address".into())); }
        Ok(self.record.clone())
    }
    async fn store_session(&mut self, address: &ProtocolAddress, record: &SessionRecord) -> ProtocolResult<()> {
        if address != &self.address { return Err(SignalProtocolError::InvalidArgument("Unexpected session address".into())); }
        self.record = Some(record.clone());
        Ok(())
    }
}

struct PreKeySlot { record: Option<PreKeyRecord>, consumed: Option<u32> }

#[async_trait(?Send)]
impl PreKeyStore for PreKeySlot {
    async fn get_pre_key(&self, id: PreKeyId) -> ProtocolResult<PreKeyRecord> {
        match &self.record {
            Some(record) if record.id()? == id => Ok(record.clone()),
            _ => Err(SignalProtocolError::InvalidPreKeyId),
        }
    }
    async fn save_pre_key(&mut self, id: PreKeyId, record: &PreKeyRecord) -> ProtocolResult<()> {
        if record.id()? != id { return Err(SignalProtocolError::InvalidPreKeyId); }
        self.record = Some(record.clone()); Ok(())
    }
    async fn remove_pre_key(&mut self, id: PreKeyId) -> ProtocolResult<()> {
        self.get_pre_key(id).await?;
        self.record = None; self.consumed = Some(id.into()); Ok(())
    }
}

struct SignedSlot { record: Option<SignedPreKeyRecord> }

#[async_trait(?Send)]
impl SignedPreKeyStore for SignedSlot {
    async fn get_signed_pre_key(&self, id: SignedPreKeyId) -> ProtocolResult<SignedPreKeyRecord> {
        match &self.record {
            Some(record) if record.id()? == id => Ok(record.clone()),
            _ => Err(SignalProtocolError::InvalidSignedPreKeyId),
        }
    }
    async fn save_signed_pre_key(&mut self, id: SignedPreKeyId, record: &SignedPreKeyRecord) -> ProtocolResult<()> {
        if record.id()? != id { return Err(SignalProtocolError::InvalidSignedPreKeyId); }
        self.record = Some(record.clone()); Ok(())
    }
}

#[derive(Serialize)]
struct KeyUse { pq_prekey_id: u32, signed_prekey_id: u32, base_key: String, fingerprint: String }

struct PqSlot {
    record: Option<KyberPreKeyRecord>, last_resort: bool, already_used: bool,
    consumed: bool, key_use: Option<KeyUse>,
}

#[async_trait(?Send)]
impl KyberPreKeyStore for PqSlot {
    async fn get_kyber_pre_key(&self, id: KyberPreKeyId) -> ProtocolResult<KyberPreKeyRecord> {
        match &self.record {
            Some(record) if record.id()? == id => Ok(record.clone()),
            _ => Err(SignalProtocolError::InvalidKyberPreKeyId),
        }
    }
    async fn save_kyber_pre_key(&mut self, id: KyberPreKeyId, record: &KyberPreKeyRecord) -> ProtocolResult<()> {
        if record.id()? != id { return Err(SignalProtocolError::InvalidKyberPreKeyId); }
        self.record = Some(record.clone()); Ok(())
    }
    async fn mark_kyber_pre_key_used(&mut self, id: KyberPreKeyId, ec: SignedPreKeyId, base: &PublicKey) -> ProtocolResult<()> {
        self.get_kyber_pre_key(id).await?;
        if self.already_used { return Err(SignalProtocolError::InvalidArgument("Repeated PQ prekey/base-key use".into())); }
        self.key_use = Some(key_use(id, ec, base));
        if !self.last_resort { self.record = None; self.consumed = true; }
        Ok(())
    }
}

fn key_use(pq: KyberPreKeyId, ec: SignedPreKeyId, base: &PublicKey) -> KeyUse {
    let pq: u32 = pq.into(); let ec: u32 = ec.into();
    let bytes = base.serialize();
    let mut hash = Sha256::new(); hash.update(pq.to_be_bytes()); hash.update(ec.to_be_bytes()); hash.update(&bytes);
    KeyUse { pq_prekey_id:pq, signed_prekey_id:ec, base_key:STANDARD.encode(bytes),
        fingerprint:hash.finalize().iter().map(|b|format!("{b:02x}")).collect() }
}

fn decode(value: &str, maximum: usize) -> Result<Vec<u8>,String> {
    if value.len()>maximum*2 { return Err("Encoded protocol record exceeds its size limit".into()); }
    let bytes=STANDARD.decode(value).map_err(|_| "Invalid protocol record encoding")?;
    if bytes.len()>maximum { return Err("Protocol record exceeds its size limit".into()); }
    Ok(bytes)
}

fn address(uuid: &str) -> Result<ProtocolAddress,String> {
    if uuid.len()!=64 || !uuid.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) {
        return Err("Invalid account id".into());
    }
    Ok(ProtocolAddress::new(uuid.into(), DeviceId::new(1).map_err(|_| "Invalid single-install address")?))
}

fn context(local: &LocalIdentity, peer: &PeerState) -> Result<(IdentitySlot,SessionSlot,ProtocolAddress),String> {
    if local.uuid==peer.uuid || !(1..=16380).contains(&local.registration_id) { return Err("Invalid session context".into()); }
    let local_address=address(&local.uuid)?; let remote=address(&peer.uuid)?;
    let pair_bytes=Zeroizing::new(decode(&local.identity_key_pair,256)?);
    let pair=IdentityKeyPair::try_from(pair_bytes.as_slice()).map_err(|e|e.to_string())?;
    if verify_certificate(&local.certificate,&local.uuid)? != *pair.identity_key() {
        return Err("Local protocol identity does not match its root certificate".into());
    }
    let trusted=verify_certificate(&peer.certificate,&peer.uuid)?;
    if let Some(pinned)=&peer.trusted_identity_key {
        if IdentityKey::decode(&decode(pinned,64)?).map_err(|e|e.to_string())?!=trusted {
            return Err("Peer protocol identity changed; explicit contact verification is required".into());
        }
    }
    let record=peer.session_record.as_ref().map(|s| decode(s,1024*1024)
        .and_then(|s|SessionRecord::deserialize(&s).map_err(|e|e.to_string()))).transpose()?;
    Ok((IdentitySlot {local:pair,registration_id:local.registration_id,remote:remote.clone(),trusted},
        SessionSlot {address:remote,record},local_address))
}

fn save_peer(mut peer: PeerState, sessions: &SessionSlot, identity: &IdentitySlot) -> Result<PeerState,String> {
    let record=sessions.record.as_ref().ok_or("No session was created")?;
    peer.session_record=Some(STANDARD.encode(record.serialize().map_err(|e|e.to_string())?));
    peer.trusted_identity_key=Some(STANDARD.encode(identity.trusted.serialize()));
    Ok(peer)
}

pub fn encrypt(local: LocalIdentity, peer: PeerState, bundle: Option<SignedBundle>, selected_prekey: Option<u32>, plaintext: String) -> Result<Value,String> {
    let (mut identities,mut sessions,local_address)=context(&local,&peer)?;
    if sessions.record.is_none() {
        let bundle=bundle.ok_or("A verified prekey bundle is required for a new session")?;
        let bundle=verify_bundle(&bundle,&peer.uuid)?;
        let remote_identity=verify_certificate(&bundle.certificate,&peer.uuid)?;
        if remote_identity!=identities.trusted { return Err("Directory key does not match peer certificate".into()); }
        let prekey=match selected_prekey {
            Some(id)=>Some(bundle.prekeys.iter().find(|k|k.id==id).ok_or("Selected prekey is absent from the signed bundle")?),
            None=>None,
        };
        let prekey=prekey.map(|k| decode(&k.public_key,64).and_then(|bytes|PublicKey::deserialize(&bytes).map_err(|e|e.to_string())).map(|key|(k.id.into(),key))).transpose()?;
        let prekey_bundle=signal::PreKeyBundle::new(bundle.registration_id,DeviceId::new(1).map_err(|_|"Invalid address")?,prekey,
            bundle.signed_prekey_id.into(),PublicKey::deserialize(&decode(&bundle.signed_prekey,64)?).map_err(|e|e.to_string())?,
            decode(&bundle.signed_prekey_signature,64)?,bundle.pq_prekey_id.into(),
            signal::kem::PublicKey::deserialize(&decode(&bundle.pq_prekey,4096)?).map_err(|e|e.to_string())?,
            decode(&bundle.pq_prekey_signature,64)?,remote_identity).map_err(|e|e.to_string())?;
        futures_executor::block_on(signal::initiate_session(&sessions.address.clone(),&local_address,&mut sessions,&mut identities,
            &prekey_bundle,SystemTime::now(),&mut rand::rng())).map_err(|e|e.to_string())?;
    }
    let plaintext=Zeroizing::new(decode(&plaintext,64*1024)?);
    let message=futures_executor::block_on(signal::message_encrypt(&plaintext,&sessions.address.clone(),&local_address,
        &mut sessions,&mut identities,SystemTime::now(),&mut rand::rng())).map_err(|e|e.to_string())?;
    let wire=WireMessage {version:2,recipient_uuid:peer.uuid.clone(),sender_certificate:local.certificate,
        message_type:message.message_type() as u8,ciphertext:STANDARD.encode(message.serialize())};
    let bytes=encode_wire(&wire)?;
    Ok(json!({"wire_bytes":STANDARD.encode(bytes),"peer_state":save_peer(peer,&sessions,&identities)?}))
}

fn parse_wire(wire: &WireMessage) -> Result<signal::CiphertextMessage,String> {
    if wire.version!=2 { return Err("Unsupported message envelope version".into()); }
    let bytes=decode(&wire.ciphertext,512*1024)?;
    if bytes.first().map(|b|b>>4)!=Some(4) { return Err("Unsupported or legacy Signal message version".into()); }
    match wire.message_type {
        2=>Ok(signal::CiphertextMessage::SignalMessage(signal::SignalMessage::try_from(bytes.as_slice()).map_err(|e|e.to_string())?)),
        3=>Ok(signal::CiphertextMessage::PreKeySignalMessage(signal::PreKeySignalMessage::try_from(bytes.as_slice()).map_err(|e|e.to_string())?)),
        _=>Err("Unsupported single-chat ciphertext type".into()),
    }
}

pub fn describe(wire: WireMessage, expected_sender: String, expected_recipient: String) -> Result<Value,String> {
    if wire.recipient_uuid!=expected_recipient { return Err("Ciphertext is addressed to another identity".into()); }
    let sender=verify_certificate(&wire.sender_certificate,&expected_sender)?;
    let message=parse_wire(&wire)?;
    match message {
        signal::CiphertextMessage::PreKeySignalMessage(message)=>{
            if message.identity_key()!=&sender { return Err("Ciphertext identity does not match root certificate".into()); }
            let pq=message.kyber_pre_key_id().ok_or("Post-quantum prekey is required")?;
            Ok(json!({"prekey_id":message.pre_key_id().map(u32::from),"signed_prekey_id":u32::from(message.signed_pre_key_id()),
                "pq_prekey_id":u32::from(pq),"pq_key_use":key_use(pq,message.signed_pre_key_id(),message.base_key())}))
        }
        _=>Ok(json!({})),
    }
}

pub fn decrypt(local: LocalIdentity, peer: PeerState, wire: WireMessage, keys: ReceiveKeys) -> Result<Value,String> {
    if wire.recipient_uuid!=local.uuid { return Err("Ciphertext is addressed to another identity".into()); }
    let (mut identities,mut sessions,local_address)=context(&local,&peer)?;
    if verify_certificate(&wire.sender_certificate,&peer.uuid)?!=identities.trusted { return Err("Untrusted sender certificate".into()); }
    let message=parse_wire(&wire)?;
    if let signal::CiphertextMessage::PreKeySignalMessage(message)=&message {
        if message.kyber_pre_key_id().is_none() || message.identity_key()!=&identities.trusted { return Err("Invalid authenticated prekey message".into()); }
    }
    let mut prekeys=PreKeySlot {record:keys.prekey.as_ref().map(|s|decode(s,1024).and_then(|s|PreKeyRecord::deserialize(&s).map_err(|e|e.to_string()))).transpose()?,consumed:None};
    let signed=SignedSlot {record:keys.signed_prekey.as_ref().map(|s|decode(s,2048).and_then(|s|SignedPreKeyRecord::deserialize(&s).map_err(|e|e.to_string()))).transpose()?};
    let mut pq=PqSlot {record:keys.pq_prekey.as_ref().map(|s|decode(s,16384).and_then(|s|KyberPreKeyRecord::deserialize(&s).map_err(|e|e.to_string()))).transpose()?,
        last_resort:keys.pq_last_resort,already_used:keys.pq_already_used,consumed:false,key_use:None};
    let plaintext=Zeroizing::new(futures_executor::block_on(signal::message_decrypt(&message,&sessions.address.clone(),&local_address,
        &mut sessions,&mut identities,&mut prekeys,&signed,&mut pq,&mut rand::rng())).map_err(|e|e.to_string())?);
    Ok(json!({"plaintext":STANDARD.encode(plaintext.as_slice()),"peer_state":save_peer(peer,&sessions,&identities)?,
        "consumed_prekey_id":prekeys.consumed,"consumed_pq_prekey":pq.consumed,"pq_key_use":pq.key_use}))
}
