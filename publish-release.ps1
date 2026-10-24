# Publishes release v<version> on GitHub and uploads release\qmusic-<version>.apk to it.
# The token is typed here (hidden), used for these calls only, and never written anywhere.
param([string]$Version = '1.0.17')
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$owner = 'mrquakex'; $repo = 'qmusic'
$apk = Join-Path $PSScriptRoot "release\qmusic-$Version.apk"
if (-not (Test-Path $apk)) { throw "APK not found: $apk" }

$secure = Read-Host 'GitHub token (hidden)' -AsSecureString
$token = [System.Net.NetworkCredential]::new('', $secure).Password
$headers = @{ Authorization = "Bearer $token"; 'User-Agent' = 'qmusic-publish'; Accept = 'application/vnd.github+json' }

# Make sure GitHub has the commit the release points at (token used for this one push only).
$basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("x-access-token:$token"))
git -c "http.extraheader=Authorization: Basic $basic" push origin HEAD:main

$body = @{ tag_name = "v$Version"; target_commitish = 'main'; name = "QMusic $Version"; body = "QMusic $Version"; draft = $false; prerelease = $false } | ConvertTo-Json
$release = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$owner/$repo/releases" -Headers $headers -Body $body -ContentType 'application/json'
Write-Host "Release created: $($release.html_url)"

$uploadUrl = "https://uploads.github.com/repos/$owner/$repo/releases/$($release.id)/assets?name=qmusic-$Version.apk"
Invoke-RestMethod -Method Post -Uri $uploadUrl -Headers $headers -InFile $apk -ContentType 'application/vnd.android.package-archive' | Out-Null
Write-Host "APK uploaded. Done: $($release.html_url)"
