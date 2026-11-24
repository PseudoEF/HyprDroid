"""Summarize the benchmark, including unsuccessful runs and sampled process memory."""
import math
import re
import sys
from pathlib import Path


def percentile(values, pct):
    return values[math.ceil(len(values) * pct / 100) - 1] if values else "-"


def memory_samples(path):
    for block in path.read_text().split("SAMPLE ")[1:]:
        core = 0
        workers = 0
        for line in block.splitlines()[1:]:
            match = re.match(r"\s*([\d,]+)K:\s+([\w.:]+)", line)
            if not match:
                continue
            kib = int(match[1].replace(",", ""))
            if ":dev.hyprdroid.launcher.external.ExternalLuaService:" in line:
                workers += kib
            elif match[2] == "dev.hyprdroid.launcher.experimental":
                core += kib
        if core or workers:
            yield core, workers


def report(trial):
    log = (trial / "logcat.txt").read_text()
    samples = list(memory_samples(trial / "pss.txt"))
    sample_line = next((line for line in log.splitlines() if "HyprLuaBenchmark:" in line), "")
    fields = dict(re.findall(r"(\w+)=([\w,]+)", sample_line))
    latencies = sorted(int(n) for n in fields.get("latenciesMs", "").split(",") if n)
    worker_cpu = {int(pid): int(cpu) for pid, cpu in re.findall(r"worker exit pid=(\d+) cpuMs=(\d+)", log)}
    print(
        trial.name, f"frames={fields.get('initialFrames', '?')}", f"attempts={fields.get('attempts', '?')}",
        f"misses={fields.get('misses', '?')}", f"coreCpuMs={fields.get('cpuMs', '?')}",
        f"durationMs={fields.get('durationMs', '?')}", f"workerCpuMs={sum(worker_cpu.values())}",
        f"bindAttempts={log.count('Worker binding requested')}",
        f"launches={len(set(re.findall(r'worker launched pid=(\d+)', log)))}", f"unbound={log.count('Worker binding closing')}",
        f"observedExits={len(worker_cpu)}", f"ready={log.count('reserve=ready')}",
        f"hits={log.count('reserve=hit')}", f"cold={log.count('reserve=cold')}",
        f"unused={log.count('reserve=unused')}", f"timeouts={log.count('Worker response timed out')}",
        f"declined={log.count('Worker admission declined')}",
        f"pssSamples={len(samples)}", f"corePeakKiB={max((core for core, _ in samples), default='-')}",
        f"workerPeakKiB={max((worker for _, worker in samples), default='-')}",
        f"totalPeakKiB={max((core + worker for core, worker in samples), default='-')}",
        f"finalSampleKiB={samples[-1] if samples else '-'}",
        "inputMs[p50/p95/p99/max]=" + "/".join(str(percentile(latencies, p)) for p in (50, 95, 99, 100)),
    )


for directory in sorted(Path(sys.argv[1]).iterdir()):
    if directory.is_dir():
        report(directory)
