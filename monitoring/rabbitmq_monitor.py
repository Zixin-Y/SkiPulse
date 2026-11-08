import argparse
import base64
import csv
import getpass
import json
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


FIELDS = [
    "sample_time_utc",
    "queue",
    "ready",
    "unacked",
    "total",
    "consumers",
    "publish_rate",
    "deliver_rate",
    "ack_rate",
    "publish_count",
    "ack_count",
]


def make_row(queue, sample_time):
    # Leave unavailable statistics blank instead of inventing zero values.
    stats = queue.get("message_stats") or {}

    def rate(name):
        return (stats.get(name + "_details") or {}).get("rate", "")

    return {
        "sample_time_utc": sample_time,
        "queue": queue["name"],
        "ready": queue.get("messages_ready", ""),
        "unacked": queue.get("messages_unacknowledged", ""),
        "total": queue.get("messages", ""),
        "consumers": queue.get("consumers", ""),
        "publish_rate": rate("publish"),
        "deliver_rate": rate("deliver_get"),
        "ack_rate": rate("ack"),
        "publish_count": stats.get("publish", ""),
        "ack_count": stats.get("ack", ""),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    output = Path(args.output).expanduser().resolve()
    if not output.parent.is_dir():
        raise SystemExit("Output directory does not exist.")

    password = getpass.getpass("RabbitMQ password for skitrak: ")
    credentials = base64.b64encode(
        ("skitrak:" + password).encode("utf-8")
    ).decode("ascii")

    request = urllib.request.Request(
        "http://127.0.0.1:15673/api/queues/%2F",
        headers={"Authorization": "Basic " + credentials},
    )

    resorts = ["resort." + str(i) for i in range(1, 11)]
    expected = resorts + ["skitrak.expired"]

    # Exclusive creation protects existing test results from being overwritten.
    with output.open("x", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=FIELDS)
        writer.writeheader()
        handle.flush()
        print("Saving to:", output, flush=True)
        print("Press Ctrl+C to stop.", flush=True)

        try:
            while True:
                cycle_start = time.monotonic()
                try:
                    with urllib.request.urlopen(request, timeout=10) as response:
                        queues = json.load(response)

                    by_name = {queue["name"]: queue for queue in queues}
                    missing = [name for name in expected if name not in by_name]
                    if missing:
                        raise ValueError("Missing queues: " + ", ".join(missing))

                    sample_time = datetime.now(timezone.utc).isoformat()
                    rows = [
                        make_row(by_name[name], sample_time)
                        for name in expected
                    ]
                    writer.writerows(rows)
                    handle.flush()

                    resort_queues = [by_name[name] for name in resorts]
                    ready = sum(q["messages_ready"] for q in resort_queues)
                    unacked = sum(
                        q["messages_unacknowledged"] for q in resort_queues
                    )
                    consumers = sum(q["consumers"] for q in resort_queues)
                    dlq_total = by_name["skitrak.expired"]["messages"]

                    print(
                        "{} ready={} unacked={} total={} "
                        "consumers={} dlq_total={}".format(
                            sample_time,
                            ready,
                            unacked,
                            ready + unacked,
                            consumers,
                            dlq_total,
                        ),
                        flush=True,
                    )
                except Exception as error:
                    # A failed poll is reported and is not recorded as zero.
                    print(
                        "{} POLL_ERROR {}: {}".format(
                            datetime.now(timezone.utc).isoformat(),
                            type(error).__name__,
                            error,
                        ),
                        flush=True,
                    )

                elapsed = time.monotonic() - cycle_start
                time.sleep(max(0, 5 - elapsed))
        except KeyboardInterrupt:
            print("\nMonitoring stopped. CSV saved.", flush=True)


if __name__ == "__main__":
    main()