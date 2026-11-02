"""Minimal Parquet reader sufficient for the FlyWire export.

Supports: PLAIN / RLE_DICTIONARY / RLE encodings, ZSTD + UNCOMPRESSED + GZIP codecs,
optional (def-level 1) flat columns, DATA_PAGE and DATA_PAGE_V2.
"""
import ctypes
import gzip
import os
import struct
import zlib

import numpy as np

from thrift_compact import TB, read_struct

ZSTD_DLL = r"C:\Program Files\Git\mingw64\bin\libzstd.dll"
_lib = None


def _zstd():
    global _lib
    if _lib is None:
        lib = ctypes.CDLL(ZSTD_DLL)
        lib.ZSTD_decompress.restype = ctypes.c_size_t
        lib.ZSTD_decompress.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_void_p, ctypes.c_size_t]
        lib.ZSTD_isError.restype = ctypes.c_uint
        lib.ZSTD_isError.argtypes = [ctypes.c_size_t]
        _lib = lib
    return _lib


def zstd_decompress(src: bytes, size: int) -> bytes:
    lib = _zstd()
    dst = ctypes.create_string_buffer(size)
    code = lib.ZSTD_decompress(dst, size, src, len(src))
    if lib.ZSTD_isError(code):
        raise RuntimeError("zstd error %d" % code)
    return dst.raw[:code]


def decompress(codec: int, data: bytes, size: int) -> bytes:
    if codec == 0:
        return data
    if codec == 1:  # snappy (unused here)
        return snappy_decompress(data)
    if codec == 2:
        return zlib.decompress(data)
    if codec == 6:
        return zstd_decompress(data, size)
    raise ValueError("unsupported codec %d" % codec)


# ---------------------------------------------------------------- page headers
PAGE_DATA, PAGE_INDEX, PAGE_DICT, PAGE_DATA_V2 = 0, 1, 2, 3
ENC_PLAIN, ENC_PLAIN_DICT, ENC_RLE, ENC_BITPACKED, ENC_DELTA_BIN, ENC_DELTA_LEN, ENC_DELTA_BA, ENC_RLE_DICT = 0, 2, 3, 4, 5, 6, 7, 8


def _read_varint(buf, pos):
    shift = 0
    out = 0
    while True:
        b = buf[pos]
        pos += 1
        out |= (b & 0x7F) << shift
        if not b & 0x80:
            return out, pos
        shift += 7


def _unpack_bits(data: bytes, bit_width: int, count: int) -> np.ndarray:
    """LSB-first bit unpacking of `count` values of `bit_width` bits."""
    if bit_width == 0:
        return np.zeros(count, dtype=np.int64)
    nbytes = (count * bit_width + 7) // 8
    raw = np.frombuffer(data[:nbytes], dtype=np.uint8)
    bits = np.unpackbits(raw, bitorder="little")
    total = len(bits) // bit_width * bit_width
    bits = bits[:total].reshape(-1, bit_width).astype(np.int64)
    weights = (1 << np.arange(bit_width, dtype=np.int64))
    vals = bits @ weights
    return vals[:count]


def decode_hybrid(buf, pos, bit_width, num_values):
    out = np.empty(num_values, dtype=np.int64)
    i = 0
    while i < num_values:
        header, pos = _read_varint(buf, pos)
        if header & 1:
            groups = header >> 1
            count = groups * 8
            nbytes = groups * bit_width
            vals = _unpack_bits(buf[pos:pos + nbytes], bit_width, count)
            pos += nbytes
            take = min(count, num_values - i)
            out[i:i + take] = vals[:take]
            i += take
        else:
            count = header >> 1
            nbytes = (bit_width + 7) // 8
            val = int.from_bytes(buf[pos:pos + nbytes], "little") if nbytes else 0
            pos += nbytes
            take = min(count, num_values - i)
            out[i:i + take] = val
            i += take
    return out, pos


