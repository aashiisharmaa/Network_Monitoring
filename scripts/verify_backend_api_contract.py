"""Compare HTTP method/path/parameter contracts against the pre-structure baseline."""
import json
import re
from pathlib import Path
from build_backend_api_documentation import ROOT, inventory


def contracts(entries):
    return [{"method": e["method"], "paths": sorted([e["path"], *e["aliases"]]),
             "parameters": [{"name": p["name"], "location": p["location"],
                             "required": p["required"], "default": p["default"],
                             "type": re.sub(r"\s+", "", re.sub(r"(?:\w+\.)+", "", p["javaType"]))}
                            for p in e["parameters"] if p["location"] != "server-context"]} for e in entries]


if __name__ == "__main__":
    baseline = json.loads((ROOT / "backend-java/src/test/resources/alarm/http-contract-baseline.json").read_text())
    additions = ROOT / "backend-java/src/test/resources/alarm/operational-api-contracts.json"
    if additions.exists():
        baseline += json.loads(additions.read_text())
    current = contracts(inventory()[0])
    key = lambda item: (item["method"], item["paths"])
    if sorted(current, key=key) != sorted(baseline, key=key):
        before = {json.dumps(e, sort_keys=True) for e in baseline}
        after = {json.dumps(e, sort_keys=True) for e in current}
        raise SystemExit("HTTP contract changed:\nRemoved/changed: " + str(sorted(before-after)) +
                         "\nAdded/changed: " + str(sorted(after-before)))
    print(f"PASS: {len(current)} HTTP method/path/parameter contracts preserved.")
