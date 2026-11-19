// SPDX-License-Identifier: AGPL-3.0-only
//! Bounded display markup. Produces native text runs, never HTML or executable content.
use serde::Serialize;
use serde_json::{Value,json};
use sha2::{Digest, Sha256};
use zeroize::Zeroizing;

#[derive(Serialize)]
struct Run {text:String,style:u8,spoiler:u32,url:Option<String>}
#[derive(Serialize)]
struct Block {
    kind: &'static str,
    language: String,
    runs: Vec<Run>,
    #[serde(skip_serializing_if = "Option::is_none")]
    quote_fold: Option<QuoteFold>,
}

#[derive(Serialize)]
struct QuoteFold { run: usize, utf16_end: usize }

fn quote_fold(runs: &[Run]) -> Option<QuoteFold> {
    const VISIBLE_LINES: usize = 4;
    let mut breaks = 0;
    for (run, item) in runs.iter().enumerate() {
        for (offset, _) in item.text.match_indices('\n') {
            breaks += 1;
            if breaks == VISIBLE_LINES {
                return Some(QuoteFold { run, utf16_end: item.text[..offset].encode_utf16().count() });
            }
        }
    }
    None
}
#[derive(Clone,Copy)]
struct Pair {other:usize,flag:u8,width:usize}

fn marker(bytes:&[u8],index:usize)->Option<u8>{
    match bytes.get(index..index+2)? {b"**"=>Some(1),b"__"=>Some(2),b"++"=>Some(4),b"~~"=>Some(8),b"||"=>Some(32),_=>None}
}
fn escaped(bytes:&[u8],index:usize)->bool {
    bytes[index]==b'\\' && bytes.get(index+1).is_some_and(|b|b"\\*_+~|`>[]()".contains(b))
}
fn url_end(text:&str,index:usize,open:u8)->Option<(usize,bool)>{
    let tail=&text[index..];
    let bare = tail.get(..4).is_some_and(|prefix|prefix.eq_ignore_ascii_case("www."));
    let explicit = ["https://", "http://"].iter().any(|scheme|
        tail.get(..scheme.len()).is_some_and(|prefix|prefix.eq_ignore_ascii_case(scheme)));
    if !bare && !explicit { return None; }
    if index > 0 && text.as_bytes()[index-1].is_ascii_alphanumeric() { return None; }
    if bare && index > 0 && b".@_-/".contains(&text.as_bytes()[index-1]) { return None; }
    let mut end=index+tail.find(|c:char|c.is_whitespace() || matches!(c,'<'|'>'|'"'|'，'|'。'|'！'|'？'|'；'|'、'|'）'|'】'|'》'|'」'|'』')).unwrap_or(tail.len());
    while end>index && b".,!?;:".contains(&text.as_bytes()[end-1]){end-=1;}
    while end>=index+2 {
        let Some(flag)=marker(text.as_bytes(),end-2) else{break};
        if flag & open==0 {break}
        end-=2;
    }
    for (close,open) in [(')', '('),(']', '['),('}', '{')] {
        let mut excess=text[index..end].chars().filter(|c|*c==close).count().saturating_sub(text[index..end].chars().filter(|c|*c==open).count());
        while excess>0 && text[index..end].ends_with(close){end-=1;excess-=1;}
    }
    Some((end,bare))
}
fn emit(runs:&mut Vec<Run>,text:&str,style:u8,spoiler:u32,url:Option<String>){
    if text.is_empty(){return}
    if let Some(last) = runs.last_mut()
        && last.style == style && last.spoiler == spoiler && last.url == url
    {
        last.text.push_str(text);
        return;
    }
    runs.push(Run{text:text.into(),style,spoiler,url});
}
fn inline(text:&str,base:u32)->Vec<Run>{
    let bytes=text.as_bytes();let mut pairs=vec![None;bytes.len()];let mut stack=Vec::<(usize,u8)>::new();let mut index=0;
    // One pairing pass; unmatched/crossing markers remain ordinary text.
    while index<bytes.len(){
        if escaped(bytes,index){index+=2;continue}
        if let Some((end,_))=url_end(text,index,stack.iter().fold(0,|mask,(_,flag)|mask|flag)){index=end;continue}
        if bytes[index]==b'`'{
            let line=&text[index+1..];let line=&line[..line.find('\n').unwrap_or(line.len())];
            if let Some(relative)=line.find('`'){
                let end=index+1+relative;pairs[index]=Some(Pair{other:end,flag:16,width:1});pairs[end]=Some(Pair{other:index,flag:16,width:1});index=end+1;continue;
            }
        }
        if let Some(flag)=marker(bytes,index){
            if stack.last().is_some_and(|(_,top)|*top==flag){
                let (start,_)=stack.pop().unwrap();pairs[start]=Some(Pair{other:index,flag,width:2});pairs[index]=Some(Pair{other:start,flag,width:2});
            }else if stack.len()<32 {stack.push((index,flag));}
            index+=2;
        }else{index+=text[index..].chars().next().unwrap().len_utf8();}
    }
    let mut runs=Vec::new();let mut style=0;let mut spoiler=0;index=0;
    while index<bytes.len(){
        if escaped(bytes,index){emit(&mut runs,&text[index+1..index+2],style,spoiler,None);index+=2;continue}
        if let Some(pair)=pairs[index]{
            if pair.flag==16 && pair.other>index {
                emit(&mut runs,&text[index+1..pair.other],style|16,spoiler,None);index=pair.other+1;continue;
            }
            if pair.flag==32 {spoiler=if pair.other>index {base+index as u32+1}else{0};}
            else{style^=pair.flag;}
            index+=pair.width;continue;
        }
        if let Some((end,bare))=url_end(text,index,style|if spoiler!=0{32}else{0}){
            let value=&text[index..end];
            let destination = if bare { format!("https://{value}") } else { value.to_owned() };
            let url = crate::web_preview::target(&destination).ok().and_then(|target| {
                (!bare || target.host.split('.').count() >= 3 &&
                    target.host.rsplit('.').next().is_some_and(|suffix|
                        suffix.len() >= 2 && (suffix.starts_with("xn--") || suffix.bytes().all(|b| b.is_ascii_alphabetic()))))
                    .then_some(destination)
            });
            emit(&mut runs,value,style,spoiler,url);index=end;continue;
        }
        let next=index+text[index..].chars().next().unwrap().len_utf8();emit(&mut runs,&text[index..next],style,spoiler,None);index=next;
    }
    runs
}