# ---------------------------------------------------------------- column decode
class Column:
    def __init__(self, meta, path, max_def=1, max_rep=0):
        self.meta = meta
        self.path = path
        self.max_def = max_def
        self.max_rep = max_rep

    @property
    def codec(self):
        return self.meta[4]

    @property
    def num_values(self):
        return self.meta[5]

    @property
    def phys_type(self):
        return self.meta[1]

    @property
    def start(self):
        cands = [self.meta.get(k) for k in (9, 11)]
        cands = [c for c in cands if c and c > 0]
        return min(cands)

    @property
    def total_compressed(self):
        return self.meta[7]


def _bit_width(max_level):
    if max_level <= 0:
        return 0
    return max_level.bit_length()


def _decode_plain(buf, phys, count, type_length=None):
    if phys == 1:  # INT32
        return np.frombuffer(buf[:count * 4], dtype="<i4").astype(np.int64)
    if phys == 2:  # INT64
        return np.frombuffer(buf[:count * 8], dtype="<i8")
    if phys == 4:  # FLOAT
        return np.frombuffer(buf[:count * 4], dtype="<f4")
    if phys == 5:  # DOUBLE
        return np.frombuffer(buf[:count * 8], dtype="<f8")
    if phys == 6:  # BYTE_ARRAY
        out = []
        pos = 0
        for _ in range(count):
            n = struct.unpack_from("<I", buf, pos)[0]
            pos += 4
            out.append(buf[pos:pos + n])
            pos += n
        arr = np.empty(len(out), dtype=object)
        arr[:] = out
        return arr
    raise ValueError("plain phys %d" % phys)


def read_column(path: str, col: Column) -> np.ndarray:
    with open(path, "rb") as f:
        f.seek(col.start)
        blob = f.read(col.total_compressed)
    pos = 0
    dict_vals = None
    chunks = []
    got = 0
    total = col.num_values
    while got < total and pos < len(blob):
        tb = TB(blob, pos)
        hdr = read_struct(tb)
        pos = tb.pos
        ptype = hdr[1]
        uncomp = hdr[2]
        comp = hdr[3]
        body = blob[pos:pos + comp]
        pos += comp

        if ptype == PAGE_DICT:
            raw = decompress(col.codec, body, uncomp)
            n = hdr[7][1]
            dict_vals = _decode_plain(raw, col.phys_type, n)
            continue
        if ptype == PAGE_DATA:
            raw = decompress(col.codec, body, uncomp)
            dh = hdr[5]
            n = dh[1]
            enc = dh[2]
            dpos = 0
            defs = None
            if col.max_rep > 0:
                nrep = struct.unpack_from("<I", raw, dpos)[0]
                dpos += 4 + nrep
            if col.max_def > 0:
                ndef = struct.unpack_from("<I", raw, dpos)[0]
                dpos += 4
                if ndef > 0:
                    defs, _ = decode_hybrid(raw, dpos, _bit_width(col.max_def), n)
                dpos += ndef
            nnon = n if defs is None else int((defs == col.max_def).sum())
            vals = _decode_values(raw[dpos:], enc, col.phys_type, nnon, dict_vals)
            if defs is not None and nnon != n:
                vals = _scatter(vals, defs == col.max_def, n)
        elif ptype == PAGE_DATA_V2:
            dh = hdr[8]
            n = dh[1]
            enc = dh[4]
            def_len = dh[5]
            rep_len = dh[6]
            is_comp = dh[7] if 7 in dh else True
            rep = body[:rep_len]
            defs_raw = body[rep_len:rep_len + def_len]
            val_raw = body[rep_len + def_len:]
            if is_comp:
                val_raw = decompress(col.codec, val_raw, uncomp - rep_len - def_len)
            defs = None
            if def_len > 0:
                defs, _ = decode_hybrid(defs_raw, 0, _bit_width(col.max_def), n)
            nnon = n if defs is None else int((defs == col.max_def).sum())
            vals = _decode_values(val_raw, enc, col.phys_type, nnon, dict_vals)
            if defs is not None and nnon != n:
                vals = _scatter(vals, defs == col.max_def, n)
        else:
            continue
        chunks.append(vals)
        got += n
    if not chunks:
        return np.array([])
    if col.phys_type == 6:
        return [v for c in chunks for v in c]
    return np.concatenate(chunks)


