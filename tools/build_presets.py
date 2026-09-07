#!/usr/bin/env python3
"""Build the bundled website preset lists for Distraction Killer Launcher.

Outputs (all under app/src/main/assets/presets/):
  index.tsv     catalog of presets (id, kind, name, description, source, license)
  <id>.txt      one domain per line, lowercase, sorted, deduplicated

Hand-curated presets are defined in this file. The "adult" and "gambling"
presets are derived from the StevenBlack/hosts extensions (MIT), collapsed to
registrable domains (eTLD+1) with the Public Suffix List (ICANN + PRIVATE
sections). A host is only collapsed when upstream blocks its registrable
domain itself; subdomains of shared sites (nsfw.reddit.com, a blog on
wordpress.com) are kept as-is so the platform is not blocked wholesale. Downloads are cached
in tools/.cache/ so re-runs are offline and byte-for-byte reproducible.

Usage:
  python3 tools/build_presets.py            # build (uses cache when present)
  python3 tools/build_presets.py --refresh  # re-download sources first
  python3 tools/build_presets.py --check    # validate existing assets only

Python 3.8+, standard library only.
"""

from __future__ import annotations

import argparse
import datetime as _dt
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS_DIR = ROOT / "app" / "src" / "main" / "assets" / "presets"
CACHE_DIR = ROOT / "tools" / ".cache"
INDEX_NAME = "index.tsv"
NOTICES_FILE = "THIRD_PARTY_NOTICES.md"

MAX_TOTAL_BYTES = 1_500_000  # hard budget for all preset files together

# ---------------------------------------------------------------------------
# Domain syntax (mirrors the Kotlin parser's contract)
# ---------------------------------------------------------------------------

DOMAIN_RE = re.compile(r"^[a-z0-9-]+(\.[a-z0-9-]+)+$")
ID_RE = re.compile(r"^[a-z0-9_]+$")
INDEX_HEADER = ["id", "kind", "name", "description", "source", "license"]

HOSTS_SKIP = {
    "localhost",
    "localhost.localdomain",
    "local",
    "broadcasthost",
    "0.0.0.0",
    "ip6-localhost",
    "ip6-loopback",
    "ip6-localnet",
    "ip6-mcastprefix",
    "ip6-allnodes",
    "ip6-allrouters",
    "ip6-allhosts",
}


def normalize_host(raw: str) -> str | None:
    """Lowercase, strip a leading 'www.', and reject anything that is not a
    plain DNS hostname with at least two labels. Returns None when rejected."""
    host = raw.strip().lower().rstrip(".")
    if host.startswith("www."):
        host = host[4:]
    if not host or not DOMAIN_RE.match(host):
        return None
    labels = host.split(".")
    # No IPv4 addresses (the regex alone would accept "1.2.3.4") and no
    # all-numeric TLDs; no empty/invalid labels.
    if labels[-1].isdigit():
        return None
    if len(host) > 253:
        return None
    for label in labels:
        if len(label) > 63 or label.startswith("-") or label.endswith("-"):
            return None
    return host


# ---------------------------------------------------------------------------
# Public Suffix List
# ---------------------------------------------------------------------------


class PublicSuffixList:
    """Minimal implementation of the PSL algorithm (https://publicsuffix.org/list/).

    Supports normal rules, wildcard rules ("*.ck") and exception rules
    ("!www.ck"). Both the ICANN and the PRIVATE DOMAINS sections are loaded
    by default; the private section is what keeps shared platforms such as
    blogspot.com, github.io or translate.goog from being collapsed into one
    entry that would block the whole platform.
    """

    def __init__(self, text: str, include_private: bool = True) -> None:
        self.rules: set[str] = set()
        self.wildcards: set[str] = set()  # "ck" for the rule "*.ck"
        self.exceptions: set[str] = set()  # "www.ck" for the rule "!www.ck"
        self.include_private = include_private
        in_private = False
        for line in text.splitlines():
            line = line.strip()
            if not line:
                continue
            if line.startswith("//"):
                if "BEGIN PRIVATE DOMAINS" in line:
                    in_private = True
                elif "END PRIVATE DOMAINS" in line:
                    in_private = False
                continue
            if in_private and not include_private:
                continue
            rule = line.split()[0].lower()
            if rule.startswith("!"):
                self.exceptions.add(rule[1:])
            elif rule.startswith("*."):
                self.wildcards.add(rule[2:])
            else:
                self.rules.add(rule)

    def public_suffix(self, host: str) -> str:
        labels = host.split(".")
        best_len = 1  # the implicit "*" rule: the TLD is always a public suffix
        # Walk every suffix of the host, longest match wins; exception rules
        # win over everything and denote a suffix one label shorter.
        for i in range(len(labels)):
            candidate = ".".join(labels[i:])
            n = len(labels) - i
            if candidate in self.exceptions:
                return ".".join(labels[i + 1 :])
            if candidate in self.rules and n > best_len:
                best_len = n
            parent = ".".join(labels[i + 1 :]) if i + 1 < len(labels) else ""
            if parent and parent in self.wildcards and n > best_len:
                best_len = n
        return ".".join(labels[-best_len:])

    def registrable(self, host: str) -> str | None:
        """eTLD+1 of host, or None when host is itself a public suffix."""
        suffix = self.public_suffix(host)
        if host == suffix:
            return None
        rest = host[: -len(suffix) - 1]
        return rest.split(".")[-1] + "." + suffix


