#!/usr/bin/env python3
"""Dependency-free article text extractor for diagnostics and fallback tooling."""
import argparse, html.parser, re, urllib.request
class P(html.parser.HTMLParser):
    def __init__(self):super().__init__();self.out=[];self.ignore=0
    def handle_starttag(self,t,a):
        if t in ('script','style','nav','noscript'):self.ignore+=1
        if not self.ignore and t in ('p','br','li','h1','h2','h3'):self.out.append('\n')
    def handle_endtag(self,t):
        if t in ('script','style','nav','noscript') and self.ignore:self.ignore-=1
    def handle_data(self,d):
        if not self.ignore:self.out.append(d+' ')
ap=argparse.ArgumentParser();ap.add_argument('url');a=ap.parse_args();req=urllib.request.Request(a.url,headers={'User-Agent':'AutoNewsRoller/0.1'})
with urllib.request.urlopen(req,timeout=30) as r:h=r.read().decode('utf-8','replace')
p=P();p.feed(h)
for line in ''.join(p.out).splitlines():
    line=re.sub(r'\s+',' ',line).strip()
    if len(line)>=25:print(line)