fn blocks(text:&str)->Result<Vec<Block>,String>{
    if text.len()>48*1024{return Err("Message text exceeds the supported display size".into())}
    let normalized=text.replace("\r\n","\n").replace('\r',"\n");let lines:Vec<&str>=normalized.split('\n').collect();
    let mut blocks=Vec::new();let mut index=0;let mut base=0u32;
    while index<lines.len(){
        let line=lines[index];
        if let Some(language) = line.strip_prefix("```")
            && language.len() <= 32 && language.bytes().all(|b| b.is_ascii_alphanumeric() || b"_+-".contains(&b))
        {
            let mut content=String::new();index+=1;let start=index;
            while index<lines.len() && lines[index].trim()!="```"{if index>start{content.push('\n');}content.push_str(lines[index]);index+=1;}
            if index<lines.len(){index+=1;}
            base+=content.len() as u32+1;
            blocks.push(Block{kind:"code",language:language.into(),runs:vec![Run{text:content,style:16,spoiler:0,url:None}],quote_fold:None});continue;
        }
        let quote=line.starts_with("> ") || line==">";let kind=if quote{"quote"}else{"paragraph"};let mut content=String::new();let start=index;
        while index<lines.len(){
            let line=lines[index];let next_quote=line.starts_with("> ") || line==">";
            if next_quote!=quote || line.starts_with("```"){break}
            if index>start{content.push('\n');}
            content.push_str(if quote{line.strip_prefix("> ").unwrap_or("")}else{line});index+=1;
        }
        // An invalid fence stays literal and must still advance.
        if index==start{content.push_str(lines[index]);index+=1;}
        let runs=inline(&content,base);base+=content.len() as u32+1;
        let quote_fold = if quote { quote_fold(&runs) } else { None };
        blocks.push(Block{kind,language:String::new(),runs,quote_fold});
    }
    Ok(blocks)
}