def _self_test_psl(psl: PublicSuffixList) -> None:
    cases = {
        "old.reddit.com": "reddit.com",
        "reddit.com": "reddit.com",
        "news.bbc.co.uk": "bbc.co.uk",
        "www.gov.uk": "www.gov.uk",  # gov.uk is itself a public suffix
        "a.b.c.kobe.jp": "b.c.kobe.jp",  # wildcard rule *.kobe.jp
        "city.kobe.jp": "city.kobe.jp",  # exception rule !city.kobe.jp
        "x.city.kobe.jp": "city.kobe.jp",
        "foo.unknowntld": "foo.unknowntld",  # implicit "*" rule
    }
    for host, want in cases.items():
        got = psl.registrable(host)
        if got != want:
            raise SystemExit(f"PSL self-test failed: {host!r} -> {got!r}, expected {want!r}")
    if psl.registrable("co.uk") is not None or psl.registrable("com") is not None:
        raise SystemExit("PSL self-test failed: public suffixes must not be registrable")
    if psl.include_private and psl.registrable("some.blog.blogspot.com") != "blog.blogspot.com":
        raise SystemExit("PSL self-test failed: private-section rule blogspot.com not honoured")


# ---------------------------------------------------------------------------
# Download cache
# ---------------------------------------------------------------------------

SOURCES = {
    "porn_hosts.txt": "https://raw.githubusercontent.com/StevenBlack/hosts/master/extensions/porn/sinfonietta/hosts",
    "gambling_hosts.txt": "https://raw.githubusercontent.com/StevenBlack/hosts/master/extensions/gambling/sinfonietta/hosts",
    "public_suffix_list.dat": "https://publicsuffix.org/list/public_suffix_list.dat",
}