def _scatter(vals, present_mask, n):
    """Re-insert nulls so the result stays aligned with the row index."""
    if vals.dtype == object:
        out = np.empty(n, dtype=object)
        out[:] = b""
    else:
        out = np.full(n, np.nan, dtype=np.float64) if vals.dtype.kind == "f" else np.full(n, -1, dtype=np.int64)
        out = out.astype(vals.dtype) if vals.dtype.kind != "f" else out
    out[present_mask] = vals
    return out


def _decode_values(buf, enc, phys, count, dict_vals):
    if enc == ENC_PLAIN:
        return _decode_plain(buf, phys, count)
    if enc in (ENC_RLE_DICT, ENC_PLAIN_DICT):
        bw = buf[0]
        idx, _ = decode_hybrid(buf, 1, bw, count)
        return dict_vals[idx]
    raise ValueError("unsupported encoding %d" % enc)


# ---------------------------------------------------------------- file access
CODEC_NAMES = {0: "uncompressed", 1: "snappy", 2: "gzip", 6: "zstd"}


class ParquetFile:
    def __init__(self, path):
        self.path = path
        self.size = os.path.getsize(path)
        with open(path, "rb") as f:
            f.seek(-8, 2)
            n = struct.unpack("<I", f.read(4))[0]
            f.seek(-(8 + n), 2)
            self.meta = read_struct(TB(f.read(n)))
        self.nrows = self.meta[3]
        self.schema = self.meta[2]
        self.row_groups = self.meta[4]
        self.columns = self._columns()

    def _columns(self):
        leaves = {}
        for se in self.schema[1:]:
            leaves[se[4].decode()] = se
        out = {}
        for rg_i, rg in enumerate(self.row_groups):
            for cc in rg[1]:
                cm = cc[3]
                name = ".".join(x.decode() for x in cm[3])
                se = leaves.get(name)
                max_def = 1 if (se is not None and se.get(3) == 1) else 0
                out.setdefault(name, []).append(Column(cm, self.path, max_def, 0))
        return out

    def read(self, name):
        parts = [read_column(self.path, c) for c in self.columns[name]]
        if isinstance(parts[0], list):
            return [x for p in parts for x in p]
        return np.concatenate(parts)


def snappy_decompress(data):
    # minimal snappy block decompressor (raw format)
    pos = 0
    length = 0
    shift = 0
    while True:
        b = data[pos]
        pos += 1
        length |= (b & 0x7F) << shift
        if not b & 0x80:
            break
        shift += 7
    out = bytearray()
    while pos < len(data):
        tag = data[pos]
        pos += 1
        t = tag & 3
        if t == 0:
            n = tag >> 2
            if n < 60:
                ln = n + 1
            else:
                extra = n - 59
                ln = int.from_bytes(data[pos:pos + extra], "little") + 1
                pos += extra
            out += data[pos:pos + ln]
            pos += ln
        else:
            if t == 1:
                ln = 4 + ((tag >> 2) & 7)
                off = ((tag >> 5) << 8) | data[pos]
                pos += 1
            elif t == 2:
                ln = 1 + (tag >> 2)
                off = struct.unpack_from("<H", data, pos)[0]
                pos += 2
            else:
                ln = 1 + (tag >> 2)
                off = struct.unpack_from("<I", data, pos)[0]
                pos += 4
            start = len(out) - off
            for i in range(ln):
                out.append(out[start + i])
    return bytes(out[:length])
