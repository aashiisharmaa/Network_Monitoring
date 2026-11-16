"""Source-derived API handbook generator. Examples are synthetic, never live customer data."""
from __future__ import annotations
import hashlib
import json
import re
import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTROLLERS = ROOT / "backend-java/src/main/java/com/network/monitoring"
OUT = ROOT / "reports/api-reference"


def balanced(text: str, start: int, left="(", right=")") -> int:
    depth, quote, escaped = 0, None, False
    for index in range(start, len(text)):
        char = text[index]
        if quote:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
            continue
        if char in "\"'":
            quote = char
        elif char == left:
            depth += 1
        elif char == right:
            depth -= 1
            if depth == 0:
                return index
    raise ValueError("Unbalanced Java syntax")


def without_comments(text):
    pattern = r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/'
    return re.sub(pattern, lambda m: re.sub(r"[^\n]", " ", m[0]) if m[0].startswith(("//", "/*")) else m[0], text)


def split_top(text):
    result, start, parens, angle, braces, quote, escaped = [], 0, 0, 0, 0, None, False
    for index, char in enumerate(text):
        if quote:
            if escaped: escaped = False
            elif char == "\\": escaped = True
            elif char == quote: quote = None
            continue
        if char in "\"'": quote = char
        elif char == "(": parens += 1
        elif char == ")": parens -= 1
        elif char == "<": angle += 1
        elif char == ">" and angle: angle -= 1
        elif char in "{[": braces += 1
        elif char in "}]": braces -= 1
        elif char == "," and not (parens or angle or braces):
            result.append(text[start:index].strip()); start = index + 1
    if text[start:].strip(): result.append(text[start:].strip())
    return result


def annotations(text):
    result = []
    for match in re.finditer(r"@(\w+)\b", text):
        start = match.end()
        while start < len(text) and text[start].isspace(): start += 1
        end = balanced(text, start) + 1 if start < len(text) and text[start] == "(" else match.end()
        result.append((match[1], text[start + 1:end - 1] if end > start and text[start:start + 1] == "(" else "", match.start(), end))
    return result


def literal(value):
    match = re.fullmatch(r'"((?:\\.|[^"\\])*)"', value.strip())
    return json.loads('"' + match[1] + '"') if match else value.strip()


def attributes(args):
    result = {}
    for part in split_top(args):
        match = re.match(r"(\w+)\s*=\s*([\s\S]+)", part)
        if match: result[match[1]] = match[2]
        else: result["value"] = part
    return result


def paths(args):
    attrs = attributes(args)
    raw = attrs.get("value", attrs.get("path", '""'))
    values = re.findall(r'"((?:\\.|[^"\\])*)"', raw)
    return values or [""]


def parameter(raw):
    anns = annotations(raw)
    cleaned = raw
    for _, _, start, end in reversed(anns): cleaned = cleaned[:start] + " " + cleaned[end:]
    match = re.search(r"([\w$]+)\s*$", cleaned)
    if not match: raise ValueError("Cannot parse parameter " + raw)
    variable = match[1]
    java_type = re.sub(r"\s+", " ", cleaned[:match.start()].strip())
    for name, args, _, _ in anns:
        if name not in {"RequestParam", "PathVariable", "RequestBody", "RequestPart", "RequestHeader"}: continue
        attrs = attributes(args)
        default = literal(attrs["defaultValue"]) if "defaultValue" in attrs else None
        required = attrs.get("required", "true") != "false" and default is None
        return {"name": literal(attrs.get("value", attrs.get("name", '"' + variable + '"'))),
                "variable": variable, "javaType": java_type, "location": {"RequestParam": "query", "PathVariable": "path", "RequestBody": "body", "RequestPart": "form", "RequestHeader": "header"}[name],
                "required": required, "default": default, "validation": [x[0] + ("(" + x[1] + ")" if x[1] else "") for x in anns if x[0] not in {"RequestParam", "PathVariable", "RequestBody", "RequestPart", "RequestHeader"}]}
    return {"name": variable, "javaType": java_type, "location": "server-context", "required": False, "default": None, "validation": []}


