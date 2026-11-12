# ZXing 3.5.4 QR encoder subset

Upstream: https://github.com/zxing/zxing/tree/zxing-3.5.4/core
License: Apache-2.0; full upstream LICENSE (including notices) retained in LICENSE.txt.
Only namespace relocation is applied to original complete Java source files; original headers remain.
No barcode-format or decoding implementation is selected unless referenced by the encoder closure.

Reproduce/check with Python3 and the exact official Maven source/binary inputs (never executed):

    python3 tools/vendor_zxing_qr.py --sources /path/core-3.5.4-sources.jar --core /path/core-3.5.4.jar --destination third_party/zxing-qr-only --check

Omit --check only to generate an empty destination. Existing differing files are never overwritten.
The tool pins both input SHA-256 values and computes the transitive ZXing class references rooted at
QRCodeWriter, maps them to complete source files, and applies the one package-name relocation.
The checked-in manifest pins each original and resulting source. Ordinary/offline builds use these
sources directly; the complete upstream binary is not a runtime/production dependency.
Full core 3.5.4 is used solely by tests for an independent QR reader path.
