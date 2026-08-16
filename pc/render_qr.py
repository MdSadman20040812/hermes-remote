"""Render the Hermes Remote pairing payload as a terminal QR.

Called by hermes-remote.ps1. Uses the `qrcode` package from pc/vendor
(installed there so the Hermes venv stays untouched). Falls back to printing
the raw payload for manual entry.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent / "vendor"))


def main() -> int:
    payload = sys.argv[1] if len(sys.argv) > 1 else sys.stdin.read().strip()
    if not payload:
        print("no pairing payload given", file=sys.stderr)
        return 2
    try:
        import qrcode
    except ImportError:
        print("(qrcode package not vendored — payload printed for manual entry)")
        print(payload)
        return 0
    qr = qrcode.QRCode(border=1)
    qr.add_data(payload)
    qr.make()
    qr.print_ascii(invert=True)
    print("\nScan with Hermes Remote to pair. Manual payload:")
    print(payload)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
