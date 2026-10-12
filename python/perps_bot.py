"""
perps_bot.py — Hissiz, iki yönlü momentum perps botu (Hyperliquid)
Coinler: BTC, ETH, SOL, AVAX, SUI

KURALLAR (kodun anayasası):
 1. Kar-al ve zarar-kes seviyeleri pozisyon AÇILMADAN önce hesaplanır. Hesaplanamazsa işlem yok.
 2. Taraf hareketten seçilir: dump = short, pump = long. Fikir yok, sadece setup.
 3. Boyut, MEVCUT bakiyeden hesaplanır (başlangıç depozitosundan değil).
 4. Kaldıraç coine ve hedefe olan mesafeye göre ayarlanır (coin tavanı ile sınırlı).
 5. Kaybeden hızlı kesilir; aynı coinde açık pozisyon varken ASLA ekleme / ortalama yok.

Varsayılan mod: DRY_RUN (kağıt işlem). Canlı için: DRY_RUN=0 + HL_PRIVATE_KEY + HL_ACCOUNT ortam değişkenleri.
Gereken: pip install requests   (canlı mod için ayrıca: pip install hyperliquid-python-sdk eth-account)
"""

import os, time, json, math, logging
from dataclasses import dataclass, field
import requests

# ───────────────────────── AYARLAR ─────────────────────────
API = "https://api.hyperliquid.xyz"
COINS = ["BTC", "ETH", "SOL", "AVAX", "SUI"]
INTERVAL = "15m"
INTERVAL_MS = 15 * 60 * 1000

# Coin başı profil: kaldıraç tavanı + hareketin "anlamlı" sayılması için gereken ATR katı
COIN_PROFILE = {
    "BTC":  {"max_lev": 20, "impulse_atr": 1.6},
    "ETH":  {"max_lev": 15, "impulse_atr": 1.7},
    "SOL":  {"max_lev": 10, "impulse_atr": 1.8},
    "AVAX": {"max_lev": 8,  "impulse_atr": 1.9},
    "SUI":  {"max_lev": 6,  "impulse_atr": 2.0},
}

ATR_LEN        = 14
VOL_LEN        = 20
VOL_MULT       = 1.3     # hareket mumunun hacmi ortalamanın en az bu katı olmalı
TP_ATR         = 2.0     # kar-al = giriş ± 2.0 ATR
SL_ATR         = 1.0     # zarar-kes = giriş ∓ 1.0 ATR  (kaybedeni hızlı kes)
RISK_PCT       = 0.01    # işlem başı risk = mevcut bakiyenin %1'i
TARGET_ROE     = 0.30    # TP'ye ulaşınca marjin üzerinden hedeflenen getiri (%30)
MAX_MARGIN_PCT = 0.20    # tek pozisyona bağlanacak marjin üst sınırı (bakiyenin %20'si)
MAX_OPEN       = 3       # aynı anda en fazla açık pozisyon
TIME_STOP_BARS = 8       # 8 mum içinde TP/SL olmadıysa kapat (yavaş kaybedeni de kes)
DAILY_LOSS_CAP = 0.04    # gün içi %4 düşüşte o gün yeni işlem yok
FEE_RATE       = 0.00045 # taker ücreti (kağıt modda PnL'e yansıtılır)

DRY_RUN       = os.getenv("DRY_RUN", "1") != "0"
PAPER_BALANCE = float(os.getenv("PAPER_BALANCE", "1000"))
STATE_FILE    = "bot_state.json"

logging.basicConfig(level=logging.INFO, format="%(asctime)s | %(message)s", datefmt="%m-%d %H:%M")
log = logging.getLogger("bot")


# ───────────────────────── VERİ ─────────────────────────
def info(payload):
    r = requests.post(f"{API}/info", json=payload, timeout=10)
    r.raise_for_status()
    return r.json()

def candles(coin, n=60):
    end = int(time.time() * 1000)
    raw = info({"type": "candleSnapshot",
                "req": {"coin": coin, "interval": INTERVAL,
                        "startTime": end - n * INTERVAL_MS, "endTime": end}})
    out = [{"t": c["t"], "o": float(c["o"]), "h": float(c["h"]),
            "l": float(c["l"]), "c": float(c["c"]), "v": float(c["v"])} for c in raw]
    # son mum hâlâ açık olabilir → sadece kapanmış mumlarla çalış
    if out and out[-1]["t"] + INTERVAL_MS > end:
        out = out[:-1]
    return out

def mids():
    return {k: float(v) for k, v in info({"type": "allMids"}).items()}

_meta_cache = {}
def sz_decimals(coin):
    if not _meta_cache:
        for a in info({"type": "meta"})["universe"]:
            _meta_cache[a["name"]] = a
    return _meta_cache[coin]["szDecimals"]

