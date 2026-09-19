#!/usr/bin/env python3
"""Small RSS diagnostics utility; the production pipeline uses Java ingestion."""
import argparse, urllib.request, xml.etree.ElementTree as ET
ap=argparse.ArgumentParser();ap.add_argument('url');a=ap.parse_args()
req=urllib.request.Request(a.url,headers={'User-Agent':'AutoNewsRoller/0.1'})
with urllib.request.urlopen(req,timeout=30) as r:data=r.read()
root=ET.fromstring(data)
for item in root.findall('.//item')[:20]: print((item.findtext('title') or '').strip(), (item.findtext('link') or '').strip())
