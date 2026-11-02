import os, time, urllib.request

DEST = r"C:\Users\conta\OneDrive\Documentos\deepseek-harness\default-workspace\tools\data"
os.makedirs(DEST, exist_ok=True)


def get(url, name):
    path = os.path.join(DEST, name)
    if os.path.exists(path) and os.path.getsize(path) > 1000:
        print("cached", name, os.path.getsize(path))
        return path
    t = time.time()
    r = urllib.request.urlopen(url, timeout=60)
    total = int(r.headers.get("Content-Length") or 0)
    done = 0
    with open(path, "wb") as f:
        while True:
            b = r.read(1 << 20)
            if not b:
                break
            f.write(b)
            done += len(b)
            if total and done % (10 << 20) < (1 << 20):
                print("  %s %.1f/%.1f MB" % (name, done / 1e6, total / 1e6), flush=True)
    print("downloaded", name, os.path.getsize(path), "in", round(time.time() - t, 1), "s", flush=True)
    return path


get("https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/pt/pt_50k.txt", "pt_50k.txt")
get("https://kaikki.org/dictionary/Portuguese/kaikki.org-dictionary-Portuguese.jsonl.gz", "kaikki_pt.jsonl.gz")
print("DONE")
