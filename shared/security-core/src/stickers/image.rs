// SPDX-License-Identifier: AGPL-3.0-only
//! Structural bounds for supported still/GIF/WebP images; platform codecs validate actual pixels.
use super::{u16_at,u32_at};
fn be16(b:&[u8],p:usize)->Result<usize,String>{Ok(u16::from_be_bytes(b.get(p..p+2).ok_or("Truncated image header")?.try_into().map_err(|_|"Invalid image header")?) as usize)}
fn be32(b:&[u8],p:usize)->Result<usize,String>{Ok(u32::from_be_bytes(b.get(p..p+4).ok_or("Truncated image header")?.try_into().map_err(|_|"Invalid image header")?) as usize)}
fn le24(b:&[u8],p:usize)->Result<usize,String>{let v=b.get(p..p+3).ok_or("Truncated image header")?;Ok(v[0] as usize | (v[1] as usize)<<8 | (v[2] as usize)<<16)}
fn dimensions(w:usize,h:usize,frames:usize)->Result<(usize,usize,usize),String>{if w==0 || h==0 || w>2048 || h>2048 || frames==0 || frames>300 {return Err("Sticker dimensions or frame count exceed the supported limits".into())}Ok((w,h,frames))}
fn blocks(b:&[u8],mut p:usize)->Result<usize,String>{loop{let count=*b.get(p).ok_or("Truncated GIF block")? as usize;p+=1;if count==0{return Ok(p)}p=p.checked_add(count).filter(|v|*v<=b.len()).ok_or("Truncated GIF block")?;}}
pub(super) fn inspect(b:&[u8],mime:&str)->Result<(usize,usize,usize),String>{
    match mime {
        "image/gif"=>{
            if b.len()<13 {return Err("Truncated GIF header".into())}
            let w=u16_at(b,6)? as usize;let h=u16_at(b,8)? as usize;dimensions(w,h,1)?;
            let mut p=13;if b[10]&0x80!=0 {p+=3*(1usize<<((b[10]&7)+1))}let mut frames=0;
            loop {
                let tag=*b.get(p).ok_or("Missing GIF trailer")?;p+=1;
                match tag {
                    0x3b=>return dimensions(w,h,frames),
                    0x00=>{},
                    0x21=>{p+=1;if p>b.len(){return Err("Truncated GIF extension".into())}p=blocks(b,p)?;},
                    0x2c=>{
                        let x=u16_at(b,p)? as usize;let y=u16_at(b,p+2)? as usize;let fw=u16_at(b,p+4)? as usize;let fh=u16_at(b,p+6)? as usize;let flags=*b.get(p+8).ok_or("Truncated GIF frame")?;p+=9;
                        if fw==0 || fh==0 || x+fw>w || y+fh>h {return Err("GIF frame exceeds its canvas".into())}
                        if flags&0x80!=0 {p+=3*(1usize<<((flags&7)+1))}
                        let code=*b.get(p).ok_or("Missing GIF image data")?;if !(2..=8).contains(&code){return Err("Invalid GIF image code size".into())}p=blocks(b,p+1)?;
                        frames+=1;if frames>300 {return Err("Animated sticker exceeds 300 frames".into())}
                    },_=>return Err("Unsupported GIF structure".into())
                }
            }
        }
        "image/png"=>{
            if b.get(12..16)!=Some(b"IHDR") || be32(b,8)?!=13 {return Err("Invalid PNG header".into())}
            let w=be32(b,16)?;let h=be32(b,20)?;dimensions(w,h,1)?;
            let mut p=8;while p<b.len(){let n=be32(b,p)?;let end=p.checked_add(12).and_then(|v|v.checked_add(n)).filter(|n|*n<=b.len()).ok_or("Truncated PNG chunk")?;
                if b.get(p+4..p+8)==Some(b"acTL") && (n!=8 || be32(b,p+8)?>1) {return Err("Export animated PNG as GIF or WebP to preserve its animation".into())}
                if b.get(p+4..p+8)==Some(b"IEND"){return dimensions(w,h,1)}p=end;
            }Err("Missing PNG trailer".into())
        }
        "image/webp"=>{
            let end=(u32_at(b,4)? as usize).checked_add(8).filter(|v|*v<=b.len() && *v>=12).ok_or("Truncated WebP container")?;
            let mut p=12;let(mut w,mut h,mut frames)=(0,0,0);
            while p<end {
                let n=u32_at(b,p+4)? as usize;let data=p+8;let next=data.checked_add(n).and_then(|v|v.checked_add(n&1)).filter(|v|*v<=end).ok_or("Truncated WebP chunk")?;
                match b.get(p..p+4).ok_or("Invalid WebP chunk")? {
                    b"VP8X"=>{if n<10{return Err("Invalid WebP canvas".into())}w=1+le24(b,data+4)?;h=1+le24(b,data+7)?;dimensions(w,h,1)?;},
                    b"VP8 "=>{if n<10 || b.get(data+3..data+6)!=Some(&[0x9d,1,0x2a]) {return Err("Invalid WebP frame".into())}if w==0 {w=(u16_at(b,data+6)?&0x3fff) as usize;h=(u16_at(b,data+8)?&0x3fff) as usize;}},
                    b"VP8L"=>{if n<5 || b.get(data)!=Some(&0x2f){return Err("Invalid WebP lossless frame".into())}if w==0{let bits=u32_at(b,data+1)?;w=1+(bits&0x3fff) as usize;h=1+((bits>>14)&0x3fff) as usize;}},
                    b"ANMF"=>{if n<16{return Err("Truncated animated WebP frame".into())}let x=le24(b,data)?*2;let y=le24(b,data+3)?*2;let fw=le24(b,data+6)?+1;let fh=le24(b,data+9)?+1;if w==0 || x+fw>w || y+fh>h {return Err("WebP frame exceeds its canvas".into())}frames+=1;if frames>300{return Err("Animated sticker exceeds 300 frames".into())}},_=>{}
                }p=next;
            }
            dimensions(w,h,frames.max(1))
        }
        "image/jpeg"=>{
            let mut p=2;while p<b.len() {
                if b[p]!=0xff{return Err("Invalid JPEG marker".into())}while b.get(p)==Some(&0xff){p+=1}
                let marker=*b.get(p).ok_or("Truncated JPEG marker")?;p+=1;
                if marker==0xd9 || marker==0xda {return Err("Missing JPEG dimensions".into())}
                if marker==0x01 || (0xd0..=0xd8).contains(&marker){continue}
                let n=be16(b,p)?;if n<2 || p.checked_add(n).is_none_or(|v|v>b.len()){return Err("Truncated JPEG segment".into())}
                if [0xc0,0xc1,0xc2,0xc3,0xc5,0xc6,0xc7,0xc9,0xca,0xcb,0xcd,0xce,0xcf].contains(&marker){if n<7{return Err("Invalid JPEG dimensions".into())}return dimensions(be16(b,p+5)?,be16(b,p+3)?,1)}p+=n;
            }Err("Missing JPEG dimensions".into())
        }
        "image/bmp"=>{
            let n=u32_at(b,14)?;
            if n==12 {dimensions(u16_at(b,18)? as usize,u16_at(b,20)? as usize,1)}
            else if n>=40 {let w=u32_at(b,18)? as i32;let h=(u32_at(b,22)? as i32).checked_abs().ok_or("Invalid BMP height")?;if w<=0{return Err("Invalid BMP width".into())}dimensions(w as usize,h as usize,1)}
            else {Err("Unsupported BMP header".into())}
        },_=>Err("Unsupported sticker image".into())
    }
}
