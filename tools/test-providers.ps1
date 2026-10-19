$ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

Write-Output "=== A) Internet Archive: audio-only search ==="
try {
  $u = "https://archive.org/advancedsearch.php?q=%22kind+of+blue%22+AND+mediatype%3Aaudio&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=item_size&rows=3&page=1&output=json"
  $r = Invoke-WebRequest $u -UserAgent $ua -TimeoutSec 20 -UseBasicParsing
  $j = $r.Content | ConvertFrom-Json
  Write-Output "numFound=$($j.response.numFound)"
  foreach ($d in $j.response.docs) { Write-Output "  id=$($d.identifier) size=$($d.item_size) title=$($d.title)" }
} catch { Write-Output "ERR $($_.Exception.Message)" }

Write-Output ""
Write-Output "=== B) Nyaa parent audio category c=2_0 ==="
try {
  $r2 = Invoke-WebRequest "https://nyaa.si/?page=rss&q=soundtrack&c=2_0&f=0" -UserAgent $ua -TimeoutSec 20 -UseBasicParsing
  $cnt = ([regex]'<item>').Matches($r2.Content).Count
  $rxCat = [regex]'<nyaa:category>([^<]+)</nyaa:category>'
  $cats = @()
  foreach ($m in $rxCat.Matches($r2.Content)) { $cats += $m.Groups[1].Value }
  $cats = $cats | Select-Object -Unique
  Write-Output "items=$cnt"
  Write-Output ("categories: " + ($cats -join " | "))
} catch { Write-Output "ERR $($_.Exception.Message)" }

Write-Output ""
Write-Output "=== C) apibay: category distribution + empty-result shape ==="
try {
  $r3 = Invoke-WebRequest "https://apibay.org/q.php?q=kind+of+blue" -UserAgent $ua -TimeoutSec 15 -UseBasicParsing
  $j3 = $r3.Content | ConvertFrom-Json
  $groups = $j3 | Group-Object category | Sort-Object Count -Descending
  $parts = @()
  foreach ($g in $groups) { $parts += "$($g.Name) x$($g.Count)" }
  Write-Output ("categories: " + ($parts -join ", "))

  $r4 = Invoke-WebRequest "https://apibay.org/q.php?q=zzqqxyzzy98765" -UserAgent $ua -TimeoutSec 15 -UseBasicParsing
  Write-Output "empty-result body:"
  Write-Output "  $($r4.Content)"
} catch { Write-Output "ERR $($_.Exception.Message)" }
