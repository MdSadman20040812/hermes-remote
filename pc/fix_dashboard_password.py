"""Re-sync the dashboard credential so the pairing QR actually works.

ROOT CAUSE (found 2026-09-09): D:\HermesMobile\pc\.dashboard-password did NOT
match config.yaml's scrypt password_hash. hermes-remote.ps1 builds the pairing
QR from that FILE, so every scan handed the phone a credential the server
rejects. The dashboard was reachable the whole time; the secret was stale.

This writes ONE password to both places so they cannot disagree:
  * plaintext -> .dashboard-password   (what the QR carries)
  * scrypt hash -> config.yaml         (what the server verifies against)

Usage:  python fix_dashboard_password.py [--password <pw>]
Default: generates a fresh 32-char URL-safe password.
"""
import argparse
import base64
import hashlib
import re
import secrets
import shutil
from datetime import datetime
from pathlib import Path

CFG = Path(r"D:\.hermes\config.yaml")
PWFILE = Path(r"D:\HermesMobile\pc\.dashboard-password")
N, R, P, DKLEN = 16384, 8, 1, 32

ap = argparse.ArgumentParser()
ap.add_argument("--password", default="")
args = ap.parse_args()

pw = args.password or secrets.token_urlsafe(24)[:32]

salt = secrets.token_bytes(16)
dk = hashlib.scrypt(pw.encode(), salt=salt, n=N, r=R, p=P, dklen=DKLEN)
newhash = "scrypt${}${}${}${}${}".format(
    N, R, P,
    base64.b64encode(salt).decode(),
    base64.b64encode(dk).decode(),
)

cfg = CFG.read_text(encoding="utf-8")
if not re.search(r"password_hash:\s*\S+", cfg):
    raise SystemExit("no password_hash line in config.yaml - aborting")

bak = CFG.with_suffix(".yaml.bak.pairfix-" + datetime.now().strftime("%Y%m%d_%H%M%S"))
shutil.copy2(CFG, bak)

cfg2 = re.sub(r"password_hash:\s*\S+", "password_hash: " + newhash, cfg, count=1)
CFG.write_text(cfg2, encoding="utf-8")
PWFILE.write_text(pw, encoding="utf-8")

# prove it round-trips
stored = re.search(r"password_hash:\s*(\S+)", CFG.read_text(encoding="utf-8")).group(1)
algo, n, r, p, s_b64, h_b64 = stored.split("$")
check = hashlib.scrypt(PWFILE.read_text(encoding="utf-8").strip().encode(),
                       salt=base64.b64decode(s_b64), n=int(n), r=int(r), p=int(p),
                       dklen=len(base64.b64decode(h_b64)))

print(f"backup:   {bak.name}")
print(f"username: sadman")
print(f"password: {pw}")
print()
print("VERIFIED - file and config now agree"
      if check == base64.b64decode(h_b64) else "STILL MISMATCHED - do not pair")
