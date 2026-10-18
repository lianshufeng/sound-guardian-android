$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false, $true)
$destination = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../src/main/assets/weather'))
$adminRevision = 'c49d495b40ac73eb1a66f6eeae5f8fd10696f035'
$geoRevision = '0e7cb877cb75f4767a81d51c5921de33aacc8089'
$adminBase = "https://raw.githubusercontent.com/modood/Administrative-divisions-of-China/$adminRevision"
$geoBase = "https://raw.githubusercontent.com/88250/city-geo/$geoRevision"

function Read-Source([string]$address) {
    $response = Invoke-WebRequest -UseBasicParsing -Uri $address -TimeoutSec 30
    if ($response.Content -is [byte[]]) { return $utf8.GetString($response.Content) }
    return [string]$response.Content
}

# 上游为百度地理编码坐标。先由 BD-09 转 GCJ-02，再近似逆变换为天气接口使用的 WGS84。
# 城市中心只用于天气网格选择，不用于导航或精确定位。
function Convert-ToWgs84([double]$latitude, [double]$longitude) {
    $x = $longitude - 0.0065; $y = $latitude - 0.006
    $xPi = [Math]::PI * 3000 / 180
    $z = [Math]::Sqrt($x*$x + $y*$y) - 0.00002 * [Math]::Sin($y * $xPi)
    $theta = [Math]::Atan2($y, $x) - 0.000003 * [Math]::Cos($x * $xPi)
    $lng = $z * [Math]::Cos($theta); $lat = $z * [Math]::Sin($theta)
    $x = $lng - 105; $y = $lat - 35; $pi = [Math]::PI
    $dLat = -100 + 2*$x + 3*$y + 0.2*$y*$y + 0.1*$x*$y + 0.2*[Math]::Sqrt([Math]::Abs($x))
    $dLat += (20*[Math]::Sin(6*$x*$pi) + 20*[Math]::Sin(2*$x*$pi))*2/3
    $dLat += (20*[Math]::Sin($y*$pi) + 40*[Math]::Sin($y/3*$pi))*2/3
    $dLat += (160*[Math]::Sin($y/12*$pi) + 320*[Math]::Sin($y*$pi/30))*2/3
    $dLng = 300 + $x + 2*$y + 0.1*$x*$x + 0.1*$x*$y + 0.1*[Math]::Sqrt([Math]::Abs($x))
    $dLng += (20*[Math]::Sin(6*$x*$pi) + 20*[Math]::Sin(2*$x*$pi))*2/3
    $dLng += (20*[Math]::Sin($x*$pi) + 40*[Math]::Sin($x/3*$pi))*2/3
    $dLng += (150*[Math]::Sin($x/12*$pi) + 300*[Math]::Sin($x/30*$pi))*2/3
    $radLat = $lat/180*$pi; $sinLat = [Math]::Sin($radLat)
    $magic = 1 - 0.00669342162296594323*$sinLat*$sinLat; $sqrtMagic = [Math]::Sqrt($magic)
    $dLat = $dLat*180 / ((6378245*(1-0.00669342162296594323))/($magic*$sqrtMagic)*$pi)
    $dLng = $dLng*180 / (6378245/$sqrtMagic*[Math]::Cos($radLat)*$pi)
    return @([Math]::Round($lat-$dLat, 4), [Math]::Round($lng-$dLng, 4))
}

$provinces = (Read-Source "$adminBase/dist/provinces.json") | ConvertFrom-Json
$cities = (Read-Source "$adminBase/dist/cities.json") | ConvertFrom-Json
$geo = (Read-Source "$geoBase/data.json") | ConvertFrom-Json
$catalog = @()
foreach ($province in $provinces) {
    $rows = @()
    $direct = $province.code -in @('11', '12', '31', '50')
    $entries = @($cities | Where-Object { $_.provinceCode -eq $province.code })
    foreach ($city in $entries) {
        if ($city.name -match '直辖县级|^县$') { continue }
        $cityName = if ($direct) { $province.name.TrimEnd('市') } else { $city.name }
        $matches = @($geo | Where-Object { !$_.area -and $_.province -eq $province.name -and $_.city -eq $city.name })
        if ($matches.Count -ne 1 -or !$matches[0].lat -or !$matches[0].lng) { throw "Missing coordinate: $($province.name)/$cityName" }
        $coordinates = Convert-ToWgs84 ([double]::Parse($matches[0].lat, [Globalization.CultureInfo]::InvariantCulture)) ([double]::Parse($matches[0].lng, [Globalization.CultureInfo]::InvariantCulture))
        $rows += [ordered]@{code=$city.code; name=$cityName; lat=$coordinates[0]; lon=$coordinates[1]}
    }
    if ($rows.Count -eq 0) { throw "Empty province: $($province.name)" }
    $catalog += [ordered]@{code=$province.code; name=$province.name; cities=$rows}
}
$cityCount = ($catalog | ForEach-Object { $_.cities.Count } | Measure-Object -Sum).Sum
if ($catalog.Count -ne 31 -or $cityCount -ne 337) { throw 'Unexpected province/city count' }
[IO.Directory]::CreateDirectory($destination) | Out-Null
$json = [ordered]@{version=1; coordinate_system='WGS84'; provinces=$catalog} | ConvertTo-Json -Depth 8
[IO.File]::WriteAllText((Join-Path $destination 'china_cities.json'), $json + "`n", $utf8)
[IO.File]::WriteAllText((Join-Path $destination 'CITY-GEO-LICENSE.txt'), (Read-Source "$geoBase/LICENSE"), $utf8)
[IO.File]::WriteAllText((Join-Path $destination 'ADMIN-DIVISIONS-LICENSE.txt'), (Read-Source "$adminBase/LICENSE"), $utf8)
$notice = @"
Offline weather city dictionary
Coverage: 31 mainland provincial regions; 333 prefecture divisions and 4 municipalities.
County-level divisions and province-direct county groups are excluded.
Administrative names/codes: https://github.com/modood/Administrative-divisions-of-China/tree/$adminRevision
Coordinates: https://github.com/88250/city-geo/tree/$geoRevision
City Geo copyright (c) 2020-present, b3log.org; Mulan PSL v2.
Original coordinates were converted from BD-09 through GCJ-02 to approximate WGS84, rounded to 4 decimals.
Modified by Anlan: grouped provinces, filtered divisions, renamed municipalities, transformed coordinates.
City centres are weather grid references only. This is a fixed snapshot, not a live administrative registry.
Rebuild with anlan/tools/prepare-cities.ps1. Retain the accompanying upstream licenses.
"@
[IO.File]::WriteAllText((Join-Path $destination 'NOTICE.txt'), $notice + "`n", $utf8)
Write-Output "Prepared $($catalog.Count) provinces and $cityCount cities."
