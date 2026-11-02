"""Analyse candidate quarter-scale subnetworks of the FlyWire connectome."""
import sys, time
sys.path.insert(0, r"C:\Users\conta\OneDrive\Documentos\deepseek-harness\default-workspace\tools")
from parquet_read import ParquetFile, read_column
import numpy as np

NODES = r"C:\Users\conta\Downloads\conectoma\nodes.parquet"
CONN = r"C:\Users\conta\Downloads\conectoma\connections.parquet"

t0 = time.time()
pn = ParquetFile(NODES)
root = pn.read("root_id")
sc = np.array([x.decode() for x in pn.read("super_class")], dtype=object)
n = len(root)

order = np.argsort(root)
sorted_ids = root[order]


def to_idx(ids):
    pos = np.searchsorted(sorted_ids, ids)
    pos = np.clip(pos, 0, n - 1)
    ok = sorted_ids[pos] == ids
    out = np.where(ok, order[pos], -1)
    return out


pc = ParquetFile(CONN)
deg = np.zeros(n, dtype=np.int64)
edge_pre, edge_post = [], []
t = time.time()
for rg in range(len(pc.row_groups)):
    pre = to_idx(read_column(CONN, pc.columns["pre"][rg]))
    post = to_idx(read_column(CONN, pc.columns["post"][rg]))
    deg += np.bincount(pre, minlength=n)
    deg += np.bincount(post, minlength=n)
    edge_pre.append(pre.astype(np.int32))
    edge_post.append(post.astype(np.int32))
    print("rg", rg, "ok", round(time.time() - t, 1))
pre_all = np.concatenate(edge_pre)
post_all = np.concatenate(edge_post)
del edge_pre, edge_post
print("edges", len(pre_all), "neg", int((pre_all < 0).sum()), "degsum", deg.sum(), "elapsed", round(time.time() - t0, 1))

classes, counts = np.unique(sc, return_counts=True)
K = int(round(n / 4))
print("n", n, "K", K, "classes", dict(zip(classes.tolist(), counts.tolist())))


def induced(sel_mask):
    m = sel_mask[pre_all] & sel_mask[post_all]
    return int(m.sum())


# candidate A: global top-K by degree
order_deg = np.argsort(-deg)
selA = np.zeros(n, dtype=bool)
selA[order_deg[:K]] = True

# candidate B: stratified per super_class
selB = np.zeros(n, dtype=bool)
for c in classes:
    idx = np.where(sc == c)[0]
    k = max(1, int(round(len(idx) / 4)))
    sub = idx[np.argsort(-deg[idx])[:k]]
    selB[sub] = True

for name, sel in (("A_top_degree", selA), ("B_stratified", selB)):
    print(name, "nodes", int(sel.sum()), "induced", induced(sel))

np.save(r"C:\Users\conta\OneDrive\Documentos\deepseek-harness\default-workspace\tools\_deg.npy", deg)
np.save(r"C:\Users\conta\OneDrive\Documentos\deepseek-harness\default-workspace\tools\_selA.npy", selA)
np.save(r"C:\Users\conta\OneDrive\Documentos\deepseek-harness\default-workspace\tools\_selB.npy", selB)
print("elapsed total", round(time.time() - t0, 1))
