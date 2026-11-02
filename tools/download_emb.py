import json, os, struct, sys, time, urllib.request

UA = {"User-Agent": "Mozilla/5.0"}
DEST = r"C:\Users\conta\OneDrive\Documentos\deepseek-harness\default-workspace\tools\data"
REPO = "https://huggingface.co/nilc-nlp/word2vec-cbow-50d/resolve/main/"


def get(name, limit=None):
    path = os.path.join(DEST, name)
    if os.path.exists(path) and os.path.getsize(path) > 1000:
        print("cached", name, os.path.getsize(path), flush=True)
        return path
    req = urllib.request.Request(REPO + name, headers=UA)
    r = urllib.request.urlopen(req, timeout=120)
    total = int(r.headers.get("Content-Length") or 0)
    print(name, "size", round(total / 1e6, 1), "MB", flush=True)
    done = 0
    t = time.time()
    with open(path, "wb") as f:
        while True:
            b = r.read(1 << 20)
            if not b:
                break
            f.write(b)
            done += len(b)
            if total and done % (40 << 20) < (1 << 20):
                print("   %.0f%%" % (100 * done / total), flush=True)
    print("downloaded", name, os.path.getsize(path), "in", round(time.time() - t, 1), "s", flush=True)
    return path


for n in ("vocab.txt", "embeddings.safetensors"):
    get(n)
print("DONE")
