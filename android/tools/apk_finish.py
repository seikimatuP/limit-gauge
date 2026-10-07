#!/usr/bin/env python3
"""Finish an APK without the Android SDK build-tools.

Takes the resource APK produced by `aapt2 link` plus a classes.dex, then
  1. rewrites the ZIP with classes.dex added, resources.arsc stored uncompressed,
     and every stored entry 4-byte aligned (what zipalign does), and
  2. signs it with APK Signature Scheme v2 (RSA PKCS#1 v1.5 + SHA-256).

Usage: APK_STOREPASS=... apk_finish.py BASE_APK CLASSES_DEX KEYSTORE_P12 OUT_APK

The keystore password comes from the APK_STOREPASS environment variable, so it does not
appear in the process list. (The old form with STOREPASS as the 4th argument still works.)

Requires the `cryptography` package. The spec this implements:
https://source.android.com/docs/security/features/apksigning/v2
"""
import hashlib
import io
import os
import struct
import sys
import zipfile

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.serialization import pkcs12

ALIGNMENT = 4
ALIGNMENT_EXTRA_ID = 0xD935  # same extra-field id apksigner uses for alignment padding
FIXED_DATE = (1981, 1, 1, 1, 1, 2)  # reproducible timestamps

APK_SIG_BLOCK_MAGIC = b"APK Sig Block 42"
APK_SIGNATURE_SCHEME_V2_BLOCK_ID = 0x7109871A
SIG_RSA_PKCS1_V1_5_WITH_SHA2_256 = 0x0103
CHUNK_SIZE = 1024 * 1024


def build_aligned_zip(base_apk: str, dex_path: str) -> bytes:
    with open(dex_path, "rb") as f:
        dex = f.read()
    out = io.BytesIO()
    with zipfile.ZipFile(base_apk) as src, zipfile.ZipFile(out, "w") as dst:
        entries = [(i.filename, src.read(i.filename), i.compress_type) for i in src.infolist()]
        if any(name == "classes.dex" for name, _, _ in entries):
            raise SystemExit("base APK already contains classes.dex")
        entries.append(("classes.dex", dex, zipfile.ZIP_DEFLATED))
        for name, data, ctype in entries:
            if name == "resources.arsc":
                ctype = zipfile.ZIP_STORED  # required for targetSdk >= 30
            zi = zipfile.ZipInfo(name, date_time=FIXED_DATE)
            zi.compress_type = ctype
            zi.create_system = 0
            if ctype == zipfile.ZIP_STORED:
                header_start = out.tell()
                data_start = header_start + 30 + len(name.encode("utf-8")) + 6
                pad = (-data_start) % ALIGNMENT
                zi.extra = struct.pack("<HHH", ALIGNMENT_EXTRA_ID, 2 + pad, ALIGNMENT) + b"\0" * pad
            dst.writestr(zi, data)
    return out.getvalue()


def check_alignment(apk: bytes) -> None:
    with zipfile.ZipFile(io.BytesIO(apk)) as z:
        for info in z.infolist():
            if info.compress_type != zipfile.ZIP_STORED:
                continue
            off = info.header_offset
            name_len, extra_len = struct.unpack("<HH", apk[off + 26:off + 30])
            data_start = off + 30 + name_len + extra_len
            if data_start % ALIGNMENT:
                raise SystemExit(f"{info.filename} is not {ALIGNMENT}-byte aligned")


def find_eocd(apk: bytes) -> int:
    lowest = max(0, len(apk) - 22 - 0xFFFF)
    for i in range(len(apk) - 22, lowest - 1, -1):
        if apk[i:i + 4] == b"PK\x05\x06":
            comment_len = struct.unpack("<H", apk[i + 20:i + 22])[0]
            if i + 22 + comment_len == len(apk):
                return i
    raise SystemExit("end of central directory not found")


def lp(b: bytes) -> bytes:
    """uint32 little-endian length prefix."""
    return struct.pack("<I", len(b)) + b


def content_digest(sections) -> bytes:
    chunk_digests = []
    for section in sections:
        for off in range(0, len(section), CHUNK_SIZE):
            chunk = section[off:off + CHUNK_SIZE]
            chunk_digests.append(hashlib.sha256(b"\xa5" + struct.pack("<I", len(chunk)) + chunk).digest())
    top = hashlib.sha256(b"\x5a" + struct.pack("<I", len(chunk_digests)))
    for d in chunk_digests:
        top.update(d)
    return top.digest()


def sign_v2(apk: bytes, key, cert_der: bytes) -> bytes:
    eocd = find_eocd(apk)
    cd_size, cd_offset = struct.unpack("<II", apk[eocd + 12:eocd + 20])
    if cd_offset + cd_size != eocd:
        raise SystemExit("unexpected data between central directory and EOCD")
    entries, central_dir, eocd_record = apk[:cd_offset], apk[cd_offset:eocd], apk[eocd:]

    digest = content_digest([entries, central_dir, eocd_record])

    digests = lp(lp(struct.pack("<I", SIG_RSA_PKCS1_V1_5_WITH_SHA2_256) + lp(digest)))
    certificates = lp(lp(cert_der))
    additional_attributes = lp(b"")
    signed_data = digests + certificates + additional_attributes

    signature = key.sign(signed_data, padding.PKCS1v15(), hashes.SHA256())
    signatures = lp(lp(struct.pack("<I", SIG_RSA_PKCS1_V1_5_WITH_SHA2_256) + lp(signature)))
    public_key = lp(key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo))
    signer = lp(signed_data) + signatures + public_key
    v2_block_value = lp(lp(signer))

    pair = struct.pack("<Q", 4 + len(v2_block_value)) + struct.pack("<I", APK_SIGNATURE_SCHEME_V2_BLOCK_ID) + v2_block_value
    block_size = len(pair) + 8 + len(APK_SIG_BLOCK_MAGIC)
    signing_block = struct.pack("<Q", block_size) + pair + struct.pack("<Q", block_size) + APK_SIG_BLOCK_MAGIC

    new_eocd = bytearray(eocd_record)
    struct.pack_into("<I", new_eocd, 16, cd_offset + len(signing_block))
    return entries + signing_block + central_dir + bytes(new_eocd)


def main(argv):
    if len(argv) == 5:
        base_apk, dex_path, keystore, out_path = argv[1:]
        storepass = os.environ.get("APK_STOREPASS", "")
        if not storepass:
            raise SystemExit("set APK_STOREPASS to the keystore password")
    elif len(argv) == 6:
        base_apk, dex_path, keystore, storepass, out_path = argv[1:]
    else:
        raise SystemExit(__doc__)
    with open(keystore, "rb") as f:
        key, cert, _ = pkcs12.load_key_and_certificates(f.read(), storepass.encode())
    if key is None or cert is None:
        raise SystemExit("keystore has no private key/certificate")
    unsigned = build_aligned_zip(base_apk, dex_path)
    check_alignment(unsigned)
    signed = sign_v2(unsigned, key, cert.public_bytes(serialization.Encoding.DER))
    with open(out_path, "wb") as f:
        f.write(signed)
    print(f"wrote {out_path} ({len(signed)} bytes), cert SHA-256 {hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest()}")


if __name__ == "__main__":
    main(sys.argv)
