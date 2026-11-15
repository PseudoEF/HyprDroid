import re

MAX_ANDROID_CODE = 2_100_000_000

def next_codes(refs: str) -> tuple[int, int]:
    codes = [int(match[1]) for ref in refs.splitlines() if (match := re.fullmatch(r"refs/tags/ci/debug/(\d+)", ref))]
    first = max([9999, *codes]) + 1
    if first + 1 > MAX_ANDROID_CODE:
        raise ValueError("Android versionCode range exhausted; never wrap or reset the counter")
    return first, first + 1
