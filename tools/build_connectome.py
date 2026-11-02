"""Build the on-device connectome asset from the FlyWire parquet files.

Output (app/src/main/assets):
  connectome.bin : CSR graph, 7 bytes per synapse
  nodes.tsv      : node metadata, one line per neuron
  conectoma_stats.json
"""
import json
import os
import struct
import sys
import time

import numpy as np

sys.path.insert(0, r"C:\Users\conta\OneDrive\Documentos\deepseek-harness\default-workspace\tools")
from parquet_read import ParquetFile, read_column

NODES = r"C:\Users\conta\Downloads\conectoma\nodes.parquet"
CONN = r"C:\Users\conta\Downloads\conectoma\connections.parquet"
ROOT = r"C:\Users\conta\OneDrive\Documentos\deepseek-harness\default-workspace"
OUT = os.path.join(ROOT, "conectoma", "app", "src", "main", "assets")
TARGET_EDGES = 3_750_000
TARGET_FRACTION = 0.25

os.makedirs(OUT, exist_ok=True)
t0 = time.time()

# ---------------------------------------------------------------- nodes
pn = ParquetFile(NODES)
root = pn.read("root_id")
n = len(root)
cat = {k: np.array([x.decode() for x in pn.read(k)], dtype=object)
       for k in ("super_class", "cell_class", "cell_type", "top_nt", "side", "flow")}
conf = pn.read("top_nt_conf").astype(np.float32)
print("nodes", n, "elapsed", round(time.time() - t0, 1))

# ---------------------------------------------------------------- degrees
pc = ParquetFile(CONN)
order = np.argsort(root)
sorted_ids = root[order]


def to_idx(ids):
    pos = np.clip(np.searchsorted(sorted_ids, ids), 0, n - 1)
    ok = sorted_ids[pos] == ids
    return np.where(ok, order[pos], -1)


deg = np.zeros(n, dtype=np.int64)
t = time.time()
for rg in range(len(pc.row_groups)):
    pre = to_idx(read_column(CONN, pc.columns["pre"][rg]))
    post = to_idx(read_column(CONN, pc.columns["post"][rg]))
    deg += np.bincount(pre, minlength=n)
    deg += np.bincount(post, minlength=n)
print("degrees", round(time.time() - t, 1))

# ---------------------------------------------------------------- selection
classes, counts = np.unique(cat["super_class"], return_counts=True)
K = int(round(n * TARGET_FRACTION))
sel = np.zeros(n, dtype=bool)
for c in classes:
    idx = np.where(cat["super_class"] == c)[0]
    k = max(1, int(round(len(idx) * TARGET_FRACTION)))
    sel[idx[np.argsort(-deg[idx])[:k]]] = True
sel_idx = np.where(sel)[0]
nsel = len(sel_idx)
print("selected", nsel)

remap = np.full(n, -1, dtype=np.int32)
remap[sel_idx] = np.arange(nsel, dtype=np.int32)

# ---------------------------------------------------------------- edges
pre_l, post_l, syn_l, nt_l = [], [], [], []
t = time.time()
for rg in range(len(pc.row_groups)):
    pre = to_idx(read_column(CONN, pc.columns["pre"][rg]))
    post = to_idx(read_column(CONN, pc.columns["post"][rg]))
    keep = (remap[pre] >= 0) & (remap[post] >= 0)
    if not keep.any():
        continue
    a = remap[pre[keep]].astype(np.uint16)
    b = remap[post[keep]].astype(np.uint16)
    s = read_column(CONN, pc.columns["syn_count"][rg])[keep].astype(np.int32)
    nts = [read_column(CONN, pc.columns[k][rg])[keep].astype(np.float32)
           for k in ("da", "gaba", "ach", "glut")]
    pre_l.append(a)
    post_l.append(b)
    syn_l.append(s)
    nt_l.append(np.stack(nts, axis=1))
    print("  rg", rg, "kept", int(keep.sum()), "t", round(time.time() - t, 1))

