// SPDX-License-Identifier: AGPL-3.0-only
//! Passive metadata only. Hosts own bounded HTTP I/O; this module never opens a socket.
use serde_json::{Value,json};
use std::net::IpAddr;

pub struct WebTarget {pub host:String,pub port:u16,pub secure:bool}
pub fn target(url:&str)->Result<WebTarget,String> {
    if url.len()>4096 || !url.is_ascii() || url.bytes().any(|b|b<=32 || b==127 || b==b'\\') {return Err("Unsupported preview URL".into())}
    let (secure,rest)=if url.get(..8).is_some_and(|v|v.eq_ignore_ascii_case("https://")) {(true,&url[8..])} else if url.get(..7).is_some_and(|v|v.eq_ignore_ascii_case("http://")) {(false,&url[7..])} else {return Err("Unsupported preview scheme".into())};
    let authority=rest.split(['/', '?', '#']).next().unwrap_or("");
    if authority.is_empty() || authority.contains(['@','%']) {return Err("Invalid preview host".into())}
    let (host,port)=if let Some(rest)=authority.strip_prefix('[') {
        let end=rest.find(']').ok_or("Invalid preview host")?;
        let host=&rest[..end];host.parse::<std::net::Ipv6Addr>().map_err(|_|"Invalid preview host")?;
        (host,if rest.len()==end+1 {None} else {Some(rest[end+1..].strip_prefix(':').ok_or("Invalid preview port")?)})
    } else {
        let (host,port)=authority.split_once(':').map_or((authority,None),|(h,p)|(h,Some(p)));
        if host.len()>253 || host.ends_with('.') || !host.split('.').all(|s|!s.is_empty() && s.len()<=63 && !s.starts_with('-') && !s.ends_with('-') && s.bytes().all(|b|b.is_ascii_alphanumeric() || b==b'-')) {return Err("Invalid preview host".into())}
        (host,port)
    };
    let port=match port {Some(p) if !p.is_empty() && p.bytes().all(|b|b.is_ascii_digit())=>p.parse::<u16>().map_err(|_|"Invalid preview port")?,None=>if secure {443}else{80},_=>return Err("Invalid preview port".into())};
    if port==0 {return Err("Invalid preview port".into())}
    let host=host.to_ascii_lowercase();
    if !host.contains('.') && !host.contains(':') || host=="localhost" || [".localhost",".local",".internal",".home",".lan",".onion"].iter().any(|s|host.ends_with(s)) {return Err("Local preview destination is forbidden".into())}
    if let Ok(ip)=host.parse::<IpAddr>() {if !public_ip(ip) {return Err("Local preview destination is forbidden".into())}}
    Ok(WebTarget{host,port,secure})
}
fn public_ip(ip:IpAddr)->bool {
    match ip {
        IpAddr::V4(ip)=>{
            let n=u32::from(ip);
            ![(0x00000000,8),(0x0a000000,8),(0x64400000,10),(0x7f000000,8),(0xa9fe0000,16),(0xac100000,12),(0xc0000000,24),(0xc0000200,24),(0xc0586300,24),(0xc0a80000,16),(0xc6120000,15),(0xc6336400,24),(0xcb007100,24),(0xe0000000,3)].iter().any(|&(base,bits)|n>>(32-bits)==base>>(32-bits))
        }
        IpAddr::V6(ip)=>{
            let s=ip.segments();
            s[0]&0xe000==0x2000 && !(s[0]==0x2001 && (s[1]<0x0200 || s[1]==0x0db8)) && s[0]!=0x2002 && !(s[0]==0x3fff && s[1]<0x1000)
        }
    }
}
pub fn policy(url:String,addresses:Vec<String>)->Result<Value,String> {
    let t=target(&url)?;
    if addresses.is_empty() || addresses.len()>64 {return Err("Preview destination has no usable address".into())}
    for address in &addresses {if !public_ip(address.parse().map_err(|_|"Invalid preview address")?) {return Err("Local preview destination is forbidden".into())}}
    Ok(json!({"url":url,"host":t.host,"port":t.port,"secure":t.secure,"addresses":addresses}))
}
pub fn candidate(text:String)->Result<Value,String> {
    Ok(json!({"url":crate::rich_text::preview_candidate(&text)?}))
}