def atr(cs, n=ATR_LEN):
    trs = []
    for i in range(1, len(cs)):
        h, l, pc = cs[i]["h"], cs[i]["l"], cs[i - 1]["c"]
        trs.append(max(h - l, abs(h - pc), abs(l - pc)))
    return sum(trs[-n:]) / n if len(trs) >= n else None


# ───────────────────────── SİNYAL ─────────────────────────
def signal(coin):
    """Kural 2: hareketten taraf seç. Dump → short, pump → long. Yoksa None."""
    cs = candles(coin)
    if len(cs) < max(ATR_LEN, VOL_LEN) + 2:
        return None
    a = atr(cs[:-1])                      # ATR, sinyal mumu hariç → hareket kendini ölçmesin
    last = cs[-1]
    body = last["c"] - last["o"]
    avg_vol = sum(c["v"] for c in cs[-VOL_LEN - 1:-1]) / VOL_LEN
    k = COIN_PROFILE[coin]["impulse_atr"]

    if a is None or a <= 0:
        return None
    if abs(body) < k * a or last["v"] < VOL_MULT * avg_vol:
        return None                       # anlamlı hareket yok → işlem yok

    side = "long" if body > 0 else "short"
    return {"side": side, "atr": a, "bar_t": last["t"], "ref": last["c"]}


# ───────────────────────── PLAN (açılmadan önce) ─────────────────────────
def build_plan(coin, sig, price, equity):
    """Kural 1, 3, 4: TP/SL, boyut ve kaldıraç pozisyon açılmadan kesinleşir."""
    a, long = sig["atr"], sig["side"] == "long"
    tp = price + TP_ATR * a if long else price - TP_ATR * a
    sl = price - SL_ATR * a if long else price + SL_ATR * a
    if tp <= 0 or sl <= 0:
        return None

    sl_dist = abs(price - sl) / price
    tp_dist = abs(tp - price) / price

    # Kural 3: risk mevcut bakiyeden
    risk_usd = equity * RISK_PCT
    notional = risk_usd / sl_dist

    # Kural 4: kaldıraç = hedef ROE / hedefe mesafe, coin tavanında kırpılır
    lev = max(1, min(COIN_PROFILE[coin]["max_lev"], math.floor(TARGET_ROE / tp_dist)))

    # Marjin sınırı aşılıyorsa boyutu küçült (asla riski büyütme)
    margin = notional / lev
    if margin > equity * MAX_MARGIN_PCT:
        notional = equity * MAX_MARGIN_PCT * lev
        margin = notional / lev

    # Güvenlik: tasfiye fiyatı stop'tan önce gelmesin (kaba kontrol: 1/lev > sl_dist + tampon)
    if 1 / lev <= sl_dist * 1.5:
        lev = max(1, math.floor(1 / (sl_dist * 1.5)))
        margin = notional / lev
        if margin > equity * MAX_MARGIN_PCT:
            notional = equity * MAX_MARGIN_PCT * lev
            margin = notional / lev

    dec = sz_decimals(coin)
    size = math.floor(notional / price * 10**dec) / 10**dec
    if size <= 0 or size * price < 10:    # Hyperliquid min emir ~10$
        return None

    rr = tp_dist / sl_dist
    return {"coin": coin, "side": sig["side"], "entry": price, "tp": tp, "sl": sl,
            "size": size, "lev": lev, "margin": round(size * price / lev, 2),
            "risk_usd": round(size * price * sl_dist, 2), "rr": round(rr, 2),
            "opened_bar": sig["bar_t"], "opened_at": time.time()}


# ───────────────────────── BORSA KATMANI ─────────────────────────
class Broker:
    def __init__(self):
        self.ex = self.inf = None
        if not DRY_RUN:
            from eth_account import Account
            from hyperliquid.exchange import Exchange
            from hyperliquid.info import Info
            from hyperliquid.utils import constants
            wallet = Account.from_key(os.environ["HL_PRIVATE_KEY"])
            self.addr = os.environ.get("HL_ACCOUNT", wallet.address)
            self.ex = Exchange(wallet, constants.MAINNET_API_URL, account_address=self.addr)
            self.inf = Info(constants.MAINNET_API_URL, skip_ws=True)

    def equity(self, state):
        if DRY_RUN:
            return state["paper_equity"]
        return float(self.inf.user_state(self.addr)["marginSummary"]["accountValue"])

    def live_positions(self):
        if DRY_RUN:
            return None
        ps = self.inf.user_state(self.addr)["assetPositions"]
        return {p["position"]["coin"] for p in ps if float(p["position"]["szi"]) != 0}

    def open(self, p):
        if DRY_RUN:
            return True
        is_buy = p["side"] == "long"
        self.ex.update_leverage(p["lev"], p["coin"], is_cross=False)
        r = self.ex.market_open(p["coin"], is_buy, p["size"], None, 0.01)
        if r.get("status") != "ok":
            log.error(f"{p['coin']} açılamadı: {r}")
            return False
        # TP ve SL borsaya hemen, reduce-only tetik emri olarak gider
        for kind, px in (("tp", p["tp"]), ("sl", p["sl"])):
            px = float(f"{px:.5g}")
            self.ex.order(p["coin"], not is_buy, p["size"], px,
                          {"trigger": {"triggerPx": px, "isMarket": True, "tpsl": kind}},
                          reduce_only=True)
        return True

    def close(self, coin):
        if DRY_RUN:
            return True
        self.ex.market_close(coin)
        for o in self.inf.open_orders(self.addr):   # kalan TP/SL emirlerini temizle
            if o["coin"] == coin:
                self.ex.cancel(coin, o["oid"])
        return True