pub fn render(text:String)->Result<Value,String>{
    let text=Zeroizing::new(text);
    Ok(json!({"version":1,"blocks":blocks(&text)?}))
}

pub fn copy_text(text: String, revealed: Vec<u32>, placeholder: String) -> Result<Value, String> {
    if revealed.len() > 48 * 1024 / 4 || placeholder.len() > 128 {
        return Err("Unsupported message copy input".into());
    }
    let text = Zeroizing::new(text);
    let revealed: std::collections::HashSet<u32> = revealed.into_iter().collect();
    let mut output = Zeroizing::new(String::with_capacity(text.len()));
    for (index, block) in blocks(&text)?.into_iter().enumerate() {
        if index != 0 {
            output.push('\n');
        }
        let mut previous_hidden = 0;
        for run in block.runs {
            let hidden = run.spoiler != 0 && !revealed.contains(&run.spoiler);
            if hidden {
                if previous_hidden != run.spoiler {
                    output.push_str(&placeholder);
                }
                previous_hidden = run.spoiler;
            } else {
                output.push_str(&run.text);
                previous_hidden = 0;
            }
        }
    }
    Ok(json!({"text": output.as_str()}))
}

fn reply_quote_document(text: &str) -> Result<String, String> {
    if text.len() > 48 * 1024 {
        return Err("Message text exceeds the supported quote size".into());
    }
    let masked = Zeroizing::new(masked_spoilers(text));
    let mut visible = String::with_capacity(text.len());
    for (index, block) in blocks(&masked)?.into_iter().enumerate() {
        if index != 0 {
            visible.push('\n');
        }
        for run in block.runs {
            if run.spoiler == 0 { visible.push_str(&run.text); }
        }
    }
    Ok(visible.replace('\0', "\u{fffd}"))
}

fn reply_quote_digest(text: &str) -> String {
    format!("{:x}", Sha256::digest(text.as_bytes()))
}

pub fn reply_quote_text(text: String) -> Result<Value, String> {
    let source = Zeroizing::new(text);
    let visible = Zeroizing::new(reply_quote_document(&source)?);
    Ok(json!({"text": visible.as_str(), "digest": reply_quote_digest(&source)}))
}

pub fn reply_quote_selection(text: String, selection: String) -> Result<Value, String> {
    let source = Zeroizing::new(text);
    let invalid = || "The selected quote is no longer available".to_owned();
    let mut parts = selection.splitn(3, ':');
    let start: u32 = parts.next().and_then(|value| value.parse().ok()).ok_or_else(invalid)?;
    let end: u32 = parts.next().and_then(|value| value.parse().ok()).ok_or_else(invalid)?;
    let digest = parts.next().ok_or_else(invalid)?;
    if source.len() > 48 * 1024 || digest != reply_quote_digest(&source) || end <= start || end - start > 1024 {
        return Err("The selected quote is no longer available".into());
    }
    let visible = Zeroizing::new(reply_quote_document(&source)?);
    let start = utf16_byte(&visible, start)?;
    let end = utf16_byte(&visible, end)?;
    let quote = visible[start..end].trim();
    if quote.is_empty() || quote.chars().count() > 512 || quote.contains('\0') {
        return Err("Select between 1 and 512 characters to quote".into());
    }
    // Escape display markup so selecting part of a run cannot form new delimiters.
    let mut escaped = String::with_capacity(quote.len());
    for character in quote.chars() {
        if matches!(character, '\\' | '*' | '_' | '+' | '~' | '|' | '`' | '>') {
            escaped.push('\\');
        }
        escaped.push(character);
    }
    if escaped.len() > 4096 {
        return Err("The quote is too large".into());
    }
    Ok(json!({"preview": escaped}))
}