def fetch(name: str, refresh: bool) -> tuple[str, str]:
    """Return (text, fetched_date) for a cached source, downloading if needed."""
    url = SOURCES[name]
    path = CACHE_DIR / name
    meta = CACHE_DIR / (name + ".meta.json")
    if path.exists() and meta.exists() and not refresh:
        info = json.loads(meta.read_text(encoding="utf-8"))
        return path.read_text(encoding="utf-8", errors="replace"), info["fetched"]
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    print(f"downloading {url}")
    req = urllib.request.Request(url, headers={"User-Agent": "distraction-killer-launcher-build-presets/1.0"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        data = resp.read()
    fetched = _dt.date.today().isoformat()
    path.write_bytes(data)
    meta.write_text(json.dumps({"url": url, "fetched": fetched, "bytes": len(data)}, indent=2) + "\n", encoding="utf-8")
    return data.decode("utf-8", errors="replace"), fetched


# ---------------------------------------------------------------------------
# Derived lists (hosts file -> registrable domains)
# ---------------------------------------------------------------------------


def parse_hosts(text: str) -> tuple[set[str], int]:
    """Extract hostnames from '0.0.0.0 host' / '127.0.0.1 host' lines.
    Returns (hosts, rejected_count)."""
    hosts: set[str] = set()
    rejected = 0
    for line in text.splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) < 2 or parts[0] not in ("0.0.0.0", "127.0.0.1"):
            continue
        raw = parts[1]  # ignore any trailing junk tokens
        if raw.lower() in HOSTS_SKIP or raw.lower().startswith("ip6-"):
            continue
        host = normalize_host(raw)
        if host is None:
            rejected += 1
            continue
        hosts.add(host)
    return hosts, rejected


def dedupe_by_suffix(domains: set[str]) -> set[str]:
    """Drop entries already covered by a shorter entry (the app matches by
    suffix, so 'a.b.example.com' is redundant when 'b.example.com' is listed)."""
    out: set[str] = set()
    for d in domains:
        parts = d.split(".")
        if not any(".".join(parts[i:]) in domains for i in range(1, len(parts) - 1)):
            out.add(d)
    return out


def collapse(hosts: set[str], psl: PublicSuffixList) -> tuple[set[str], list[str], dict[str, int]]:
    """Collapse hosts to registrable domains (eTLD+1) without over-blocking.

    A host is replaced by its registrable domain only when the upstream list
    blocks that registrable domain itself. When upstream lists only specific
    subdomains (nsfw.reddit.com, someblog.wordpress.com, a page on itch.io)
    the subdomains are kept as-is, because collapsing them would block the
    whole shared platform, which upstream never intended. Hosts that are
    themselves public suffixes are dropped. Returns (domains, dropped, stats).
    """
    out: set[str] = set()
    dropped: list[str] = []
    protected: dict[str, int] = {}  # registrable domain kept out -> hosts kept under it
    for host in hosts:
        reg = psl.registrable(host)
        if reg is None:
            dropped.append(host)
        elif reg in hosts:
            out.add(reg)
        else:
            out.add(host)
            protected[reg] = protected.get(reg, 0) + 1
    out = dedupe_by_suffix(out)
    deeper = sum(1 for d in out if psl.registrable(d) != d)
    stats = {"registrable": len(out) - deeper, "deeper": deeper, "protected_parents": len(protected)}
    return out, sorted(dropped), stats


# ---------------------------------------------------------------------------
# Hand-curated lists
# ---------------------------------------------------------------------------

HAND_CURATED_SOURCE = "Hand-curated for Distraction Killer Launcher"
HAND_CURATED_LICENSE = "Public domain (this project)"
STEVENBLACK_LICENSE = "MIT"

# Entries below the registrable domain, kept because the registrable domain
# itself must not be blocked/allowed wholesale (e.g. google.com).
DEEPER_ENTRIES = {
    "abcnews.go.com",
    "amazon.com.be",  # com.be is not a public suffix in the PSL
    "aws.amazon.com",
    "finance.yahoo.com",
    "news.google.com",
    "news.sky.com",
    "news.yahoo.com",
    "news.ycombinator.com",
    "scholar.google.com",
    "store.steampowered.com",
    "timesofindia.indiatimes.com",
    "tv.apple.com",
    "translate.google.com",
    "translate.yandex.com",
    "webcache.googleusercontent.com",
    "web.archive.org",
    "redlib.catsarch.com",
}

# Entries that are public suffixes on purpose. Only sensible in ALLOW lists,
# where matching every site under them is the point: all UK government and
# NHS sites, every published Notion page, and GitHub's raw-file/asset host.
SUFFIX_ENTRIES = {"gov.uk", "nhs.uk", "notion.site", "githubusercontent.com", "translate.goog"}

BLOCK_DISTRACTIONS = """
    # social networks
    facebook.com fb.com fb.watch instagram.com threads.net threads.com
    twitter.com x.com bsky.app mastodon.social mastodon.online
    snapchat.com tiktok.com douyin.com pinterest.com pinterest.co.uk pinterest.de
    pinterest.fr pinterest.ca pinterest.com.au pinterest.jp pinterest.es pinterest.it
    tumblr.com vk.com ok.ru weibo.com douban.com nextdoor.com myspace.com bereal.com
    truthsocial.com gab.com discord.com discord.gg
    # forums and link aggregators known as time-sinks
    reddit.com redd.it redditmedia.com lemmy.world 4chan.org 4channel.org quora.com
    digg.com fark.com somethingawful.com tvtropes.org
    # video and streaming entertainment
    youtube.com youtu.be youtube-nocookie.com twitch.tv kick.com dailymotion.com vimeo.com
    rumble.com odysee.com bitchute.com bilibili.com nicovideo.jp youku.com iqiyi.com
    netflix.com hulu.com disneyplus.com primevideo.com hbomax.com max.com peacocktv.com
    paramountplus.com crunchyroll.com tubitv.com pluto.tv hotstar.com tv.apple.com
    # memes, humour and image sites
    9gag.com imgur.com ifunny.co boredpanda.com buzzfeed.com cheezburger.com
    knowyourmeme.com giphy.com tenor.com imgflip.com memedroid.com funnyjunk.com
    ebaumsworld.com thechive.com theonion.com cracked.com ranker.com upworthy.com
    # dating apps
    tinder.com bumble.com hinge.co okcupid.com badoo.com match.com
"""

BLOCK_NEWS = """
    # aggregators and portals
    news.google.com news.yahoo.com finance.yahoo.com msn.com drudgereport.com
    flipboard.com news.ycombinator.com slashdot.org
    # United States
    cnn.com foxnews.com nytimes.com washingtonpost.com wsj.com usatoday.com latimes.com
    nypost.com nydailynews.com huffpost.com politico.com thehill.com axios.com vox.com
    slate.com salon.com theatlantic.com newyorker.com time.com newsweek.com usnews.com
    npr.org nbcnews.com cbsnews.com abcnews.go.com msnbc.com breitbart.com dailycaller.com
    dailywire.com thedailybeast.com motherjones.com vice.com
    # business and finance news
    bloomberg.com reuters.com apnews.com cnbc.com foxbusiness.com businessinsider.com
    forbes.com fortune.com marketwatch.com ft.com economist.com
    # technology news
    theverge.com techcrunch.com engadget.com arstechnica.com wired.com gizmodo.com
    mashable.com cnet.com zdnet.com techradar.com 9to5mac.com 9to5google.com macrumors.com
    androidpolice.com androidauthority.com
    # United Kingdom and Ireland
    bbc.com bbc.co.uk theguardian.com dailymail.co.uk thesun.co.uk mirror.co.uk
    telegraph.co.uk independent.co.uk express.co.uk metro.co.uk standard.co.uk
    thetimes.co.uk thetimes.com news.sky.com rte.ie irishtimes.com
    # international, English-language
    aljazeera.com dw.com france24.com rt.com cbc.ca ctvnews.ca globalnews.ca
    theglobeandmail.com abc.net.au news.com.au smh.com.au theage.com.au nzherald.co.nz
    stuff.co.nz timesofindia.indiatimes.com ndtv.com hindustantimes.com thehindu.com
    indianexpress.com scmp.com straitstimes.com japantimes.co.jp
    # major European dailies
    spiegel.de bild.de zeit.de faz.net sueddeutsche.de welt.de lemonde.fr lefigaro.fr
    elpais.com elmundo.es corriere.it repubblica.it nos.nl nu.nl telegraaf.nl
"""

BLOCK_SHOPPING = """
    # amazon
    amazon.com amazon.co.uk amazon.de amazon.ca amazon.com.au amazon.in amazon.fr amazon.it
    amazon.es amazon.co.jp amazon.com.br amazon.com.mx amazon.nl amazon.se amazon.pl
    amazon.sg amazon.ae amazon.sa amazon.eg amazon.com.tr amazon.com.be amzn.to
    # ebay
    ebay.com ebay.co.uk ebay.de ebay.ca ebay.com.au ebay.fr ebay.it ebay.es ebay.at ebay.ch
    ebay.nl ebay.ie ebay.pl
    # global marketplaces and fast fashion
    aliexpress.com alibaba.com taobao.com tmall.com jd.com pinduoduo.com dhgate.com
    banggood.com lightinthebox.com temu.com shein.com wish.com etsy.com rakuten.com
    rakuten.co.jp mercadolibre.com mercadolibre.com.ar mercadolibre.com.mx mercadolivre.com.br
    flipkart.com myntra.com ozon.ru wildberries.ru lazada.com shopee.com tokopedia.com noon.com
    # big retailers
    walmart.com target.com bestbuy.com costco.com homedepot.com lowes.com macys.com
    nordstrom.com kohls.com newegg.com microcenter.com bhphotovideo.com zappos.com chewy.com
    wayfair.com ikea.com argos.co.uk currys.co.uk johnlewis.com otto.de mediamarkt.de
    bol.com coolblue.nl cdiscount.com fnac.com allegro.pl
    # fashion
    zalando.com zalando.de zalando.co.uk zalando.fr zalando.it zalando.es zalando.nl
    zalando.pl zalando.se zalando.be zalando.at zalando.ch asos.com hm.com zara.com
    uniqlo.com nike.com adidas.com gap.com
    # second-hand, resale and deals
    craigslist.org offerup.com mercari.com poshmark.com depop.com vinted.com vinted.co.uk
    vinted.fr vinted.de stockx.com goat.com groupon.com slickdeals.net woot.com
"""

BLOCK_GAMES = """
    # browser game portals
    poki.com crazygames.com miniclip.com addictinggames.com kongregate.com armorgames.com
    y8.com coolmathgames.com agame.com friv.com newgrounds.com kizi.com lagged.com
    silvergames.com gamejolt.com pogo.com boardgamearena.com geoguessr.com
    agar.io slither.io krunker.io diep.io skribbl.io
    # chess (deliberately included; override if you want them)
    chess.com lichess.org
    # game stores, launchers and platforms
    roblox.com itch.io store.steampowered.com steamcommunity.com epicgames.com ea.com
    origin.com blizzard.com battle.net gog.com ubisoft.com rockstargames.com bethesda.net
    humblebundle.com greenmangaming.com fanatical.com g2a.com kinguin.net instant-gaming.com
    cdkeys.com playstation.com xbox.com nintendo.com minecraft.net mojang.com
    leagueoflegends.com riotgames.com playvalorant.com fortnite.com pubg.com
    worldoftanks.com wargaming.net warframe.com runescape.com jagex.com
    # gaming news, mods and communities
    ign.com gamespot.com kotaku.com polygon.com pcgamer.com eurogamer.net
    rockpapershotgun.com gamesradar.com destructoid.com nexusmods.com moddb.com
    curseforge.com speedrun.com resetera.com neogaf.com
"""

BLOCK_PROXIES = """
    # Anything here can show a blocked site's pages under its own host name,
    # which address-bar matching cannot see through. On by default in the app.
    # translation proxies
    translate.goog translate.google.com translate.yandex.com
    # caches and archives
    webcache.googleusercontent.com web.archive.org archive.today archive.ph archive.is
    cachedview.nl 12ft.io
    # web proxies
    croxyproxy.com croxyproxy.rocks proxysite.com kproxy.com hide.me hidemyass-freeproxy.com
    # alternative front ends
    nitter.net libreddit.de teddit.net redlib.catsarch.com invidious.io yewtu.be piped.video
    farside.link
"""

ALLOW_ESSENTIALS = """
    # search
    google.com bing.com duckduckgo.com startpage.com ecosia.org brave.com
    # reference, weather and time
    wikipedia.org wikimedia.org wiktionary.org openstreetmap.org weather.com weather.gov
    accuweather.com timeanddate.com
    # translation and dictionaries
    deepl.com dictionary.com thesaurus.com merriam-webster.com
    # email
    gmail.com outlook.com live.com proton.me protonmail.com icloud.com fastmail.com
    # messaging and calls
    whatsapp.com signal.org telegram.org t.me messenger.com
    # maps, transit and rides
    waze.com here.com citymapper.com moovitapp.com uber.com lyft.com
    # health
    nhs.uk mayoclinic.org webmd.com medlineplus.gov cdc.gov who.int
    # government
    usa.gov gov.uk irs.gov ssa.gov canada.ca europa.eu
    # accounts, payments and password managers
    apple.com microsoft.com microsoftonline.com paypal.com 1password.com bitwarden.com lastpass.com
"""

ALLOW_WORK = """
    # office suites and email
    google.com gmail.com microsoft.com microsoftonline.com office.com microsoft365.com
    live.com outlook.com sharepoint.com onedrive.com azure.com apple.com
    # chat and meetings
    slack.com zoom.us zoom.com webex.com loom.com
    # code hosting and developer tools
    github.com githubusercontent.com gitlab.com bitbucket.org stackoverflow.com stackexchange.com
    mozilla.org npmjs.com pypi.org docker.com jetbrains.com postman.com vercel.com netlify.com
    heroku.com digitalocean.com cloudflare.com aws.amazon.com sentry.io datadoghq.com pagerduty.com
    # project and knowledge management
    atlassian.com atlassian.net trello.com asana.com notion.so notion.site linear.app
    monday.com clickup.com basecamp.com airtable.com miro.com evernote.com todoist.com
    # design
    figma.com canva.com adobe.com
    # files
    dropbox.com box.com
    # sales, support and HR
    salesforce.com force.com hubspot.com zendesk.com intercom.com calendly.com docusign.com
    docusign.net workday.com myworkday.com adp.com gusto.com intuit.com xero.com expensify.com
    greenhouse.io lever.co linkedin.com
    # identity, security and infrastructure
    okta.com auth0.com 1password.com bitwarden.com lastpass.com twilio.com stripe.com
    # writing and AI assistants
    grammarly.com deepl.com chatgpt.com openai.com claude.ai anthropic.com
"""

ALLOW_LEARNING = """
    # courses
    khanacademy.org coursera.org edx.org udemy.com udacity.com futurelearn.com skillshare.com
    pluralsight.com masterclass.com brilliant.org codecademy.com freecodecamp.org code.org
    ted.com
    # universities and open courseware
    mit.edu stanford.edu harvard.edu ox.ac.uk cam.ac.uk openstax.org
    # encyclopedias and reference
    wikipedia.org wikimedia.org wiktionary.org britannica.com wolframalpha.com
    merriam-webster.com dictionary.com
    # libraries and archives
    # archive.org is deliberately absent: the Wayback Machine can serve any blocked site.
    openlibrary.org gutenberg.org librivox.org worldcat.org
    # research
    scholar.google.com arxiv.org jstor.org sciencedirect.com nature.com nih.gov doi.org
    researchgate.net academia.edu
    # study tools
    quizlet.com ankiweb.net desmos.com geogebra.org symbolab.com
    # languages
    duolingo.com memrise.com busuu.com babbel.com wordreference.com linguee.com deepl.com dict.cc
    # programming
    stackoverflow.com stackexchange.com github.com python.org kotlinlang.org android.com
    mozilla.org w3schools.com leetcode.com hackerrank.com codewars.com exercism.org
    kaggle.com rust-lang.org go.dev typescriptlang.org react.dev
"""

# (id, kind, name, description, block-of-domains). Order = order in index.tsv.
HAND_CURATED = [
    (
        "distractions",
        "block",
        "Social & entertainment",
        "Social networks, short-video apps, streaming, memes, dating and forum time-sinks.",
        BLOCK_DISTRACTIONS,
    ),
    (
        "news",
        "block",
        "News sites",
        "Mainstream news, tabloids, business and tech news, and news aggregators.",
        BLOCK_NEWS,
    ),
    (
        "shopping",
        "block",
        "Online shopping",
        "Marketplaces, big retailers, fashion, resale and deal sites, including country variants.",
        BLOCK_SHOPPING,
    ),
    (
        "games",
        "block",
        "Gaming sites",
        "Browser game portals, game stores and launchers, and gaming news sites.",
        BLOCK_GAMES,
    ),
    (
        "proxies",
        "block",
        "Proxies & mirrors",
        "Translate, cache and archive proxies plus alternative front ends that show blocked sites under another name.",
        BLOCK_PROXIES,
    ),
]

DERIVED = [
    (
        "adult",
        "block",
        "Adult content",
        "Adult and pornography sites, derived from the StevenBlack hosts porn extension.",
        "porn_hosts.txt",
    ),
    (
        "gambling",
        "block",
        "Gambling sites",
        "Betting, casino and poker sites, derived from the StevenBlack hosts gambling extension.",
        "gambling_hosts.txt",
    ),
]

HAND_CURATED_ALLOW = [
    (
        "essentials",
        "allow",
        "Essentials",
        "Search, maps, reference, email, messaging, health and government; add your own bank.",
        ALLOW_ESSENTIALS,
    ),
    (
        "work",
        "allow",
        "Work tools",
        "Office suites, chat, meetings, code hosting, project tracking and design tools.",
        ALLOW_WORK,
    ),
    (
        "learning",
        "allow",
        "Learning",
        "Courses, encyclopedias, coding references, language learning and digital libraries.",
        ALLOW_LEARNING,
    ),
]


def parse_curated(block: str) -> list[str]:
    domains: list[str] = []
    for line in block.splitlines():
        line = line.split("#", 1)[0].strip()
        if line:
            domains.extend(line.split())
    return domains


def check_curated(preset_id: str, kind: str, domains: list[str], psl: PublicSuffixList) -> list[str]:
    """Validate a hand-curated list against the rules in this file's header and
    return it normalised (sorted, unique)."""
    problems: list[str] = []
    seen: set[str] = set()
    for raw in domains:
        host = normalize_host(raw)
        if host != raw:
            problems.append(f"{raw!r} is not a normalised domain (got {host!r})")
            continue
        if host in seen:
            problems.append(f"{host!r} listed twice")
        seen.add(host)
        reg = psl.registrable(host)
        if host in SUFFIX_ENTRIES:
            # A whole shared platform is a legitimate entry on either side:
            # allowing gov.uk, or blocking the translate.goog proxy platform.
            if reg is not None:
                problems.append(f"{host!r} is listed as a suffix entry but is registrable")
        elif reg is None:
            problems.append(f"{host!r} is a public suffix; blocking/allowing it covers a whole platform")
        elif reg != host and host not in DEEPER_ENTRIES:
            problems.append(f"{host!r} is below its registrable domain {reg!r}; add to DEEPER_ENTRIES if intended")
    # No entry may be redundant with (a subdomain of) another entry in the list.
    for host in seen:
        parts = host.split(".")
        for i in range(1, len(parts) - 1):
            parent = ".".join(parts[i:])
            if parent in seen:
                problems.append(f"{host!r} is redundant: {parent!r} already covers it")
    if problems:
        for p in problems:
            print(f"  {preset_id}: {p}", file=sys.stderr)
        raise SystemExit(f"hand-curated preset {preset_id!r} has {len(problems)} problem(s)")
    return sorted(seen)


# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------


def header(name: str, kind: str, source: str, license_: str, generated: str, extra: list[str]) -> str:
    lines = [
        f"# Distraction Killer Launcher preset: {name} ({kind})",
        f"# Source: {source}",
        f"# License: {license_}",
        f"# Generated: {generated} by tools/build_presets.py",
    ]
    lines.extend("# " + e for e in extra)
    return "\n".join(lines) + "\n\n"


def _without_generated_line(text: str) -> str:
    return "\n".join(l for l in text.splitlines() if not l.startswith("# Generated: "))


def write_if_changed(path: Path, content: str) -> bool:
    """Write content unless the file already has it (ignoring the Generated
    line), so re-running the script on another day leaves git clean."""
    if path.exists():
        old = path.read_text(encoding="utf-8")
        if _without_generated_line(old) == _without_generated_line(content):
            return False
    path.write_text(content, encoding="utf-8", newline="\n")
    return True


def build(refresh: bool, generated: str, include_private: bool) -> list[tuple[str, str, str, str, str, str]]:
    psl_text, psl_date = fetch("public_suffix_list.dat", refresh)
    psl = PublicSuffixList(psl_text, include_private=include_private)
    _self_test_psl(psl)
    print(f"public suffix list: {len(psl.rules)} rules, {len(psl.wildcards)} wildcards, "
          f"{len(psl.exceptions)} exceptions (fetched {psl_date}, "
          f"{'ICANN+PRIVATE' if include_private else 'ICANN section only'})")

    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    rows: list[tuple[str, str, str, str, str, str]] = []
    written = 0

    def emit(preset_id: str, kind: str, name: str, description: str, source: str, license_: str,
             domains: list[str], extra: list[str]) -> None:
        nonlocal written
        content = header(name, kind, source, license_, generated, extra) + "\n".join(domains) + "\n"
        if write_if_changed(ASSETS_DIR / f"{preset_id}.txt", content):
            written += 1
        rows.append((preset_id, kind, name, description, source, license_))

    for preset_id, kind, name, description, block in HAND_CURATED:
        domains = check_curated(preset_id, kind, parse_curated(block), psl)
        emit(preset_id, kind, name, description, HAND_CURATED_SOURCE, HAND_CURATED_LICENSE, domains,
             [f"{len(domains)} domains"])

    for preset_id, kind, name, description, cache_name in DERIVED:
        text, fetched = fetch(cache_name, refresh)
        hosts, rejected = parse_hosts(text)
        domains_set, dropped, st = collapse(hosts, psl)
        domains = sorted(domains_set)
        print(f"{preset_id}: {len(hosts)} hosts after normalising (rejected {rejected} malformed) -> "
              f"{len(domains)} entries: {st['registrable']} registrable domains (eTLD+1) + {st['deeper']} "
              f"subdomains kept under {st['protected_parents']} shared domains upstream does not block; "
              f"dropped {len(dropped)} public-suffix entries"
              + (f" e.g. {', '.join(dropped[:5])}" if dropped else ""))
        source = SOURCES[cache_name]
        emit(preset_id, kind, name, description, source,
             "MIT (StevenBlack/hosts, Copyright Steven Black; extension data by Sinfonietta) - see THIRD_PARTY_NOTICES.md",
             domains,
             [f"Upstream fetched {fetched}; {len(hosts)} hosts -> {st['registrable']} registrable domains (eTLD+1) "
              f"+ {st['deeper']} subdomains of shared sites upstream does not block wholesale"])

    for preset_id, kind, name, description, block in HAND_CURATED_ALLOW:
        domains = check_curated(preset_id, kind, parse_curated(block), psl)
        emit(preset_id, kind, name, description, HAND_CURATED_SOURCE, HAND_CURATED_LICENSE, domains,
             [f"{len(domains)} domains"])

    index = "\t".join(INDEX_HEADER) + "\n" + "".join("\t".join(r) + "\n" for r in rows)
    if write_if_changed(ASSETS_DIR / INDEX_NAME, index):
        written += 1
    print(f"wrote {written} changed file(s) to {ASSETS_DIR.relative_to(ROOT)}")
    return rows


# ---------------------------------------------------------------------------
# Validation of what is on disk (independent of how it was produced)
# ---------------------------------------------------------------------------


def validate() -> int:
    """Re-read the assets with the same rules the Kotlin parser relies on.
    Exits non-zero on any violation. Returns the total byte size."""
    errors: list[str] = []
    index_path = ASSETS_DIR / INDEX_NAME
    if not index_path.exists():
        raise SystemExit(f"missing {index_path}")
    raw = index_path.read_bytes()
    if b"\r" in raw:
        errors.append("index.tsv contains carriage returns")
    lines = raw.decode("utf-8").split("\n")
    if lines[-1] != "":
        errors.append("index.tsv must end with a newline")
    lines = lines[:-1]
    if not lines or lines[0].split("\t") != INDEX_HEADER:
        errors.append(f"index.tsv header must be exactly {chr(9).join(INDEX_HEADER)!r}")
    ids: list[str] = []
    kinds: list[str] = []
    for n, line in enumerate(lines[1:], start=2):
        cols = line.split("\t")
        if len(cols) != len(INDEX_HEADER):
            errors.append(f"index.tsv line {n}: expected {len(INDEX_HEADER)} columns, got {len(cols)}")
            continue
        preset_id, kind, name, description, source, license_ = cols
        if not ID_RE.match(preset_id):
            errors.append(f"index.tsv line {n}: bad id {preset_id!r}")
        if preset_id in ids:
            errors.append(f"index.tsv line {n}: duplicate id {preset_id!r}")
        if kind not in ("block", "allow"):
            errors.append(f"index.tsv line {n}: kind must be block/allow, got {kind!r}")
        if not name or len(name.split()) > 3:
            errors.append(f"index.tsv line {n}: name {name!r} should be 1-3 words")
        if not description or len(description) >= 110:
            errors.append(f"index.tsv line {n}: description must be 1-109 chars, got {len(description)}")
        if not (source.startswith("https://") or source == HAND_CURATED_SOURCE):
            errors.append(f"index.tsv line {n}: unexpected source {source!r}")
        if not license_:
            errors.append(f"index.tsv line {n}: empty license")
        for col in cols:
            if col != col.strip() or not col:
                errors.append(f"index.tsv line {n}: empty or untrimmed column {col!r}")
        if not (ASSETS_DIR / f"{preset_id}.txt").exists():
            errors.append(f"index.tsv line {n}: no file {preset_id}.txt")
        ids.append(preset_id)
        kinds.append(kind)
    if "allow" in kinds and "block" in kinds and kinds.index("allow") < len(kinds) - 1 - kinds[::-1].index("block"):
        errors.append("index.tsv: block presets must come before allow presets")

    for txt in sorted(ASSETS_DIR.glob("*.txt")):
        if txt.stem not in ids:
            errors.append(f"{txt.name} is not listed in index.tsv")
    stray = [p.name for p in ASSETS_DIR.iterdir() if p.suffix not in (".txt", ".tsv")]
    if stray:
        errors.append(f"unexpected files in presets dir: {stray}")

    total = len(raw)
    report: list[tuple[str, str, int, int]] = []
    for preset_id, kind in zip(ids, kinds):
        path = ASSETS_DIR / f"{preset_id}.txt"
        data = path.read_bytes()
        total += len(data)
        if b"\r" in data:
            errors.append(f"{path.name}: contains carriage returns")
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError as e:
            errors.append(f"{path.name}: not UTF-8 ({e})")
            continue
        if not text.endswith("\n"):
            errors.append(f"{path.name}: must end with a newline")
        domains: list[str] = []
        for n, line in enumerate(text.split("\n"), start=1):
            if not line or line.startswith("#"):
                continue
            if not DOMAIN_RE.match(line):
                errors.append(f"{path.name}:{n}: {line!r} does not match the domain regex")
                continue
            if line.startswith("www."):
                errors.append(f"{path.name}:{n}: {line!r} has a leading www.")
            if line.split(".")[-1].isdigit():
                errors.append(f"{path.name}:{n}: {line!r} looks like an IP address")
            domains.append(line)
        if domains != sorted(domains):
            errors.append(f"{path.name}: domains are not sorted")
        if len(domains) != len(set(domains)):
            errors.append(f"{path.name}: contains duplicates")
        if not domains:
            errors.append(f"{path.name}: no domains")
        report.append((preset_id, kind, len(domains), len(data)))

    if total > MAX_TOTAL_BYTES:
        errors.append(f"total preset size {total} bytes exceeds budget of {MAX_TOTAL_BYTES}")

    print()
    print(f"{'id':<14}{'kind':<7}{'domains':>8}{'bytes':>10}")
    for preset_id, kind, count, size in report:
        print(f"{preset_id:<14}{kind:<7}{count:>8}{size:>10}")
    print(f"{'index.tsv':<14}{'':<7}{'':>8}{len(raw):>10}")
    print(f"{'total':<14}{'':<7}{'':>8}{total:>10}  ({total / 1024:.0f} KiB, budget {MAX_TOTAL_BYTES / 1024:.0f} KiB)")

    if errors:
        print()
        for e in errors:
            print(f"ERROR: {e}", file=sys.stderr)
        raise SystemExit(f"validation failed with {len(errors)} error(s)")
    print("validation OK")
    return total


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--refresh", action="store_true", help="re-download sources instead of using tools/.cache")
    ap.add_argument("--check", action="store_true", help="only validate the assets already on disk")
    ap.add_argument("--date", default=None, help="generation date to stamp (YYYY-MM-DD); defaults to today "
                                                 "or SOURCE_DATE_EPOCH")
    ap.add_argument("--icann-only", action="store_true",
                    help="ignore the PRIVATE section of the Public Suffix List when collapsing "
                         "(not recommended: shared platforms like blogspot.com get blocked wholesale)")
    args = ap.parse_args(argv)

    if not args.check:
        if args.date:
            generated = args.date
        elif os.environ.get("SOURCE_DATE_EPOCH"):
            generated = _dt.datetime.fromtimestamp(int(os.environ["SOURCE_DATE_EPOCH"]), tz=_dt.timezone.utc).date().isoformat()
        else:
            generated = _dt.date.today().isoformat()
        build(args.refresh, generated, not args.icann_only)
    validate()


if __name__ == "__main__":
    main()
