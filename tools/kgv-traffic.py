#!/usr/bin/env python3
"""Daily GitHub traffic log for the KGV FusionX repos.

GitHub keeps traffic for 14 days only. This saves it for good, using the gh CLI's
login (it needs admin access to the repos). Per-day views and clones are merged in,
so a run after the PC was off for up to 13 days still fills every day.

Files in ~/.local/share/kgv-traffic/ (override with KGV_TRAFFIC_DIR):
  daily.csv      date, repo, views, unique_views, clones, unique_clones
  downloads.csv  date, release, asset, downloads  (running totals, one row per run day)
  referrers.csv  date, repo, referrer, views, unique_views  (last 14 days, as of date)
  stars.csv      date, repo, stars, forks

usage: kgv-traffic.py           # fetch and save
       kgv-traffic.py --show    # print the last 14 days and download totals

Daily: copy kgv-traffic.service and kgv-traffic.timer to ~/.config/systemd/user/, fix the
ExecStart path, then `systemctl --user enable --now kgv-traffic.timer`.
"""
import csv
import datetime
import json
import os
import subprocess
import sys

OWNER = "KGVHub"
REPOS = ["kgv-fusionx", "android", "android_packages_apps_KGVIntelligence",
         "android_external_unbound"]
RELEASES_REPO = "kgv-fusionx"
DIR = os.environ.get("KGV_TRAFFIC_DIR",
                     os.path.expanduser("~/.local/share/kgv-traffic"))


def gh(path):
    out = subprocess.run(["gh", "api", path], capture_output=True, text=True, check=True)
    return json.loads(out.stdout)


def read(name, key):
    """Rows of a CSV file as {key tuple: row}."""
    path = os.path.join(DIR, name)
    if not os.path.exists(path):
        return {}
    with open(path, newline="") as f:
        return {tuple(r[k] for k in key): r for r in csv.DictReader(f)}


def write(name, fields, rows):
    path = os.path.join(DIR, name)
    tmp = path + ".tmp"
    with open(tmp, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        for k in sorted(rows):
            w.writerow(rows[k])
    os.replace(tmp, path)


def fetch():
    os.makedirs(DIR, exist_ok=True)
    today = datetime.date.today().isoformat()

    fields = ["date", "repo", "views", "unique_views", "clones", "unique_clones"]
    daily = read("daily.csv", ["date", "repo"])
    for repo in REPOS:
        for kind in ("views", "clones"):
            data = gh(f"repos/{OWNER}/{repo}/traffic/{kind}")
            for day in data.get(kind, []):
                date = day["timestamp"][:10]
                row = daily.setdefault((date, repo), {f: 0 for f in fields} | {
                    "date": date, "repo": repo})
                row[kind] = day["count"]
                row["unique_" + kind] = day["uniques"]
    write("daily.csv", fields, daily)

    fields = ["date", "release", "asset", "downloads"]
    downloads = read("downloads.csv", ["date", "release", "asset"])
    for rel in gh(f"repos/{OWNER}/{RELEASES_REPO}/releases"):
        for asset in rel["assets"]:
            downloads[(today, rel["tag_name"], asset["name"])] = {
                "date": today, "release": rel["tag_name"], "asset": asset["name"],
                "downloads": asset["download_count"]}
    write("downloads.csv", fields, downloads)

    fields = ["date", "repo", "referrer", "views", "unique_views"]
    referrers = read("referrers.csv", ["date", "repo", "referrer"])
    fields_s = ["date", "repo", "stars", "forks"]
    stars = read("stars.csv", ["date", "repo"])
    for repo in REPOS:
        for r in gh(f"repos/{OWNER}/{repo}/traffic/popular/referrers"):
            referrers[(today, repo, r["referrer"])] = {
                "date": today, "repo": repo, "referrer": r["referrer"],
                "views": r["count"], "unique_views": r["uniques"]}
        info = gh(f"repos/{OWNER}/{repo}")
        stars[(today, repo)] = {"date": today, "repo": repo,
                                "stars": info["stargazers_count"], "forks": info["forks_count"]}
    write("referrers.csv", fields, referrers)
    write("stars.csv", fields_s, stars)
    print(f"kgv-traffic: saved {today} to {DIR}")


def show():
    """Per-repo totals for the last 14 days, the days with any traffic, and downloads."""
    since = (datetime.date.today() - datetime.timedelta(days=14)).isoformat()
    daily = {k: r for k, r in read("daily.csv", ["date", "repo"]).items() if k[0] >= since}
    print(f"last 14 days  {'repo':38} {'views':>6} {'uniq':>5} {'clones':>7} {'uniq':>5}")
    for repo in REPOS:
        rows = [r for (d, rp), r in daily.items() if rp == repo]
        t = {k: sum(int(r[k]) for r in rows)
             for k in ("views", "unique_views", "clones", "unique_clones")}
        print(f"{'':13} {repo:38} {t['views']:>6} {t['unique_views']:>5} "
              f"{t['clones']:>7} {t['unique_clones']:>5}")
    busy = [(k, r) for k, r in sorted(daily.items()) if int(r["views"]) or int(r["clones"])]
    if busy:
        print("\ndays with traffic:")
        for (date, repo), r in busy:
            print(f"  {date}  {repo:38} {r['views']:>6} {r['unique_views']:>5} "
                  f"{r['clones']:>7} {r['unique_clones']:>5}")
    downloads = read("downloads.csv", ["date", "release", "asset"])
    if downloads:
        last = max(k[0] for k in downloads)
        print(f"\ndownloads (total, as of {last}):")
        for (date, rel, asset), r in sorted(downloads.items()):
            if date == last:
                print(f"  {rel} {asset}: {r['downloads']}")


if __name__ == "__main__":
    show() if "--show" in sys.argv else fetch()
