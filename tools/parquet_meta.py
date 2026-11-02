import struct, sys, json
sys.path.insert(0, r"C:\Users\conta\OneDrive\Documentos\deepseek-harness\default-workspace\tools")
from thrift_compact import TB, read_struct

CODEC = {0: "UNCOMPRESSED", 1: "SNAPPY", 2: "GZIP", 3: "LZO", 4: "BROTLI", 5: "LZ4", 6: "ZSTD", 7: "LZ4_RAW"}
PTYPE = {0: "BOOLEAN", 1: "INT32", 2: "INT64", 3: "INT96", 4: "FLOAT", 5: "DOUBLE", 6: "BYTE_ARRAY", 7: "FLBA"}
ENC = {0: "PLAIN", 2: "PLAIN_DICT", 3: "RLE", 4: "BIT_PACKED", 5: "DELTA_BIN", 6: "DELTA_LEN", 7: "DELTA_BYTE_ARRAY", 8: "RLE_DICT", 9: "BYTE_STREAM_SPLIT"}


def footer(path):
    with open(path, "rb") as f:
        f.seek(-8, 2)
        n = struct.unpack("<I", f.read(4))[0]
        f.seek(-(8 + n), 2)
        return f.read(n)


def dump(path):
    md = read_struct(TB(footer(path)))
    print("=" * 30, path.split("\\")[-1])
    print("version", md.get(1), "num_rows", md.get(3), "created_by", md.get(6))
    print("-- schema --")
    for se in md[2]:
        print("  name=%s type=%s rep=%s nchild=%s conv=%s logical=%s" % (
            se.get(4), PTYPE.get(se.get(1)), se.get(3), se.get(5), se.get(6), (se.get(10) or {}).keys()))
    print("-- row groups --", len(md[4]))
    rg = md[4][0]
    print("  rg0 rows", rg.get(3), "bytes", rg.get(2))
    for cc in rg[1]:
        cm = cc[3]
        print("   col %-24s type=%-10s codec=%-12s enc=%-28s n=%d dict_off=%s data_off=%s comp=%d uncomp=%d" % (
            ".".join(x.decode() for x in cm[3]), PTYPE.get(cm.get(1)), CODEC.get(cm.get(4)),
            ",".join(ENC.get(e, str(e)) for e in cm.get(2, [])), cm.get(5), cm.get(11), cm.get(9),
            cm.get(7), cm.get(6)))
    tot = sum(cc[3].get(7) for rg in md[4] for cc in rg[1])
    print("  total compressed column bytes:", tot)


for p in sys.argv[1:]:
    dump(p)
