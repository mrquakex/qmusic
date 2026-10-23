# Creates the GitHub repo for QMusic and pushes this branch to it.
# The token is typed here (hidden), used for these two calls only, and never written to disk or to git config.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$repo = Read-Host 'Repo name (Enter for "qmusic")'
if ([string]::IsNullOrWhiteSpace($repo)) { $repo = 'qmusic' }
$secure = Read-Host 'GitHub token (hidden)' -AsSecureString
$token = [System.Net.NetworkCredential]::new('', $secure).Password

$headers = @{ Authorization = "Bearer $token"; 'User-Agent' = 'qmusic-publish'; Accept = 'application/vnd.github+json' }
$me = (Invoke-RestMethod -Uri 'https://api.github.com/user' -Headers $headers).login
Write-Host "Signed in as $me"

try {
    $body = @{ name = $repo; private = $false; description = 'QMusic - ad-free YouTube Music client' } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri 'https://api.github.com/user/repos' -Headers $headers -Body $body -ContentType 'application/json' | Out-Null
    Write-Host "Created $me/$repo"
} catch {
    Write-Host "Could not create the repo (it may already exist): $($_.Exception.Message)"
}

$url = "https://github.com/$me/$repo.git"
if (git remote | Select-String -Quiet '^origin$') { git remote set-url origin $url } else { git remote add origin $url }

# The token goes only into this one push, as a header; it is not saved anywhere.
$basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("x-access-token:$token"))
git -c "http.extraheader=Authorization: Basic $basic" push -u origin HEAD:main
git branch -u origin/main 2>$null | Out-Null

Write-Host ''
Write-Host "Done: https://github.com/$me/$repo"
Write-Host "Tell Claude: owner=$me repo=$repo"
