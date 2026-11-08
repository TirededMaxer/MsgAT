// SPDX-License-Identifier: AGPL-3.0-only
use base64::{Engine as _, engine::general_purpose::STANDARD};
use ed25519_dalek::{Signature, Signer, SigningKey, VerifyingKey};
use hkdf::Hkdf;
use libsignal_protocol::{GenericSignedPreKey, IdentityKey, IdentityKeyPair, KeyPair,
    KyberPreKeyRecord, PreKeyRecord, PrivateKey, SignedPreKeyRecord, Timestamp, kem};
use rand::Rng;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::time::{SystemTime, UNIX_EPOCH};
use zeroize::Zeroize;

const BUNDLE_DOMAIN: &[u8] = b"MsgAT/v2/signal-bundle\0";
const CERTIFICATE_DOMAIN: &[u8] = b"MsgAT/v2/protocol-identity\0";

#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Certificate {
    pub root_public_key: String,
    pub identity_key: String,
    pub signature: String,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SignedBundle {
    pub root_public_key: String,
    pub body: String,
    pub signature: String,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PublicBundle {
    pub version: u32,
    pub expires_at: u64,
    pub registration_id: u32,
    pub certificate: Certificate,
    pub signed_prekey_id: u32,
    pub signed_prekey: String,
    pub signed_prekey_signature: String,
    pub pq_prekey_id: u32,
    pub pq_prekey: String,
    pub pq_prekey_signature: String,
    pub prekeys: Vec<PublicPreKey>,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PublicPreKey { pub id: u32, pub public_key: String }

fn epoch_seconds() -> Result<u64, String> {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|t| t.as_secs()).map_err(|_| "Invalid system clock".into())
}

pub fn provision(seed: &[u8], count: u32) -> Result<Value, String> {
    generate(seed, count, None, 1, 1, 1)
}

pub fn refresh(seed: &[u8], local: &crate::session::LocalIdentity, count: u32,
    first_prekey_id: u32, signed_prekey_id: u32, pq_prekey_id: u32) -> Result<Value, String> {
    generate(seed, count, Some(local), first_prekey_id, signed_prekey_id, pq_prekey_id)
}

fn generate(seed: &[u8], count: u32, local: Option<&crate::session::LocalIdentity>,
    first_prekey_id: u32, signed_prekey_id: u32, pq_prekey_id: u32) -> Result<Value, String> {
    let seed: &[u8;32] = seed.try_into().map_err(|_| "Invalid root seed")?;
    if !(1..=100).contains(&count) { return Err("Prekey count must be between 1 and 100".into()); }
    if first_prekey_id==0 || signed_prekey_id==0 || pq_prekey_id==0 { return Err("Prekey identifiers must be positive".into()); }
    let last_prekey_id=first_prekey_id.checked_add(count-1).ok_or("Prekey identifier space is exhausted")?;
    let root = SigningKey::from_bytes(seed);
    let uuid = hex_uuid(root.verifying_key().as_bytes());
    let hkdf = Hkdf::<Sha256>::new(Some(b"MsgAT/v2/root-key-hierarchy"), seed);
    let mut protocol_seed = [0u8;32];
    hkdf.expand(b"protocol/identity", &mut protocol_seed).map_err(|_| "Key derivation failed")?;
    let private = PrivateKey::deserialize(&protocol_seed).map_err(|e| e.to_string());
    protocol_seed.zeroize();
    let private = private?;
    let identity = IdentityKeyPair::new(IdentityKey::new(private.public_key().map_err(|e|e.to_string())?), private);
    let mut certificate_bytes = CERTIFICATE_DOMAIN.to_vec();
    certificate_bytes.extend_from_slice(&identity.identity_key().serialize());
    let certificate = Certificate {
        root_public_key: STANDARD.encode(root.verifying_key().as_bytes()),
        identity_key: STANDARD.encode(identity.identity_key().serialize()),
        signature: STANDARD.encode(root.sign(&certificate_bytes).to_bytes()),
    };
    let mut rng = rand::rng();
    let registration_id = if let Some(local)=local {
        if local.uuid!=uuid || !(1..=16380).contains(&local.registration_id) ||
            verify_certificate(&local.certificate,&uuid)? != *identity.identity_key() {
            return Err("Prekey refresh cannot replace the local identity".into());
        }
        let mut existing=STANDARD.decode(&local.identity_key_pair).map_err(|_| "Invalid stored protocol identity")?;
        let matches=existing.as_slice()==identity.serialize().as_ref();
        existing.zeroize();
        if !matches { return Err("Stored protocol identity does not match the root seed".into()); }
        local.registration_id
    } else { rng.random_range(1..=16380u32) };
    let now = epoch_seconds()?;
    let signed_pair = KeyPair::generate(&mut rng);
    let signed_signature = identity.private_key().calculate_signature(&signed_pair.public_key.serialize(), &mut rng).map_err(|e|e.to_string())?;
    let signed = SignedPreKeyRecord::new(signed_prekey_id.into(), Timestamp::from_epoch_millis(now * 1000), &signed_pair, &signed_signature);
    let pq = KyberPreKeyRecord::generate(kem::KeyType::MLKEM1024, pq_prekey_id.into(), identity.private_key()).map_err(|e|e.to_string())?;
    let mut public_prekeys = Vec::with_capacity(count as usize);
    let mut records = Vec::with_capacity(count as usize);
    for id in first_prekey_id..=last_prekey_id {
        let pair = KeyPair::generate(&mut rng);
        public_prekeys.push(PublicPreKey { id, public_key: STANDARD.encode(pair.public_key.serialize()) });
        records.push(json!({"id":id,"record":STANDARD.encode(PreKeyRecord::new(id.into(),&pair).serialize().map_err(|e|e.to_string())?)}));
    }
    let body = PublicBundle {
        version:2, expires_at:now+7*24*3600, registration_id,
        certificate:certificate.clone(),
        signed_prekey_id, signed_prekey:STANDARD.encode(signed_pair.public_key.serialize()),
        signed_prekey_signature:STANDARD.encode(signed_signature),
        pq_prekey_id, pq_prekey:STANDARD.encode(pq.public_key().map_err(|e|e.to_string())?.serialize()),
        pq_prekey_signature:STANDARD.encode(pq.signature().map_err(|e|e.to_string())?), prekeys:public_prekeys,
    };
    let body = serde_json::to_vec(&body).map_err(|_| "Bundle encoding failed")?;
    let mut signed_bytes = BUNDLE_DOMAIN.to_vec();
    signed_bytes.extend_from_slice(&body);
    let bundle = SignedBundle {
        root_public_key:STANDARD.encode(root.verifying_key().as_bytes()),
        signature:STANDARD.encode(root.sign(&signed_bytes).to_bytes()), body:STANDARD.encode(body),
    };
    Ok(json!({"uuid":uuid,"public_bundle":bundle,"private_state":{
        "version":2,"local_identity":{
            "uuid":uuid,"registration_id":registration_id,
            "identity_key_pair":STANDARD.encode(identity.serialize()),"certificate":certificate
        },
        "signed_prekeys":[{"id":signed_prekey_id,"record":STANDARD.encode(signed.serialize().map_err(|e|e.to_string())?)}],
        "pq_prekeys":[{"id":pq_prekey_id,"record":STANDARD.encode(pq.serialize().map_err(|e|e.to_string())?),"last_resort":true}],
        "prekeys":records
    }}))
}

pub fn verify_bundle(bundle: &SignedBundle, expected_uuid: &str) -> Result<PublicBundle,String> {
    if bundle.body.len()>128*1024 { return Err("Bundle is too large".into()); }
    let public = STANDARD.decode(&bundle.root_public_key).map_err(|_| "Invalid root public key")?;
    let public: [u8;32] = public.try_into().map_err(|_| "Invalid root public key")?;
    if hex_uuid(&public)!=expected_uuid { return Err("Contact identity does not match".into()); }
    let root = VerifyingKey::from_bytes(&public).map_err(|_| "Invalid root public key")?;
    let body = STANDARD.decode(&bundle.body).map_err(|_| "Invalid bundle encoding")?;
    let signature = STANDARD.decode(&bundle.signature).map_err(|_| "Invalid signature encoding")?;
    let signature = Signature::from_slice(&signature).map_err(|_| "Invalid root signature")?;
    let mut signed_bytes = BUNDLE_DOMAIN.to_vec();
    signed_bytes.extend_from_slice(&body);
    root.verify_strict(&signed_bytes,&signature).map_err(|_| "Invalid bundle signature")?;
    let body: PublicBundle = serde_json::from_slice(&body).map_err(|_| "Invalid bundle body")?;
    let now=epoch_seconds()?;
    if body.version!=2 || body.expires_at<=now || body.expires_at>now+8*24*3600 || body.prekeys.len()>100
        || !(1..=16380).contains(&body.registration_id) || body.signed_prekey_id==0 || body.pq_prekey_id==0 {
        return Err("Expired or unsupported bundle".into());
    }
    if body.certificate.root_public_key != bundle.root_public_key { return Err("Bundle root does not match certificate".into()); }
    let identity = verify_certificate(&body.certificate, expected_uuid)?;
    let signed = STANDARD.decode(&body.signed_prekey).map_err(|_| "Invalid signed prekey")?;
    libsignal_protocol::PublicKey::deserialize(&signed).map_err(|e|e.to_string())?;
    let signed_signature = STANDARD.decode(&body.signed_prekey_signature).map_err(|_| "Invalid signed prekey signature")?;
    let pq = STANDARD.decode(&body.pq_prekey).map_err(|_| "Invalid PQ prekey")?;
    kem::PublicKey::deserialize(&pq).map_err(|e|e.to_string())?;
    let pq_signature = STANDARD.decode(&body.pq_prekey_signature).map_err(|_| "Invalid PQ prekey signature")?;
    if !identity.public_key().verify_signature(&signed, &signed_signature) ||
        !identity.public_key().verify_signature(&pq, &pq_signature) {
        return Err("Invalid protocol prekey signature".into());
    }
    let mut ids = std::collections::BTreeSet::new();
    for prekey in &body.prekeys {
        if prekey.id==0 || !ids.insert(prekey.id) { return Err("Invalid or duplicate prekey id".into()); }
        libsignal_protocol::PublicKey::deserialize(&STANDARD.decode(&prekey.public_key).map_err(|_| "Invalid prekey encoding")?).map_err(|e|e.to_string())?;
    }
    Ok(body)
}

pub fn verify_certificate(certificate: &Certificate, expected_uuid: &str) -> Result<IdentityKey,String> {
    let root = STANDARD.decode(&certificate.root_public_key).map_err(|_| "Invalid root public key")?;
    let root: [u8;32] = root.try_into().map_err(|_| "Invalid root public key")?;
    if hex_uuid(&root)!=expected_uuid { return Err("Contact root identity does not match".into()); }
    let identity = STANDARD.decode(&certificate.identity_key).map_err(|_| "Invalid protocol identity")?;
    let decoded = IdentityKey::decode(&identity).map_err(|e|e.to_string())?;
    let signature = STANDARD.decode(&certificate.signature).map_err(|_| "Invalid identity signature")?;
    let signature = Signature::from_slice(&signature).map_err(|_| "Invalid identity signature")?;
    let mut signed = CERTIFICATE_DOMAIN.to_vec(); signed.extend_from_slice(&identity);
    VerifyingKey::from_bytes(&root).map_err(|_| "Invalid root key")?
        .verify_strict(&signed,&signature).map_err(|_| "Invalid root binding signature")?;
    Ok(decoded)
}

pub fn hex_uuid(public: &[u8]) -> String {
    Sha256::digest(public).iter().map(|b|format!("{b:02x}")).collect()
}
