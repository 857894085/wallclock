import io, zipfile, os, sys
root = sys.argv[1]
apk = os.path.join(root, "output", "WallClock.apk")
z = zipfile.ZipFile(apk)
data = z.read("classes.dex")
want = ["TlsCompat", "TlsAnchors", "AlmanacPage", "WeatherDetailPage", "MonthPage",
        "LifeIndex", "TextDraw", "Almanac", "DashboardView", "SettingsActivity",
        "gen/Layout", "Lcom/vincent/wallclock/TlsCompat;"]
out = []
out.append("classes.dex = %d bytes" % len(data))
ok = True
for w in want:
    hit = w.encode("utf-8") in data
    if not hit:
        ok = False
    out.append("  [%s] %s" % ("OK" if hit else "MISS", w))
# ?????????? dex?? ISRG Root X1 ?? base64 ?? 40 ???
anchor = "MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwA"
hit = anchor[:40].encode("ascii") in data
if not hit:
    ok = False
out.append("  [%s] ISRG Root X1 ?? base64 ? dex ?" % ("OK" if hit else "MISS"))
out.append("??: " + ("????" if ok else "???"))
io.open(os.path.join(root, "tools", "out_dexcheck.txt"), "w", encoding="utf-8", newline="\n").write("\n".join(out) + "\n")
