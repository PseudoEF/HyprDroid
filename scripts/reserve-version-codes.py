"""Repository-wide durable counter, including old-commit re-runs. Never changes main."""
import os
from pathlib import Path
import re
import subprocess

repo = os.environ["GH_REPO"]
commit = os.environ["HYPRDROID_BUILD_COMMIT"]
if not re.fullmatch(r"[0-9a-f]{40,64}", commit):
    raise SystemExit("Invalid commit")
refs = subprocess.check_output(["gh", "api", "--paginate", f"repos/{repo}/git/matching-refs/tags/ci/debug/", "--jq", ".[].ref"], text=True)
codes = [int(match[1]) for ref in refs.splitlines() if (match := re.fullmatch(r"refs/tags/ci/debug/(\d+)", ref))]
first = max([9999, *codes]) + 1
second = first + 1
if second > 2_100_000_000:
    raise SystemExit("Android versionCode range exhausted; never wrap or reset the counter")
for code in (first, second):
    subprocess.run(["gh", "api", "--method", "POST", f"repos/{repo}/git/refs", "-f", f"ref=refs/tags/ci/debug/{code}", "-f", f"sha={commit}"], check=True, stdout=subprocess.DEVNULL)
with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
    output.write(f"first={first}\nsecond={second}\n")
print(f"Reserved version codes {first}, {second}")