def inventory():
    endpoints, source_hashes = [], {}
    for file in sorted(CONTROLLERS.rglob("*Controller.java")):
        original = file.read_text(encoding="utf-8-sig")
        source = without_comments(original)
        class_at = source.index("public class")
        class_mapping = [ann for ann in annotations(source[:class_at]) if ann[0] == "RequestMapping"]
        prefixes = paths(class_mapping[-1][1]) if class_mapping else [""]
        source_hashes[str(file.relative_to(ROOT)).replace("\\", "/")] = hashlib.sha256(original.encode()).hexdigest()
        declared = 0
        for ann, args, start, end in annotations(source[class_at:]):
            if ann not in {"GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping"}: continue
            start += class_at; end += class_at; declared += 1
            sig = re.search(r"\bpublic\s+([\w<>?,.\[\]\s]+?)\s+(\w+)\s*\(", source[end:])
            if not sig: raise ValueError("Missing method signature: " + file.name)
            open_at = end + sig.end() - 1
            close_at = balanced(source, open_at)
            body_at = source.index("{", close_at)
            body_end = balanced(source, body_at, "{", "}")
            parameters = [parameter(raw) for raw in split_top(source[open_at + 1:close_at])]
            method_paths = paths(args)
            aliases = sorted({(prefix.rstrip("/") + "/" + suffix.lstrip("/")).rstrip("/") or "/" for prefix in prefixes for suffix in method_paths})
            canonical = next((path for path in aliases if path.startswith("/api/v1/")), aliases[0])
            multipart = any("MultipartFile" in param["javaType"] for param in parameters)
            if multipart:
                for param in parameters:
                    if param["location"] == "query": param["location"] = "form"
            endpoints.append({"id": f"API-{len(endpoints)+1:03}", "controller": file.stem, "handler": sig[2],
                "method": ann.replace("Mapping", "").upper(), "path": canonical, "aliases": [p for p in aliases if p != canonical],
                "parameters": parameters, "returnType": re.sub(r"\s+", " ", sig[1]),
                "requestContentType": "multipart/form-data" if multipart else "application/json" if any(p["location"] == "body" for p in parameters) else None,
                "source": str(file.relative_to(ROOT)).replace("\\", "/"), "sourceLine": original.count("\n", 0, start) + 1,
                "_body": source[body_at:body_end+1], "_source": source})
        actual = len(re.findall(r"@(Get|Post|Put|Patch|Delete)Mapping\b", source[class_at:]))
        if declared != actual: raise ValueError("Mapping coverage mismatch: " + file.name)
    return endpoints, source_hashes


if __name__ == "__main__":
    cli=argparse.ArgumentParser(description=__doc__)
    cli.add_argument('--inventory-only', action='store_true')
    cli.add_argument('--output', type=Path, help='Separate artifact directory, e.g. when the existing handbook is open in Word')
    args=cli.parse_args()
    if args.output:
        OUT = args.output.resolve()
    OUT.mkdir(parents=True, exist_ok=True)
    entries, hashes = inventory()
    if not args.inventory_only:
        from backend_api_reference_render import render
        entries=render(entries,ROOT,OUT)
    public = [{key: value for key, value in entry.items() if not key.startswith("_")} for entry in entries]
    (OUT / "backend-api-inventory.json").write_text(json.dumps({"source": "current Java controller source; not a live-response recording", "controllers": len(hashes), "logicalOperations": len(entries), "sourceHashes": hashes, "endpoints": public}, indent=2), encoding="utf-8")
    print(json.dumps({"controllers": len(hashes), "operations": len(entries), "pathsIncludingAliases": sum(1+len(e["aliases"]) for e in entries)}))
    if not args.inventory_only:
        from docx import Document
        from pypdf import PdfReader
        import zipfile
        import xml.etree.ElementTree as ET
        pairs=[(entry['method'],path) for entry in entries for path in [entry['path']]+entry['aliases']]
        if len(pairs)!=len(set(pairs)):raise ValueError('Duplicate method/path operation')
        with zipfile.ZipFile(OUT/'Backend_API_Handbook.docx') as package:
            word_text=' '.join(node.text or '' for node in ET.fromstring(package.read('word/document.xml')).iter() if node.tag.endswith('}t'))
        reader=PdfReader(OUT/'Backend_API_Handbook.pdf')
        pdf_text='\n'.join(page.extract_text() or '' for page in reader.pages)
        for entry in entries:
            if entry['id'] not in word_text or entry['id'] not in pdf_text:raise ValueError('Missing documented operation: '+entry['id'])
            if any(p['location']=='body' for p in entry['parameters']) and entry['requestExample'] is None:raise ValueError('Missing request example')
        collection=json.loads((OUT/'Network_Monitoring.postman_collection.json').read_text(encoding='utf-8'))
        postman_count=sum(len(group['item']) for group in collection['item'])
        if postman_count!=len(entries):raise ValueError('Postman coverage mismatch')
        for artifact in OUT.glob('*.json'):json.loads(artifact.read_text(encoding='utf-8'))
        validation={'controllers':len(hashes),'logicalOperations':len(entries),'expandedMethodPathCombinations':len(pairs),'jsonBodyOperations':sum(any(p['location']=='body' for p in e['parameters']) for e in entries),'postmanRequests':postman_count,'pdfPages':len(reader.pages),'liveOpenApiListedCanonicalOperations':sum(e['liveOpenApiListed'] is True for e in entries),'sourceOnlyCanonicalOperations':sum(e['liveOpenApiListed'] is False for e in entries),'runtimeUnverifiedOperations':sum(e['liveOpenApiListed'] is None for e in entries),'allOperationIdsPresentInDocxAndPdf':True,'jsonArtifactsParse':True,'businessRequestsExecuted':False,'credentialFilesRead':False,'generationDate':'2026-11-16'}
        (OUT/'documentation-validation.json').write_text(json.dumps(validation,indent=2),encoding='utf-8')
        print(json.dumps(validation))