pub fn locate_reply_quote(text: String, preview: String) -> Result<Value, String> {
    let text = Zeroizing::new(text);
    let preview = Zeroizing::new(preview);
    if preview.is_empty() || preview.len() > 4096 || preview.contains('\0') {
        return Ok(json!({"ranges": []}));
    }
    let mut excerpt = Zeroizing::new(String::with_capacity(preview.len()));
    let mut chars = preview.chars().peekable();
    while let Some(character) = chars.next() {
        if character == '\\' && chars.peek().is_some_and(|next| matches!(*next, '\\' | '*' | '_' | '+' | '~' | '|' | '`' | '>')) {
            excerpt.push(chars.next().unwrap());
        } else {
            excerpt.push(character);
        }
    }
    if excerpt.trim().is_empty() { return Ok(json!({"ranges": []})); }

    let document = blocks(&text)?;
    let mut visible = Zeroizing::new(String::new());
    let mut segments = Vec::new();
    for (block_index, block) in document.iter().enumerate() {
        if block_index != 0 { visible.push('\n'); }
        for (run_index, run) in block.runs.iter().enumerate() {
            if run.spoiler != 0 {
                visible.push('\0');
            } else {
                let start = visible.len();
                visible.push_str(&run.text);
                segments.push((block_index, run_index, start, visible.len(), run.text.as_str()));
            }
        }
    }
    let Some(start) = visible.find(excerpt.as_str()) else { return Ok(json!({"ranges": []})); };
    let next = start + visible[start..].chars().next().unwrap().len_utf8();
    if visible[next..].contains(excerpt.as_str()) { return Ok(json!({"ranges": []})); }
    let end = start + excerpt.len();
    let ranges: Vec<_> = segments.into_iter().filter_map(|(block, run, from, to, value)| {
        let overlap_start = start.max(from);
        let overlap_end = end.min(to);
        if overlap_start >= overlap_end { return None; }
        Some(json!({"block": block, "run": run,
            "start": value[..overlap_start-from].encode_utf16().count(),
            "end": value[..overlap_end-from].encode_utf16().count()}))
    }).collect();
    Ok(json!({"ranges": ranges}))
}
pub fn preview_candidate(text:&str)->Result<Option<String>,String>{
    for block in blocks(&masked_spoilers(text))? {for run in block.runs {if run.spoiler==0 && run.style & 16==0 && run.url.is_some(){return Ok(run.url)}}}
    Ok(None)
}
pub fn visible_url(text:&str,url:&str)->Result<bool,String>{
    Ok(blocks(&masked_spoilers(text))?.into_iter().flat_map(|block|block.runs).any(|run|run.spoiler==0 && run.style & 16==0 && run.url.as_deref()==Some(url)))
}

pub fn visible_urls(text: String) -> Result<Value, String> {
    let text = Zeroizing::new(text);
    let mut seen = std::collections::HashSet::new();
    let urls: Vec<String> = blocks(&masked_spoilers(&text))?
        .into_iter()
        .flat_map(|block| block.runs)
        .filter(|run| run.spoiler == 0 && run.style & 16 == 0)
        .filter_map(|run| run.url)
        .filter(|url| seen.insert(url.clone()))
        .collect();
    Ok(json!({"urls": urls}))
}

/// Summaries can start from a database prefix that ends before a closing spoiler.
/// Mask conservatively before parsing so truncation cannot expose the hidden tail.
pub fn summarize(text:String,limit:usize)->Result<Value,String>{
    let text=Zeroizing::new(text);
    if text.len()>48*1024 || limit>48*1024{return Err("Unsupported message summary size".into())}
    let masked=masked_spoilers(&text);
    summarize_visible(&masked,limit)
}
fn masked_spoilers(text:&str)->String{
    let mut masked=String::new();let mut hidden=false;let mut index=0;
    while index<text.len(){
        if escaped(text.as_bytes(),index){if !hidden{masked.push_str(&text[index..index+2]);}index+=2;continue}
        if text[index..].starts_with("||") {if !hidden{masked.push_str("Hidden text");}hidden=!hidden;index+=2;continue}
        let next=index+text[index..].chars().next().unwrap().len_utf8();
        if !hidden{masked.push_str(&text[index..next]);}index=next;
    }
    masked
}
fn summarize_visible(masked:&str,limit:usize)->Result<Value,String>{
    let mut output=String::new();let mut count=0;let mut space=false;
    for block in blocks(masked)? {
        if !output.is_empty(){space=true;}
        for run in block.runs {for character in run.text.chars(){
            if character.is_whitespace(){space=true;continue}
            if character.is_control(){continue}
            if space && !output.is_empty() && count<limit{output.push(' ');count+=1;}space=false;
            if count>=limit{return Ok(json!({"text":output}))}
            output.push(character);count+=1;
        }}
    }
    Ok(json!({"text":output}))
}

