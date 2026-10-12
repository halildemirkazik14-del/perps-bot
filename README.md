# Perps Bot

BTC, ETH, SOL, AVAX ve SUI için iki yönlü, hissiz momentum botu (Android).

**Şu an kağıt modda:** gerçek Hyperliquid fiyatları, sanal 1000$ bakiye. Gerçek emir göndermez.

## Kurallar
1. Kâr al ve zarar kes, pozisyon açılmadan önce hesaplanır.
2. Taraf hareketten seçilir: dump = short, pump = long.
3. Boyut mevcut bakiyeden: işlem başı risk bakiyenin %1'i.
4. Kaldıraç coine ve hedefe olan mesafeye göre (BTC en fazla 20x, SUI en fazla 6x).
5. Kaybeden hızlı kesilir (1 ATR stop, 2 saatlik zaman stopu); ortalama düşürme yok.

## Kurulum
Releases bölümündeki en son `PerpsBot.apk` dosyasını telefondan indirip kur.

`python/perps_bot.py` aynı mantığın bilgisayar/sunucu sürümüdür.
