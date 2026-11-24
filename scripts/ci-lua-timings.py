"""Summarize fixture-only Lua timings without treating incomplete operations as samples."""
import math
import re
import sys
from collections import defaultdict
from pathlib import Path


def summarize(lines):
    operations = defaultdict(dict)
    samples = defaultdict(list)
    for line in lines:
        if "HyprLuaGate" not in line:
            continue
        fields = dict(re.findall(r"(\w+)=([\w-]+)", line))
        if "operation" in fields:
            operation = operations[fields["operation"]]
            operation[fields["phase"]] = fields
        else:
            for key in ("clickToUiMs", "commitMs", "publishMs"):
                if key in fields:
                    label = fields.get("kind", "hello")
                    samples[f"{label}.{key}"].append(int(fields[key]))

    def difference(operation, end, start):
        return int(operation[end]["sinceQueueMs"]) - int(operation[start]["sinceQueueMs"])

    complete = 0
    for operation in operations.values():
        if not {"binding", "connected", "dispatch", "lua", "reply", "released"} <= operation.keys():
            continue
        complete += 1
        samples["worker.queueMs"].append(int(operation["binding"]["queueMs"]))
        samples["worker.ioHandoffMs"].append(int(operation["binding"]["ioMs"]))
        samples["worker.bindMs"].append(difference(operation, "connected", "binding"))
        samples["worker.prepareDispatchMs"].append(difference(operation, "dispatch", "connected"))
        samples["worker.dispatchToReplyMs"].append(difference(operation, "reply", "dispatch"))
        samples["worker.luaMs"].append(int(operation["lua"]["luaMs"]))
        samples["worker.replyToReleaseMs"].append(difference(operation, "released", "reply"))

    print(f"operations={len(operations)} complete={complete} incomplete={len(operations) - complete}")
    for label, values in sorted(samples.items()):
        values.sort()
        p95 = values[math.ceil(len(values) * 0.95) - 1]
        print(f"{label} samples={len(values)} p95={p95} max={values[-1]}")


if __name__ == "__main__":
    summarize(Path(sys.argv[1]).read_text().splitlines())