fn utf16_byte(text:&str,offset:u32)->Result<usize,String>{
    let mut position=0;
    for(index,character)in text.char_indices(){if position==offset{return Ok(index)}position+=character.len_utf16() as u32;if position>offset{return Err("The selection splits a character".into())}}
    if position==offset{Ok(text.len())}else{Err("Invalid text selection".into())}
}
/// Native text editors on all hosts use UTF-16 selections. Preserve original source and undo semantics.
pub fn format_selection(text:String,start:u32,end:u32,kind:String)->Result<Value,String>{
    let text=Zeroizing::new(text);
    if text.len()>48*1024{return Err("Message text is too large".into())}
    let start_byte=utf16_byte(&text,start.min(end))?;let end_byte=utf16_byte(&text,start.max(end))?;
    let before=&text[..start_byte];let selected=&text[start_byte..end_byte];let after=&text[end_byte..];
    let (opening,closing)=match kind.as_str(){
        "bold"=>("**".to_owned(),"**".to_owned()),"italic"=>("__".to_owned(),"__".to_owned()),
        "underline"=>("++".to_owned(),"++".to_owned()),"strike"=>("~~".to_owned(),"~~".to_owned()),
        "spoiler"=>("||".to_owned(),"||".to_owned()),
        "code"=>{if selected.contains('`') || selected.contains('\n'){return Err("Use a code block for this selection".into())}("`".to_owned(),"`".to_owned())},
        "code_block"=>{
            if selected.lines().any(|line|line.trim()=="```"){return Err("This selection already contains a code fence".into())}
            (format!("{}```\n",if before.is_empty() || before.ends_with('\n'){""}else{"\n"}),format!("\n```{}",if after.is_empty() || after.starts_with('\n'){""}else{"\n"}))
        },
        "quote"=>{
            let line_start=before.rfind('\n').map(|i|i+1).unwrap_or(0);
            let line_end=after.find('\n').map(|i|end_byte+i).unwrap_or(text.len());
            let source=&text[line_start..line_end];
            let remove=!source.is_empty() && source.split('\n').all(|line|line.starts_with("> "));
            let replacement=source.split('\n').map(|line|if remove{line.strip_prefix("> ").unwrap_or(line).to_owned()}else{format!("> {line}")}).collect::<Vec<_>>().join("\n");
            let prefix=&text[..line_start];let result=format!("{}{}{}",prefix,replacement,&text[line_end..]);
            if result.len()>48*1024{return Err("Formatting exceeds the message size limit".into())}
            let anchor=prefix.encode_utf16().count() as u32;
            let (selection_start,selection_end)=if start==end{let caret=if remove{start.saturating_sub(2).max(anchor)}else{start+2};(caret,caret)}else{(anchor,anchor+replacement.encode_utf16().count() as u32)};
            return Ok(json!({"text":result,"start":selection_start,"end":selection_end}));
        },
        _=>return Err("Unsupported text format".into())
    };
    let toggle=before.ends_with(&opening) && after.starts_with(&closing);
    let (result,anchor)=if toggle{
        let prefix=&before[..before.len()-opening.len()];(format!("{}{}{}",prefix,selected,&after[closing.len()..]),prefix.encode_utf16().count() as u32)
    }else{(format!("{}{}{}{}{}",before,opening,selected,closing,after),(before.encode_utf16().count()+opening.encode_utf16().count()) as u32)};
    if result.len()>48*1024{return Err("Formatting exceeds the message size limit".into())}
    Ok(json!({"text":result,"start":anchor,"end":anchor+selected.encode_utf16().count() as u32}))
}
