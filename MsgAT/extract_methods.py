import os
import re
import json

base_dir = "app/src/main/java/org/msgat"
result = {}

def get_methods(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # simple regex to find fun declarations
    # matches 'fun name(' or 'suspend fun name(' or 'private fun name('
    methods = []
    lines = content.split('\n')
    for i, line in enumerate(lines):
        match = re.search(r'\b(fun)\s+([a-zA-Z0-9_<>]+)\s*\(', line)
        if match:
            # try to get the whole line
            methods.append({'line_num': i, 'signature': line.strip()})
    return methods

for root, dirs, files in os.walk(base_dir):
    for file in files:
        if file.endswith(".kt"):
            path = os.path.join(root, file)
            methods = get_methods(path)
            if methods:
                result[path] = methods

with open('methods.json', 'w', encoding='utf-8') as f:
    json.dump(result, f, indent=2)

print("Extracted methods to methods.json")
