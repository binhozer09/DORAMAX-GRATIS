import json
import os
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

CHANNEL_ID = "UCVRGRH2goft4HQ6fWkq-j7g"
FEED_URL = f"https://www.youtube.com/feeds/videos.xml?channel_id={CHANNEL_ID}"
OUTPUT = "data/videos.json"

request = urllib.request.Request(FEED_URL, headers={"User-Agent": "DORAMAX-video-feed/1.0"})
with urllib.request.urlopen(request, timeout=30) as response:
    xml_data = response.read()

root = ET.fromstring(xml_data)
ns = {"yt": "http://www.youtube.com/xml/schemas/2015", "atom": "http://www.w3.org/2005/Atom"}
incoming = []
for entry in root.findall("atom:entry", ns):
    video_id = entry.findtext("yt:videoId", default="", namespaces=ns)
    title = entry.findtext("atom:title", default="Vídeo novo", namespaces=ns)
    published = entry.findtext("atom:published", default="", namespaces=ns)
    if video_id:
        incoming.append({"id": video_id, "title": title, "published": published})

if not incoming:
    raise SystemExit("O feed do YouTube não retornou vídeos; mantendo o catálogo anterior.")

os.makedirs("data", exist_ok=True)
try:
    with open(OUTPUT, "r", encoding="utf-8") as file:
        current = json.load(file)
except (FileNotFoundError, json.JSONDecodeError):
    current = {"channelId": CHANNEL_ID, "videos": []}

merged = {}
for video in incoming + current.get("videos", []):
    if video.get("id") and video["id"] not in merged:
        merged[video["id"]] = {
            "id": video["id"],
            "title": video.get("title") or "Vídeo novo",
            "published": video.get("published") or ""
        }

newest = [v for v in merged.values() if v.get("published")]
older = [v for v in merged.values() if not v.get("published")]
newest.sort(key=lambda v: v["published"], reverse=True)
result = {
    "channelId": CHANNEL_ID,
    "updatedAt": datetime.now(timezone.utc).isoformat(),
    "videos": (newest + older)[:300]
}
with open(OUTPUT, "w", encoding="utf-8") as file:
    json.dump(result, file, ensure_ascii=False, indent=2)
    file.write("\n")
print(f"Catálogo atualizado: {len(result['videos'])} vídeos; {len(incoming)} recebidos do YouTube.")
