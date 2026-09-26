"""Check every mixin injection point against a Hytale server jar.

Reads the mixin sources for @Mixin targets, @Redirect/@WrapMethod/@Shadow members and
their @At INVOKE targets, then looks each one up in the jar bytecode with javap.
A green build does not prove these still match: descriptor changes often compile
fine and only fail when the server boots.

usage: check_mixins.py <server.jar> <mixin source dir> [javap]
Normally run through `gradle checkMixinTargets`.
"""
import os, re, shutil, subprocess, sys

JAR, SRC = sys.argv[1], sys.argv[2]
JAVAP = sys.argv[3] if len(sys.argv) > 3 else shutil.which("javap")
if not JAVAP:
    sys.exit("javap not found, pass it as the third argument")

cache = {}
def load(cls):
    if cls in cache: return cache[cls]
    out = subprocess.run([JAVAP, "-c", "-p", "-s", "-cp", JAR, cls.replace("/", ".")], capture_output=True, text=True)
    if out.returncode != 0:
        cache[cls] = None; return None
    res = {}; cur = None; pending = None
    for line in out.stdout.splitlines():
        s = line.strip()
        if s.startswith("descriptor:") and pending:
            cur = (pending, s.split(":", 1)[1].strip()); res[cur] = []; pending = None; continue
        if line.startswith("  ") and not line.startswith("   ") and "(" in s and s.endswith(";"):
            pending = re.search(r"([\w$<>]+)\(", s).group(1); continue
        mi = re.search(r"invoke\w+\s+#\d+(?:,\s*\d+)?\s+// (?:Interface)?Method (.+)$", s)
        if mi and cur:
            res[cur].append(mi.group(1).replace('"', ''))
    cache[cls] = res
    return res

def parse(path):
    src = open(path, encoding="utf-8").read()
    imports = dict((m.group(2), m.group(1)) for m in re.finditer(r"^import ([\w.]+\.(\w+));", src, re.M))
    m = re.search(r'@Mixin\(\s*targets\s*=\s*"([^"]+)"', src)
    if m:
        target = m.group(1).replace(".", "/")
    else:
        m = re.search(r"@Mixin\(\s*([\w.]+)\.class", src)
        parts = m.group(1).split(".")
        outer = imports.get(parts[0], parts[0])
        target = (outer + "".join("$" + p for p in parts[1:])).replace(".", "/")
    specs = []
    for a in re.finditer(r"@(Redirect|WrapMethod)\((.*?)\)\s*(?:private|public|protected)", src, re.S):
        kind, body = a.group(1), a.group(2)
        method = re.search(r'method\s*=\s*"([^"]+)"', body).group(1)
        t = re.search(r'target\s*=\s*"([^"]+)"', body)
        ordm = re.search(r"ordinal\s*=\s*(\d+)", body)
        req = not re.search(r"require\s*=\s*0", body)
        specs.append((kind, method, t.group(1) if t else None, int(ordm.group(1)) if ordm else None, req))
    for sh in re.finditer(r"@Shadow\s+(?:[\w<>?,\s]+\s)?(\w+)\s*\(", src):
        specs.append(("Shadow", sh.group(1), None, None, True))
    return target, specs

def norm(t):  # Lowner;name(desc) -> owner.name:desc
    m = re.match(r"L([^;]+);([\w$<>]+)(\(.*)$", t)
    return f"{m.group(1)}.{m.group(2)}:{m.group(3)}"

bad = 0
for f in sorted(os.listdir(SRC)):
    if not f.endswith(".java"): continue
    target, specs = parse(os.path.join(SRC, f))
    ms = load(target)
    if ms is None:
        print(f"FAIL {f}: target class {target} missing"); bad += 1; continue
    for kind, method, tgt, ordinal, req in specs:
        if ":" in method or "(" in method:
            n, d = (method.split("(", 1)[0], "(" + method.split("(", 1)[1]) if "(" in method else method.split(":", 1)
            cands = [k for k in ms if k == (n, d)]
        else:
            cands = [k for k in ms if k[0] == method]
        label = f"{f[:-5]} {kind} {method.split('(')[0]}"
        if not cands:
            print(f"FAIL {label}: method not in {target}"); bad += 1; continue
        if tgt is None:
            note = "" if len(cands) == 1 or kind == "Shadow" else f" ({len(cands)} overloads)"
            print(f"ok   {label}{note}"); continue
        want = norm(tgt); owner, rest = want.split(".", 1)
        hits = sum(1 for c in cands for t in ms[c] if t == want or (owner == target and t == rest))
        sev = "FAIL" if req else "warn"
        if hits == 0:
            print(f"{sev} {label} -> {rest[:90]} : no call site"); bad += req
        elif ordinal is not None and ordinal >= hits:
            print(f"{sev} {label} ordinal {ordinal} but only {hits} call sites"); bad += req
        else:
            print(f"ok   {label} -> {rest.split(':')[0]} ({hits} site{'s' if hits > 1 else ''}{', ordinal ' + str(ordinal) if ordinal is not None else ''})")
print(f"\nfatal problems: {bad}")
sys.exit(1 if bad else 0)
