#!/usr/bin/env python3
"""
deckctl: control DRIVEDECK from a terminal (or from a Claude chat).

It edits deck.json in your private GitHub data repo, the same file the phone app and the
laptop dashboard sync, so every change shows up on the phone within seconds of it opening
(or within 15 minutes in the background).

  export DRIVEDECK_TOKEN=github_pat_...          # fine-grained token, contents read/write
  python3 tools/deckctl.py show
  python3 tools/deckctl.py add-place "Gym" "Revo Fitness Morley WA" --icon GYM
  python3 tools/deckctl.py send "Greenhse" --hours 12        # first tile on the car screen
  python3 tools/deckctl.py add-music "Party Mix" --kind PLAYLIST --url https://music.youtube.com/playlist?list=...
  python3 tools/deckctl.py stats
"""
import argparse, base64, json, os, sys, time, uuid, urllib.request, urllib.error

OWNER = os.environ.get("DRIVEDECK_OWNER", "LAZARZEC18")
REPO = os.environ.get("DRIVEDECK_REPO", "drivedeck-data")
API = f"https://api.github.com/repos/{OWNER}/{REPO}/contents/deck.json"
NOW = lambda: int(time.time() * 1000)


def token():
    t = os.environ.get("DRIVEDECK_TOKEN") or (open(os.path.expanduser("~/.drivedeck-token")).read().strip() if os.path.exists(os.path.expanduser("~/.drivedeck-token")) else None)
    if not t:
        sys.exit("Set DRIVEDECK_TOKEN (or put the token in ~/.drivedeck-token)")
    return t