# ───────────────────────── DURUM ─────────────────────────
def load_state():
    if os.path.exists(STATE_FILE):
        with open(STATE_FILE) as f:
            return json.load(f)
    return {"paper_equity": PAPER_BALANCE, "positions": {}, "seen_bars": {},
            "day": time.strftime("%Y-%m-%d"), "day_start_equity": PAPER_BALANCE, "trades": []}

def save_state(s):
    with open(STATE_FILE, "w") as f:
        json.dump(s, f, indent=1)

def pnl(p, exit_px):
    d = 1 if p["side"] == "long" else -1
    gross = d * (exit_px - p["entry"]) * p["size"]
    fees = FEE_RATE * p["size"] * (p["entry"] + exit_px)
    return gross - fees


# ───────────────────────── ANA DÖNGÜ ─────────────────────────
def manage_open(state, broker, prices):
    """Açık pozisyonlar: TP / SL / zaman stopu. Ekleme yok."""
    live = broker.live_positions()
    for coin, p in list(state["positions"].items()):
        px = prices[coin]
        long = p["side"] == "long"
        reason = None

        if live is not None and coin not in live:
            reason = "borsada kapandı (TP/SL)"
            px = p["tp"] if (long and px >= p["tp"]) or (not long and px <= p["tp"]) else p["sl"]
        elif (long and px >= p["tp"]) or (not long and px <= p["tp"]):
            reason, px = "TP", p["tp"]
        elif (long and px <= p["sl"]) or (not long and px >= p["sl"]):
            reason, px = "SL", p["sl"]
        elif time.time() - p["opened_at"] > TIME_STOP_BARS * INTERVAL_MS / 1000:
            reason = "zaman stopu"

        if reason:
            if live is None or coin in live:
                broker.close(coin)
            r = pnl(p, px)
            if DRY_RUN:
                state["paper_equity"] += r
            state["trades"].append({"coin": coin, "side": p["side"], "reason": reason,
                                    "pnl": round(r, 2), "t": time.strftime("%m-%d %H:%M")})
            del state["positions"][coin]
            log.info(f"KAPANDI {coin} {p['side']} | {reason} | PnL {r:+.2f}$")

def look_for_entries(state, broker, prices):
    equity = broker.equity(state)
    today = time.strftime("%Y-%m-%d")
    if state["day"] != today:
        state["day"], state["day_start_equity"] = today, equity
    if equity < state["day_start_equity"] * (1 - DAILY_LOSS_CAP):
        log.info("Günlük kayıp sınırı doldu — bugün yeni işlem yok.")
        return

    for coin in COINS:
        if len(state["positions"]) >= MAX_OPEN:
            break
        if coin in state["positions"]:
            continue                      # Kural 5: ortalama yok, ekleme yok
        sig = signal(coin)
        if not sig or state["seen_bars"].get(coin) == sig["bar_t"]:
            continue
        state["seen_bars"][coin] = sig["bar_t"]   # aynı mumla iki kez girme

        plan = build_plan(coin, sig, prices[coin], equity)
        if not plan:
            continue
        log.info(f"PLAN {coin} {plan['side'].upper()} @ {plan['entry']:.4g} | TP {plan['tp']:.4g} "
                 f"SL {plan['sl']:.4g} | {plan['size']} adet x{plan['lev']} | "
                 f"marjin {plan['margin']}$ risk {plan['risk_usd']}$ R:R {plan['rr']}")
        if broker.open(plan):
            state["positions"][coin] = plan

def main():
    log.info(f"Bot başladı | mod: {'KAĞIT' if DRY_RUN else 'CANLI'} | coinler: {', '.join(COINS)}")
    broker, state = Broker(), load_state()
    while True:
        try:
            prices = mids()
            manage_open(state, broker, prices)
            look_for_entries(state, broker, prices)
            save_state(state)
            wins = sum(1 for t in state["trades"] if t["pnl"] > 0)
            log.info(f"Bakiye {broker.equity(state):.2f}$ | açık {list(state['positions'])} | "
                     f"işlem {len(state['trades'])} (kazanan {wins})")
        except Exception as e:
            log.error(f"Hata: {e}")
        time.sleep(30)

if __name__ == "__main__":
    main()
