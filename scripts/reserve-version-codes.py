"""Repository-wide durable counter, including old-commit re-runs. Never changes main."""
import os
from pathlib import Path
import re
import subprocess
from version_codes import next_codes

repo = os.environ["GH_REPO"]
commit = os.environ["HYPRDROID_BUILD_COMMIT"]
if not re.fullmatch(r"[0-9a-f]{40,64}", commit):
    raise SystemExit("Invalid commit")
refs = subprocess.check_output(["gh", "api", "--paginate", f"repos/{repo}/git/matching-refs/tags/ci/debug/", "--jq", ".[].ref"], text=True)
first, second = next_codes(refs)
for code in (first, second):
    subprocess.run(["gh", "api", "--method", "POST", f"repos/{repo}/git/refs", "-f", f"ref=refs/tags/ci/debug/{code}", "-f", f"sha={commit}"], check=True, stdout=subprocess.DEVNULL)
with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
    output.write(f"first={first}\nsecond={second}\n")
print(f"Reserved version codes {first}, {second}")