def req(method, body=None):
    r = urllib.request.Request(API + ("?t=%d" % NOW() if method == "GET" else ""), method=method,
                               data=json.dumps(body).encode() if body else None,
                               headers={"Authorization": "Bearer " + token(), "Accept": "application/vnd.github+json",
                                        "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "deckctl"})
    try:
        with urllib.request.urlopen(r) as resp:
            return resp.status, json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, {}


def empty():
    return {"schema": 1, "places": [], "music": [], "trips": [], "tripsResetAt": 0, "nextUp": None, "recents": [],
            "drives": [], "fillUps": [], "plays": [], "settings": {"fuelType": "PULP", "litresPer100Km": 7.4, "phoneNavApp": "WAZE", "updatedAt": 0}}


def fetch():
    code, j = req("GET")
    if code == 404:
        return empty(), None
    if code != 200:
        sys.exit(f"GitHub error {code} (check token and repo {OWNER}/{REPO})")
    return {**empty(), **json.loads(base64.b64decode(j["content"]).decode())}, j["sha"]


def update(mutate, message):
    """Fetch → change → save, retrying if the phone saved at the same moment."""
    for _ in range(3):
        deck, sha = fetch()
        result = mutate(deck)
        body = {"message": "Chat: " + message, "content": base64.b64encode(json.dumps(deck, indent=1).encode()).decode()}
        if sha:
            body["sha"] = sha
        code, _ = req("PUT", body)
        if code in (200, 201):
            print("✓", message)
            return result
        if code not in (409, 422):
            sys.exit(f"GitHub error {code}")
    sys.exit("Couldn't save (kept conflicting), try again")


def live(items):
    return [x for x in items if not x.get("deleted")]


def find(items, name):
    m = [x for x in live(items) if x["name"].lower() == name.lower()] or [x for x in live(items) if name.lower() in x["name"].lower()]
    if not m:
        sys.exit(f"Not found: {name}")
    return m[0]


def main():
    p = argparse.ArgumentParser(description="Control DRIVEDECK")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("show")
    sub.add_parser("stats")
    a = sub.add_parser("add-place"); a.add_argument("name"); a.add_argument("address"); a.add_argument("--icon", default="PIN"); a.add_argument("--lat", type=float); a.add_argument("--lng", type=float)
    a = sub.add_parser("rm-place"); a.add_argument("name")
    a = sub.add_parser("rename-place"); a.add_argument("name"); a.add_argument("new_name")
    a = sub.add_parser("send"); a.add_argument("name"); a.add_argument("--hours", type=float, default=12)
    sub.add_parser("clear-send")
    a = sub.add_parser("add-music"); a.add_argument("name"); a.add_argument("--kind", default="PLAYLIST"); a.add_argument("--query"); a.add_argument("--url")
    a = sub.add_parser("rm-music"); a.add_argument("name")
    a = sub.add_parser("add-recent"); a.add_argument("title"); a.add_argument("--artist")
    a = sub.add_parser("fill"); a.add_argument("litres", type=float); a.add_argument("cents", type=float); a.add_argument("--odo", type=float); a.add_argument("--station")
    a = sub.add_parser("set"); a.add_argument("key", choices=["fuelType", "litresPer100Km", "phoneNavApp"]); a.add_argument("value")
    a = sub.add_parser("import-json"); a.add_argument("file", help="JSON with optional 'music' and 'recents' arrays to merge in")
    args = p.parse_args()

    if args.cmd == "show":
        d, _ = fetch()
        print(f"Places ({len(live(d['places']))}):")
        for x in live(d["places"]):
            print(f"  {x['name']:<20} {x.get('address','')}")
        print(f"Music ({len(live(d['music']))}):")
        for x in live(d["music"]):
            print(f"  {x['name']:<20} {x['kind']}")
        nu = d.get("nextUp")
        if nu and nu.get("placeId") and nu["expiresAt"] > NOW():
            print("Sent to car:", next((x["name"] for x in d["places"] if x["id"] == nu["placeId"]), "?"))
        print(f"Recent songs: {len(d['recents'])} · drives: {len(d['drives'])} · fill-ups: {len(live(d['fillUps']))} · plays: {len(d['plays'])}")
    elif args.cmd == "stats":
        d, _ = fetch()
        wk = NOW() - 7 * 86400000
        dr = [x for x in d["drives"] if x["startedAt"] >= wk]
        km = sum(x["distanceM"] for x in dr) / 1000; ms = sum(x["endedAt"] - x["startedAt"] for x in dr)
        print(f"Last 7 days: {km:.1f} km in {len(dr)} drives, {ms/3.6e6:.1f} h, avg {km/(ms/3.6e6) if ms else 0:.0f} km/h, "
              f"max {max([x['maxSpeedMps'] for x in dr] or [0])*3.6:.0f} km/h, songs {len([x for x in d['plays'] if x['at'] >= wk])}, "
              f"fuel ${sum(x['totalDollars'] for x in live(d['fillUps']) if x['at'] >= wk):.2f}")
    elif args.cmd == "add-place":
        def f(d):
            item = {"id": str(uuid.uuid4()), "name": args.name, "address": args.address, "icon": args.icon.upper(), "order": NOW(), "updatedAt": NOW(), "deleted": False}
            if args.lat is not None and args.lng is not None:
                item.update(lat=args.lat, lng=args.lng)
            d["places"].append(item)
        update(f, f"Added place {args.name}")
    elif args.cmd == "rm-place":
        update(lambda d: find(d["places"], args.name).update(deleted=True, updatedAt=NOW()), f"Deleted place {args.name}")
    elif args.cmd == "rename-place":
        update(lambda d: find(d["places"], args.name).update(name=args.new_name, updatedAt=NOW()), f"Renamed {args.name} → {args.new_name}")
    elif args.cmd == "send":
        update(lambda d: d.update(nextUp={"placeId": find(d["places"], args.name)["id"], "expiresAt": NOW() + int(args.hours * 3600e3), "updatedAt": NOW()}), f"Sent {args.name} to car")
    elif args.cmd == "clear-send":
        update(lambda d: d.update(nextUp={"placeId": None, "expiresAt": 0, "updatedAt": NOW()}), "Cleared next destination")
    elif args.cmd == "add-music":
        def f(d):
            item = {"id": str(uuid.uuid4()), "name": args.name, "kind": args.kind.upper(), "query": args.query or args.name, "order": NOW(), "updatedAt": NOW(), "deleted": False}
            if args.url:
                item["url"] = args.url
            d["music"].append(item)
        update(f, f"Added music {args.name}")
    elif args.cmd == "rm-music":
        update(lambda d: find(d["music"], args.name).update(deleted=True, updatedAt=NOW()), f"Deleted music {args.name}")
    elif args.cmd == "add-recent":
        def f(d):
            q = f"{args.title} {args.artist or ''}".strip()
            d["recents"] = ([{"title": args.title, "artist": args.artist, "query": q, "playedAt": NOW()}] +
                            [r for r in d["recents"] if r["query"].lower() != q.lower()])[:20]
        update(f, f"Added recent song {args.title}")
    elif args.cmd == "fill":
        def f(d):
            item = {"id": str(uuid.uuid4()), "at": NOW(), "litres": args.litres, "centsPerLitre": args.cents, "totalDollars": round(args.litres * args.cents / 100, 2),
                    "fullTank": True, "updatedAt": NOW(), "deleted": False}
            if args.odo: item["odometerKm"] = args.odo
            if args.station: item["station"] = args.station
            d["fillUps"].append(item)
        update(f, f"Logged fill-up {args.litres} L @ {args.cents}¢")
    elif args.cmd == "set":
        v = float(args.value) if args.key == "litresPer100Km" else args.value.upper()
        update(lambda d: d["settings"].update({args.key: v, "updatedAt": NOW()}), f"Set {args.key} = {v}")
    elif args.cmd == "import-json":
        data = json.load(open(args.file))
        def f(d):
            names = {m["name"].lower() for m in live(d["music"])}
            base = NOW()
            for i, m in enumerate(data.get("music", [])):
                if m["name"].lower() not in names:
                    d["music"].append({"id": str(uuid.uuid4()), "kind": "PLAYLIST", "query": m["name"], "order": base + i, "updatedAt": base, "deleted": False, **m})
            rec = data.get("recents", [])
            if rec:
                seen = {r["query"].lower() for r in d["recents"]}
                d["recents"] = (d["recents"] + [dict(r, playedAt=r.get("playedAt", base - i * 60000)) for i, r in enumerate(rec) if r["query"].lower() not in seen])
                d["recents"] = sorted(d["recents"], key=lambda r: -r["playedAt"])[:20]
        update(f, f"Imported {len(data.get('music', []))} playlists and {len(data.get('recents', []))} recent songs")


if __name__ == "__main__":
    main()
