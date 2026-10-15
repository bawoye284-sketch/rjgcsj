"""Download public-use NHEFS files and documentation from CDC without login."""

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import time
from urllib.parse import urljoin
from urllib.request import Request, urlopen


INDEX = "https://wwwn.cdc.gov/nchs/nhanes/nhefs/default.aspx"
ROOT = Path(__file__).resolve().parents[1]
DESTINATION = ROOT / "data" / "nhefs"
RAW_NAMES = {
    "n82int.txt", "n86int.txt", "n87int.txt", "n92int.txt",
    "n82hcfs.txt", "n86hcfs.txt", "n87hcfs.txt", "n92hcfs.txt",
    "n92hcfsp.txt", "n92mort.txt", "n92vitl.txt",
}


def download(url, destination, data_dir):
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".part")
    for attempt in range(3):
        try:
            request = Request(url, headers={"User-Agent": "IHMS-NHEFS-downloader/1.0"})
            with urlopen(request, timeout=60) as response, temporary.open("wb") as output:
                content_type = response.headers.get("Content-Type", "")
                declared = response.headers.get("Content-Length")
                digest = hashlib.sha256()
                size = 0
                first = True
                while chunk := response.read(1024 * 1024):
                    if first:
                        first = False
                        if chunk.lstrip().lower().startswith((b"<!doctype", b"<html")):
                            raise ValueError("Received an HTML page instead of a data file")
                        if destination.suffix == ".pdf" and not chunk.startswith(b"%PDF-"):
                            raise ValueError("Documentation response is not a PDF")
                    output.write(chunk)
                    digest.update(chunk)
                    size += len(chunk)
                if not size or (declared is not None and int(declared) != size):
                    raise ValueError("Empty or incomplete download")
            temporary.replace(destination)
            return {"file": destination.relative_to(data_dir).as_posix(),
                    "source_url": url, "bytes": size, "sha256": digest.hexdigest(),
                    "content_type": content_type}
        except Exception:
            if attempt == 2:
                raise
            time.sleep(attempt + 1)
        finally:
            if temporary.exists():
                temporary.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workers", type=int, choices=range(1, 5), default=3)
    parser.add_argument("--data-dir", type=Path, default=DESTINATION)
    args = parser.parse_args()
    data_dir = args.data_dir.resolve()
    with urlopen(INDEX, timeout=30) as response:
        html = response.read().decode("utf-8", "replace")
    urls = sorted({urljoin(INDEX, href) for href in re.findall(
        r"href=[\"']([^\"']+)", html, re.IGNORECASE)
        if "/data/nhefs/" in href.lower() and href.lower().endswith((".txt", ".pdf"))})
    found_raw = {url.rsplit("/", 1)[-1].lower() for url in urls} & RAW_NAMES
    if found_raw != RAW_NAMES:
        raise RuntimeError(f"Official index is missing expected raw files: {RAW_NAMES - found_raw}")

    results = []
    failures = []
    with ThreadPoolExecutor(max_workers=args.workers) as executor:
        futures = {}
        for url in urls:
            name = url.rsplit("/", 1)[-1]
            kind = "raw" if name.lower() in RAW_NAMES else "documentation"
            future = executor.submit(download, url, data_dir / kind / name, data_dir)
            futures[future] = url
        for future in as_completed(futures):
            try:
                item = future.result()
                results.append(item)
                print(f"OK {item['file']} ({item['bytes']} bytes)", flush=True)
            except Exception as error:
                failure = {"source_url": futures[future], "error": str(error)}
                failures.append(failure)
                print("FAILED " + json.dumps(failure), flush=True)

    data_dir.mkdir(parents=True, exist_ok=True)
    manifest = {"dataset": "NHEFS public-use files", "official_index": INDEX,
                "downloaded_at_utc": datetime.now(timezone.utc).isoformat(),
                "files": sorted(results, key=lambda item: item["file"]), "failures": failures}
    (data_dir / "download_manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"downloaded_files": len(results), "failed_files": len(failures),
                      "total_bytes": sum(item["bytes"] for item in results)}), flush=True)
    if failures:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
