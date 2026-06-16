import os
import re
import json

with open('comments_mapping.json', 'r', encoding='utf-8') as f:
    mapping = json.load(f)

def strip_comments(code):
    pattern = r'("(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\')|(/\*.*?\*/|//[^\r\n]*)'
    def replacer(match):
        if match.group(2):
            return ""
        return match.group(1)
    
    code = re.sub(pattern, replacer, code, flags=re.DOTALL)
    lines = code.split('\n')
    cleaned_lines = []
    for line in lines:
        if line.strip() == "":
            if len(cleaned_lines) > 0 and cleaned_lines[-1].strip() != "":
                cleaned_lines.append("")
        else:
            cleaned_lines.append(line)
    return "\n".join(cleaned_lines)

for filepath, methods in mapping.items():
    if not os.path.exists(filepath):
        continue
    
    with open(filepath, 'r', encoding='utf-8') as f:
        code = f.read()
    
    code = strip_comments(code)
    
    lines = code.split('\n')
    out_lines = []
    
    for line in lines:
        matched_comment = None
        for sig, comment in methods.items():
            if sig in line and "fun " in line:
                matched_comment = comment
                break
        
        if matched_comment:
            indent = line[:len(line) - len(line.lstrip())]
            out_lines.append(f"{indent}// {matched_comment}")
        
        out_lines.append(line)
        
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write("\n".join(out_lines))

print("Applied comments!")