fn clean(value:&str,limit:usize)->String {
    let decoded=entities(value);
    let mut out=String::new();
    for word in decoded.split_whitespace() {
        if !out.is_empty() {out.push(' ')}
        for c in word.chars().filter(|c|!c.is_control() && !matches!(*c,'\u{202a}'..='\u{202e}'|'\u{2066}'..='\u{2069}')) {if out.len()+c.len_utf8()>limit {return out.trim_end().to_owned()}out.push(c)}
    }
    out.trim().to_owned()
}
fn entities(value:&str)->String {
    let mut out=String::new();let mut rest=value;
    while let Some(pos)=rest.find('&') {
        out.push_str(&rest[..pos]);rest=&rest[pos..];
        let Some(end)=rest.find(';').filter(|n|*n<=16) else {out.push('&');rest=&rest[1..];continue};
        let token=&rest[1..end];
        let c=match token {"amp"=>Some('&'),"quot"=>Some('"'),"apos"=>Some('\''),"lt"=>Some('<'),"gt"=>Some('>'),"nbsp"=>Some(' '),_=>{
            if let Some(hex)=token.strip_prefix("#x").or_else(||token.strip_prefix("#X")) {u32::from_str_radix(hex,16).ok().and_then(char::from_u32)}
            else {token.strip_prefix('#').and_then(|v|v.parse::<u32>().ok()).and_then(char::from_u32)}
        }};
        if let Some(c)=c {out.push(c);rest=&rest[end+1..]} else {out.push('&');rest=&rest[1..]}
    }
    out.push_str(rest);out
}
fn attributes(mut text:&str)->Vec<(String,String)> {
    let mut result=Vec::new();
    while !text.is_empty() && result.len()<64 {
        text=text.trim_start_matches(|c:char|c.is_ascii_whitespace() || c=='/');
        let end=text.find(|c:char|c.is_ascii_whitespace() || c=='=').unwrap_or(text.len());
        if end==0 {break}let name=text[..end].to_ascii_lowercase();text=&text[end..];text=text.trim_start();
        if !text.starts_with('=') {continue}text=text[1..].trim_start();
        let value=if text.starts_with(['\'','"']) {
            let quote=text.as_bytes()[0] as char;text=&text[1..];let end=text.find(quote).unwrap_or(text.len());let value=&text[..end];text=text.get(end+1..).unwrap_or("");value
        } else {let end=text.find(char::is_whitespace).unwrap_or(text.len());let value=&text[..end];text=&text[end..];value};
        result.push((name,value.to_owned()));
    }
    result
}
/// A bounded passive tag scanner: no DOM, script, remote stylesheet, iframe or embedded resource executes.
pub fn inspect(url:String,html:String)->Result<Value,String> {
    target(&url)?;if html.len()>1024*1024 {return Err("Preview document is too large".into())}
    let mut rest=html.as_str();let mut title=String::new();let mut summary=String::new();let mut site=String::new();let mut image=String::new();let mut fallback=String::new();let mut tags=0;
    while let Some(start)=rest.find('<') {
        rest=&rest[start+1..];tags+=1;if tags>20000 {break}
        if rest.starts_with("!--") {let Some(end)=rest.find("-->") else {break};rest=&rest[end+3..];continue}
        let mut quote=0u8;let mut end=None;
        for (i,b) in rest.bytes().enumerate() {if quote!=0 {if b==quote {quote=0}} else if b==b'\'' || b==b'"' {quote=b} else if b==b'>' {end=Some(i);break}}
        let Some(end)=end else {break};let tag=&rest[..end];rest=&rest[end+1..];let name=tag.split_ascii_whitespace().next().unwrap_or("").to_ascii_lowercase();
        if name=="/head" || name=="body" {break}
        if name=="script" || name=="style" {let close=format!("</{name}");let Some(end)=rest.as_bytes().windows(close.len()).position(|window|window.eq_ignore_ascii_case(close.as_bytes())) else {break};rest=&rest[end..];continue}
        if name=="title" && fallback.is_empty() {fallback=clean(rest.split('<').next().unwrap_or(""),512)}
        if name!="meta" {continue}
        let attrs=attributes(&tag[name.len()..]);let key=attrs.iter().find(|(k,_)|k=="property" || k=="name").map(|(_,v)|v.to_ascii_lowercase()).unwrap_or_default();
        let value=attrs.iter().find(|(k,_)|k=="content").map(|(_,v)|v.as_str()).unwrap_or("");
        match key.as_str() {
            "og:title"=>{if title.is_empty(){title=clean(value,512)}},
            "twitter:title"=>{if title.is_empty(){title=clean(value,512)}},
            "og:description"|"description"|"twitter:description"=>{if summary.is_empty(){summary=clean(value,2048)}},
            "og:site_name"=>{if site.is_empty(){site=clean(value,256)}},
            "og:image"|"og:image:url"|"twitter:image"=>{if image.is_empty(){image=entities(value).trim().chars().take(4096).collect()}},_=>{}
        }
    }
    if title.is_empty(){title=fallback}
    Ok(json!({"url":url,"title":title,"summary":summary,"site":site,"image_url":image}))
}
pub fn validate(value:&Value,text:&str)->Result<(),String> {
    let object=value.as_object().ok_or("Invalid link preview")?;
    let required = ["url", "title", "summary", "site", "image"];
    if !required.iter().all(|key| object.contains_key(*key))
        || object.keys().any(|key| !matches!(key.as_str(), "above_text" | "compact_image") && !required.contains(&key.as_str()))
        || ["above_text", "compact_image"].iter().any(|key| object.get(*key).is_some_and(|value| !value.is_boolean()))
    {
        return Err("Invalid link preview fields".into());
    }
    let url=value["url"].as_str().ok_or("Invalid preview URL")?;target(url)?;
    if !crate::rich_text::visible_url(text,url)? {return Err("Preview URL is absent from visible message text".into())}
    for (key,limit) in [("title",512),("summary",2048),("site",256)] {
        let field=value[key].as_str().ok_or("Invalid preview text")?;
        if field.len()>limit || clean(field,limit)!=field {return Err("Invalid preview text".into())}
    }
    if !value["image"].is_null() {
        let manifest:crate::objects::Manifest=serde_json::from_value(value["image"].clone()).map_err(|_|"Invalid preview image")?;
        crate::objects::validate(manifest)?;
        if value["image"]["size"].as_u64().is_none_or(|s|s==0 || s>512*1024) || value["image"]["mime"]!="image/jpeg" {return Err("Invalid preview image limits".into())}
    }
    Ok(())
}
