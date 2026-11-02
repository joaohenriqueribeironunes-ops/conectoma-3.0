"""Convert NILC safetensors embeddings to a plain .npy aligned with vocab.txt."""
import json, os, struct, time

import numpy as np

DEST = r"C:\Users\conta\OneDrive\Documentos\deepseek-harness\default-workspace\tools\data"
ST = os.path.join(DEST, "embeddings.safetensors")
VOCAB = os.path.join(DEST, "vocab.txt")

with open(ST, "rb") as f:
    n = struct.unpack("<Q", f.read(8))[0]
    header = json.loads(f.read(n).decode("utf-8"))
    print("header keys", list(header.keys())[:4])
    key = [k for k in header if k != "__metadata__"][0]
    meta = header[key]
    print("tensor", key, meta["dtype"], meta["shape"])
    start, end = meta["data_offsets"]
    f.seek(8 + n + start)
    raw = f.read(end - start)

arr = np.frombuffer(raw, dtype="<f4").reshape(meta["shape"])
print("array", arr.shape, arr.dtype, arr.nbytes / 1e6, "MB")

with open(VOCAB, encoding="utf-8") as f:
    vocab = [line.strip() for line in f]
print("vocab", len(vocab), vocab[:5])

if len(vocab) != arr.shape[0]:
    print("MISMATCH", len(vocab), arr.shape[0])

np.save(os.path.join(DEST, "nilc_emb.npy"), arr.astype(np.float32))

# coverage against our lexicon
our = []
for line in open(os.path.join(DEST, "lexicon.tsv"), encoding="utf-8"):
    our.append(line.split("\t")[0])
idx = {w: i for i, w in enumerate(vocab)}
hit = sum(1 for w in our if w in idx)
print("lexicon words", len(our), "covered", hit, "=", round(100 * hit / len(our), 1), "%")
miss = [w for w in our if w not in idx][:15]
print("missing sample", miss)
