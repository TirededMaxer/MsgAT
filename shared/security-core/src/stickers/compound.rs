// SPDX-License-Identifier: AGPL-3.0-only
//! Conservative, bounded MS-CFB reader for classic QQ EIF; no extraction to disk.
use super::{Entry,Span,MAX_ITEM,u16_at,u32_at,u64_at};
use std::collections::HashSet;
const END:u32=0xfffffffe;
const FREE:u32=0xffffffff;

struct Compound<'a>{bytes:&'a [u8],sector:usize,fat:Vec<u32>}
impl<'a> Compound<'a>{
    fn sector(&self,id:u32)->Result<&'a [u8],String>{
        let start=(id as usize).checked_add(1).and_then(|v|v.checked_mul(self.sector)).ok_or("Invalid EIF sector")?;
        self.bytes.get(start..start+self.sector).ok_or_else(||"EIF sector is outside the package".into())
    }
    fn chain(&self,mut first:u32,limit:usize)->Result<Vec<u32>,String>{
        let mut result=Vec::new();let mut seen=HashSet::new();
        if limit==0 {if first!=END && first!=FREE {return Err("Unexpected EIF chain".into())}return Ok(result)}
        while first!=END {
            if result.len()>=limit || !seen.insert(first) {return Err("EIF chain is cyclic or too large".into())}
            self.sector(first)?;result.push(first);first=*self.fat.get(first as usize).ok_or("Missing EIF allocation entry")?;
        }
        Ok(result)
    }
    fn spans(&self,first:u32,size:usize)->Result<Vec<Span>,String>{
        let count=size.div_ceil(self.sector);let chain=self.chain(first,count)?;
        if chain.len()!=count {return Err("Truncated EIF stream".into())}
        Ok(chain.into_iter().enumerate().map(|(i,s)|Span{offset:(s as usize+1)*self.sector,length:self.sector.min(size-i*self.sector)}).collect())
    }
    fn read_chain(&self,first:u32,limit:usize)->Result<Vec<u8>,String>{
        let chain=self.chain(first,limit)?;let mut out=Vec::with_capacity(chain.len()*self.sector);
        for sector in chain {out.extend_from_slice(self.sector(sector)?)}Ok(out)
    }
}
#[derive(Clone)]
struct Directory{name:String,kind:u8,left:u32,right:u32,child:u32,first:u32,size:usize}
pub(super) fn catalog(bytes:&[u8])->Result<Vec<Entry>,String>{
    if bytes.len()<512 || u16_at(bytes,28)?!=0xfffe || u16_at(bytes,32)?!=6 || u32_at(bytes,56)?!=4096 {return Err("Unsupported EIF compound header".into())}
    let version=u16_at(bytes,26)?;let shift=u16_at(bytes,30)?;
    if !((version==3 && shift==9)||(version==4 && shift==12)) {return Err("Unsupported EIF compound version".into())}
    let sector=1usize<<shift;if bytes.len()%sector!=0 || bytes.len()/sector<3 {return Err("Truncated EIF package".into())}
    let count=bytes.len()/sector-1;let fat_count=u32_at(bytes,44)? as usize;let difat_count=u32_at(bytes,72)? as usize;
    if fat_count==0 || fat_count>count.div_ceil(sector/4)+1 || difat_count>count {return Err("Invalid EIF allocation size".into())}
    let mut c=Compound{bytes,sector,fat:Vec::new()};let mut fat_ids=Vec::with_capacity(fat_count);
    for n in 0..109 {let id=u32_at(bytes,76+n*4)?;if id!=FREE {fat_ids.push(id)}}
    let mut next=u32_at(bytes,68)?;let mut seen=HashSet::new();
    for _ in 0..difat_count {
        if !seen.insert(next) {return Err("Cyclic EIF allocation table".into())}
        let data=c.sector(next)?;
        for offset in (0..sector-4).step_by(4) {let id=u32_at(data,offset)?;if id!=FREE {fat_ids.push(id)}}
        if fat_ids.len()>fat_count {return Err("EIF allocation count mismatch".into())}next=u32_at(data,sector-4)?;
    }
    if (difat_count>0 && next!=END) || fat_ids.len()!=fat_count || fat_ids.iter().copied().collect::<HashSet<_>>().len()!=fat_count {return Err("EIF allocation table is inconsistent".into())}
    for id in fat_ids {let data=c.sector(id)?;for offset in (0..sector).step_by(4){c.fat.push(u32_at(data,offset)?);}}
    if c.fat.len()<count {return Err("Incomplete EIF allocation table".into())}
    let directory=c.read_chain(u32_at(bytes,48)?,(4096usize*128).div_ceil(sector))?;
    let mut dirs=Vec::new();
    for row in directory.chunks_exact(128) {
        let kind=row[66];let length=u16_at(row,64)? as usize;
        let name=if kind==0 {String::new()} else {
            if !(2..=64).contains(&length) || length%2!=0 || u16_at(row,length-2)?!=0 {return Err("Invalid EIF entry name".into())}
            let utf16:Vec<u16>=row[..length-2].chunks_exact(2).map(|b|u16::from_le_bytes([b[0],b[1]])).collect();String::from_utf16(&utf16).map_err(|_|"Invalid EIF entry name")?
        };
        let size=if version==3 {u32_at(row,120)? as u64}else{u64_at(row,120)?};
        if size>bytes.len() as u64 {return Err("EIF stream exceeds the package".into())}
        dirs.push(Directory{name,kind,left:u32_at(row,68)?,right:u32_at(row,72)?,child:u32_at(row,76)?,first:u32_at(row,116)?,size:size as usize});
    }
    let root=dirs.first().filter(|d|d.kind==5).ok_or("Missing EIF root storage")?;
    let mini_root=c.spans(root.first,root.size)?;let mini_count=u32_at(bytes,64)? as usize;
    if mini_count>root.size.div_ceil(64).div_ceil(sector/4)+1 {return Err("EIF mini allocation table is oversized".into())}
    let mini_bytes=c.read_chain(u32_at(bytes,60)?,mini_count)?;
    if mini_bytes.len()!=mini_count*sector {return Err("Incomplete EIF mini allocation table".into())}
    let mini_fat=mini_bytes.chunks_exact(4).map(|b|u32::from_le_bytes([b[0],b[1],b[2],b[3]])).collect::<Vec<_>>();
    let mut pending=vec![(root.child,String::new(),0usize)];let mut visited=HashSet::new();let mut entries=Vec::new();let mut expanded=0usize;
    while let Some((id,prefix,depth))=pending.pop() {
        if id==FREE {continue}
        if !visited.insert(id) || depth>32 {return Err("EIF directory is cyclic or too deep".into())}
        let item=dirs.get(id as usize).ok_or("Invalid EIF directory entry")?;
        pending.push((item.left,prefix.clone(),depth));pending.push((item.right,prefix.clone(),depth));
        if item.name.contains(['/', '\\','\0']) || item.name==".." {return Err("Invalid EIF entry path".into())}
        let path=if prefix.is_empty(){item.name.clone()}else{format!("{prefix}/{}",item.name)};
        if item.kind==1 {pending.push((item.child,path,depth+1));continue}
        if item.kind!=2 || item.size==0 || item.size>MAX_ITEM {continue}
        expanded=expanded.checked_add(item.size).filter(|v|*v<=256*1024*1024).ok_or("EIF streams exceed the total import limit")?;
        let spans=if item.size>=4096 {c.spans(item.first,item.size)?}else{
            let needed=item.size.div_ceil(64);let mut spans=Vec::with_capacity(needed);let mut seen=HashSet::new();let mut next=item.first;
            for index in 0..needed {
                if !seen.insert(next) {return Err("Cyclic EIF mini stream".into())}
                let offset=(next as usize).checked_mul(64).filter(|v|*v<root.size).ok_or("Invalid EIF mini stream offset")?;
                let root_sector=offset/sector;let inside=offset%sector;let count=64.min(item.size-index*64);
                let parent=mini_root.get(root_sector).ok_or("Truncated EIF mini stream")?;
                if inside+count>parent.length {return Err("EIF mini stream exceeds its root".into())}
                spans.push(Span{offset:parent.offset+inside,length:count});next=*mini_fat.get(next as usize).ok_or("Missing EIF mini allocation")?;
            }
            if next!=END {return Err("EIF mini stream size mismatch".into())}spans
        };
        entries.push(super::entry(path,item.size,0,None,spans)?);
    }
    Ok(entries)
}
