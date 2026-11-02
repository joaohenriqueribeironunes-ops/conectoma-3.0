"""Minimal Thrift Compact Protocol reader (enough for Parquet metadata)."""
import struct


class TB:
    """Reader over bytes with a position."""
    __slots__ = ("buf", "pos")

    def __init__(self, buf, pos=0):
        self.buf = buf
        self.pos = pos

    def byte(self):
        b = self.buf[self.pos]
        self.pos += 1
        return b

    def varint(self):
        shift = 0
        result = 0
        while True:
            b = self.buf[self.pos]
            self.pos += 1
            result |= (b & 0x7F) << shift
            if not (b & 0x80):
                return result
            shift += 7
            if shift > 70:
                raise ValueError("varint too long")

    def zigzag(self):
        n = self.varint()
        return (n >> 1) ^ -(n & 1)

    def binary(self):
        n = self.varint()
        b = self.buf[self.pos:self.pos + n]
        self.pos += n
        return b

    def double(self):
        v = struct.unpack_from("<d", self.buf, self.pos)[0]
        self.pos += 8
        return v


# compact protocol type ids
BOOLEAN_TRUE, BOOLEAN_FALSE, BYTE, I16, I32, I64, DOUBLE, BINARY, LIST, SET, MAP, STRUCT = range(1, 13)


def read_struct(tb):
    """Return dict field_id -> value. Handles nested structs/lists/maps."""
    fields = {}
    last = 0
    while True:
        h = tb.byte()
        if h == 0:
            return fields
        delta = (h >> 4) & 0x0F
        tid = h & 0x0F
        if delta == 0:
            fid = tb.zigzag()
        else:
            fid = last + delta
        last = fid
        fields[fid] = read_value(tb, tid)


def read_value(tb, tid):
    if tid == BOOLEAN_TRUE:
        return True
    if tid == BOOLEAN_FALSE:
        return False
    if tid == BYTE:
        v = tb.byte()
        return v - 256 if v > 127 else v
    if tid in (I16, I32, I64):
        return tb.zigzag()
    if tid == DOUBLE:
        return tb.double()
    if tid == BINARY:
        return tb.binary()
    if tid in (LIST, SET):
        h = tb.byte()
        size = (h >> 4) & 0x0F
        et = h & 0x0F
        if size == 15:
            size = tb.varint()
        return [read_value(tb, et) for _ in range(size)]
    if tid == MAP:
        size = tb.varint()
        if size == 0:
            return {}
        h = tb.byte()
        kt = (h >> 4) & 0x0F
        vt = h & 0x0F
        return {read_value(tb, kt): read_value(tb, vt) for _ in range(size)}
    if tid == STRUCT:
        return read_struct(tb)
    raise ValueError("bad thrift type %d at %d" % (tid, tb.pos))