pre_a = np.concatenate(pre_l)
post_a = np.concatenate(post_l)
syn_a = np.concatenate(syn_l)
nt_a = np.concatenate(nt_l)
del pre_l, post_l, syn_l, nt_l
print("induced edges", len(pre_a))

if len(pre_a) > TARGET_EDGES:
    keep = np.argpartition(-syn_a, TARGET_EDGES)[:TARGET_EDGES]
    keep.sort()
    pre_a, post_a, syn_a, nt_a = pre_a[keep], post_a[keep], syn_a[keep], nt_a[keep]
    print("trimmed to", len(pre_a), "min syn", int(syn_a.min()))

# quantise
syn_q = np.clip(np.round(255.0 * np.log1p(syn_a) / np.log1p(1300.0)), 1, 255).astype(np.uint8)
nt_q = np.clip(np.round(np.nan_to_num(nt_a) * 255.0), 0, 255).astype(np.uint8)

# ---------------------------------------------------------------- CSR
pre_o = np.argsort(pre_a, kind="stable").astype(np.int64)
post_a, syn_a, syn_q, nt_q = post_a[pre_o], syn_a[pre_o], syn_q[pre_o], nt_q[pre_o]
pre_sorted = pre_a[pre_o]
offsets = np.zeros(nsel + 1, dtype=np.uint32)
np.add.at(offsets, pre_sorted + 1, 1)
offsets = np.cumsum(offsets).astype(np.uint32)
assert offsets[-1] == len(post_a)

with open(os.path.join(OUT, "connectome.bin"), "wb") as f:
    f.write(b"CNCT")
    f.write(struct.pack("<III", 1, nsel, len(post_a)))
    f.write(offsets.tobytes())
    f.write(post_a.astype("<u2").tobytes())
    f.write(syn_q.tobytes())
    f.write(nt_q[:, 0].tobytes())
    f.write(nt_q[:, 1].tobytes())
    f.write(nt_q[:, 2].tobytes())
    f.write(nt_q[:, 3].tobytes())

# ---------------------------------------------------------------- nodes.tsv
with open(os.path.join(OUT, "nodes.tsv"), "w", encoding="utf-8", newline="\n") as f:
    f.write("idx\troot_id\tsuper_class\tcell_class\tcell_type\ttop_nt\tside\tflow\tconf\n")
    for i, gi in enumerate(sel_idx):
        f.write("%d\t%d\t%s\t%s\t%s\t%s\t%s\t%s\t%.4f\n" % (
            i, root[gi], cat["super_class"][gi], cat["cell_class"][gi], cat["cell_type"][gi],
            cat["top_nt"][gi], cat["side"][gi], cat["flow"][gi], conf[gi]))

stats = {
    "source": "FlyWire FAFB v783 (Dorkenwald et al. 2024; Schlegel et al. 2024)",
    "neurons_total": int(n),
    "neurons_selected": int(nsel),
    "neurons_fraction": round(nsel / n, 4),
    "synapses_selected": int(len(post_a)),
    "synapses_total_pairs": 15_091_983,
    "synapses_fraction": round(len(post_a) / 15_091_983, 4),
    "syn_threshold_used": int(syn_a.min()),
    "mean_out_degree": round(float(len(post_a) / nsel), 2),
    "class_counts": {c: int((cat["super_class"][sel_idx] == c).sum()) for c in classes},
    "dopaminergic_fraction": round(float((nt_q[:, 0] > 25).mean()), 4),
    "gabaergic_fraction": round(float((nt_q[:, 1] > 128).mean()), 4),
    "glutamatergic_fraction": round(float((nt_q[:, 3] > 128).mean()), 4),
    "cholinergic_fraction": round(float((nt_q[:, 2] > 128).mean()), 4),
}
with open(os.path.join(OUT, "conectoma_stats.json"), "w", encoding="utf-8") as f:
    json.dump(stats, f, indent=2, ensure_ascii=False)
print(json.dumps(stats, indent=2, ensure_ascii=False))
print("size MB", round(os.path.getsize(os.path.join(OUT, "connectome.bin")) / 1e6, 2),
      "elapsed", round(time.time() - t0, 1))
