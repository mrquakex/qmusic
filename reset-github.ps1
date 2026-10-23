# Replaces the GitHub repo mrquakex/qmusic with a fresh one that has a single commit, then publishes the release.
# DESTRUCTIVE on GitHub: the old repo (its history, tags and releases) is deleted. Your local history is kept in
# the branch "backup-full-history". The token is typed here (hidden), needs the "repo" and "delete_repo" scopes,
# and is never written to disk or to git config.
param([string]$Version = '1.0.4')
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$owner = 'mrquakex'; $repo = 'qmusic'
$apk = Join-Path $PSScriptRoot "release\qmusic-$Version.apk"
if (-not (Test-Path $apk)) { throw "APK not found: $apk" }

Write-Host "This will DELETE https://github.com/$owner/$repo and recreate it with one commit."
$confirm = Read-Host "Type the repo name ($repo) to continue"
if ($confirm -ne $repo) { throw 'Cancelled.' }

$secure = Read-Host 'GitHub token (hidden, scopes: repo + delete_repo)' -AsSecureString
$token = [System.Net.NetworkCredential]::new('', $secure).Password
$headers = @{ Authorization = "Bearer $token"; 'User-Agent' = 'qmusic-publish'; Accept = 'application/vnd.github+json' }

# 1. keep the full history locally, then build the single-commit branch
$current = git branch --show-current
git branch -f backup-full-history HEAD
git checkout --orphan clean-main
git add -A
git commit -q -m "QMusic $Version" -m 'Initial public history of QMusic.'
git remote remove upstream 2>$null

# 2. delete and recreate the GitHub repo
try { Invoke-RestMethod -Method Delete -Uri "https://api.github.com/repos/$owner/$repo" -Headers $headers | Out-Null; Write-Host 'Old repo deleted' } catch { Write-Host "Delete skipped: $($_.Exception.Message)" }
Start-Sleep -Seconds 3
$body = @{ name = $repo; private = $false; description = 'QMusic - ad-free YouTube Music client' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri 'https://api.github.com/user/repos' -Headers $headers -Body $body -ContentType 'application/json' | Out-Null
Write-Host 'New repo created'

# 3. push the single commit
$url = "https://github.com/$owner/$repo.git"
if (git remote | Select-String -Quiet '^origin$') { git remote set-url origin $url } else { git remote add origin $url }
$basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("x-access-token:$token"))
git -c "http.extraheader=Authorization: Basic $basic" push -u origin clean-main:main

# 4. release with the APK
$rel = @{ tag_name = "v$Version"; target_commitish = 'main'; name = "QMusic $Version"; body = "QMusic $Version"; draft = $false; prerelease = $false } | ConvertTo-Json
$release = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$owner/$repo/releases" -Headers $headers -Body $rel -ContentType 'application/json'
$upload = "https://uploads.github.com/repos/$owner/$repo/releases/$($release.id)/assets?name=qmusic-$Version.apk"
Invoke-RestMethod -Method Post -Uri $upload -Headers $headers -InFile $apk -ContentType 'application/vnd.android.package-archive' | Out-Null
Write-Host "Done: $($release.html_url)"
Write-Host "Local branch 'clean-main' is the new history; 'backup-full-history' keeps the old one."
