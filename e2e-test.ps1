# E2E test: register tool -> upload body -> fetch manifest -> download body -> sha256 verify
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:18090'
$work = Join-Path $env:TEMP 'upgrade-e2e'
if (Test-Path $work) { Remove-Item $work -Recurse -Force }
New-Item -ItemType Directory -Path $work | Out-Null

function Post-Json($url, $body, $token) {
    $headers = @{ 'Content-Type' = 'application/json; charset=utf-8' }
    if ($token) { $headers['X-Auth-Token'] = $token }
    return Invoke-RestMethod -Method Post -Uri $url -Headers $headers -Body $body
}

function Get-Json($url, $token) {
    $headers = @{}
    if ($token) { $headers['X-Auth-Token'] = $token }
    return Invoke-RestMethod -Method Get -Uri $url -Headers $headers
}

function Post-Empty($url, $token) {
    $headers = @{ 'Content-Type' = 'application/json; charset=utf-8' }
    if ($token) { $headers['X-Auth-Token'] = $token }
    return Invoke-RestMethod -Method Post -Uri $url -Headers $headers -Body '{}'
}

Write-Host '== 1. Login admin/admin ==' -ForegroundColor Cyan
$login = Post-Json "$base/api/auth/login" '{"username":"admin","password":"admin"}' $null
if (-not $login.success) { throw "Login failed: $($login.message)" }
Write-Host ("token=" + $login.data.token)
Write-Host ("mustChangePwd=" + $login.data.mustChangePwd)
$token = $login.data.token

Write-Host '== 2. Change password admin -> admin123 ==' -ForegroundColor Cyan
$chg = Post-Json "$base/api/auth/change-password" '{"oldPassword":"admin","newPassword":"admin123"}' $token
if (-not $chg.success) { throw "Change password failed: $($chg.message)" }
Write-Host 'Password changed'

Write-Host '== 3. Re-login admin/admin123 ==' -ForegroundColor Cyan
$login2 = Post-Json "$base/api/auth/login" '{"username":"admin","password":"admin123"}' $null
if (-not $login2.success) { throw "Re-login failed: $($login2.message)" }
$token = $login2.data.token
Write-Host ("mustChangePwd=" + $login2.data.mustChangePwd)
if ($login2.data.mustChangePwd) { throw 'Still must change pwd after change' }

Write-Host '== 4. Register tool recorder ==' -ForegroundColor Cyan
$regBody = '{"toolId":"recorder","name":"Recorder","description":"Screen recorder","defaultStartCmd":"java -jar recorder.jar"}'
$reg = Post-Json "$base/api/admin/tools" $regBody $token
if (-not $reg.success) { throw "Register tool failed: $($reg.message)" }
Write-Host ("tool id=" + $reg.data.id + " toolId=" + $reg.data.toolId + " owner=" + $reg.data.ownerUsername)

Write-Host '== 5. Create zip with recorder.jar ==' -ForegroundColor Cyan
$srcDir = Join-Path $work 'src'
New-Item -ItemType Directory -Path $srcDir | Out-Null
$jarPath = Join-Path $srcDir 'recorder.jar'
$jarContent = 'recorder-test-content-v1.0.0'
[System.IO.File]::WriteAllText($jarPath, $jarContent, [System.Text.Encoding]::ASCII)
$bytes = [System.IO.File]::ReadAllBytes($jarPath)
$sha = [System.Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
$expectedSha = [System.BitConverter]::ToString($sha).Replace('-','').ToLower()
$expectedSize = $bytes.Length
Write-Host ("recorder.jar size=" + $expectedSize + " sha256=" + $expectedSha)

$zipPath = Join-Path $work 'recorder-1.0.0-win.zip'
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory($srcDir, $zipPath)
$zipBytes = [System.IO.File]::ReadAllBytes($zipPath)
Write-Host ("zip size=" + $zipBytes.Length)

Write-Host '== 6. Upload tool body win/1.0.0 ==' -ForegroundColor Cyan
$uploadUrl = "$base/api/admin/tools/recorder/versions?version=1.0.0&platform=win&startCommand=java+-jar+recorder.jar&releaseNote=initial+release"
$headers = @{ 'X-Auth-Token' = $token; 'Content-Type' = 'application/zip' }
$uploadResp = Invoke-RestMethod -Method Post -Uri $uploadUrl -Headers $headers -Body $zipBytes
if (-not $uploadResp.success) { throw "Upload tool body failed: $($uploadResp.message)" }
$versionId = $uploadResp.data.id
Write-Host ("version id=" + $versionId + " status=" + $uploadResp.data.status + " fileCount=" + $uploadResp.data.fileCount)

Write-Host '== 7. Publish version ==' -ForegroundColor Cyan
$pub = Post-Empty "$base/api/admin/tools/versions/$versionId/publish" $token
if (-not $pub.success) { throw "Publish failed: $($pub.message)" }
Write-Host ("published status=" + $pub.data.status)

Write-Host '== 8. Fetch KV manifest ==' -ForegroundColor Cyan
$kv = Invoke-WebRequest -Uri "$base/api/bootstrap/recorder?platform=win&format=kv" -UseBasicParsing
$kvText = $kv.Content
Write-Host '--- KV manifest ---'
Write-Host $kvText
Write-Host '-------------------'
if ($kvText -notmatch "FILE_1=recorder\.jar\|$expectedSha\|$expectedSize") {
    throw "KV manifest FILE_1 does not match expected sha256/size"
}
Write-Host 'Manifest sha256 check passed' -ForegroundColor Green

Write-Host '== 9. Access /d/recorder?platform=win verify downloader script ==' -ForegroundColor Cyan
$dl = Invoke-WebRequest -Uri ("$base" + "/d/recorder?platform=win") -UseBasicParsing
$dlText = $dl.Content
Write-Host ("downloader bytes=" + $dlText.Length)
if ($dlText -notmatch 'TOOL_ID=recorder') { throw 'Downloader script missing TOOL_ID=recorder' }
if ($dlText -notmatch 'SERVER_URL=http://127.0.0.1:18090') { throw 'Downloader script missing SERVER_URL' }
Write-Host 'Downloader TOOL_ID/SERVER_URL injection OK' -ForegroundColor Green

Write-Host '== 10. Download body /api/file/tool/recorder/1.0.0/recorder.jar (route order fix) ==' -ForegroundColor Cyan
$jarResp = Invoke-WebRequest -Uri ("$base" + '/api/file/tool/recorder/1.0.0/recorder.jar') -UseBasicParsing
$dlBytes = $jarResp.Content
if ($dlBytes -is [string]) {
    $dlBytes = [System.Text.Encoding]::UTF8.GetBytes($dlBytes)
}
$dlSha = [System.Security.Cryptography.SHA256]::Create().ComputeHash($dlBytes)
$dlShaHex = [System.BitConverter]::ToString($dlSha).Replace('-','').ToLower()
Write-Host ("downloaded bytes=" + $dlBytes.Length + " sha256=" + $dlShaHex)
if ($dlShaHex -ne $expectedSha) { throw "Downloaded body sha256 mismatch: $dlShaHex != $expectedSha" }
Write-Host 'Downloaded body sha256 matches manifest' -ForegroundColor Green

Write-Host '== 11. Query policy status ==' -ForegroundColor Cyan
$pol = Get-Json ("$base" + '/api/policy/status?toolId=recorder&clientId=e2e-test-1') $null
Write-Host ("enabled=" + $pol.enabled + " threshold=" + $pol.threshold + " remaining=" + $pol.remaining + " allowed=" + $pol.allowed)

Write-Host '== 12. Report update record ==' -ForegroundColor Cyan
$recBody = '{"clientId":"e2e-test-1","oldVersion":"0.0.0","newVersion":"1.0.0","result":"SUCCESS","failReason":"","durationMs":1234}'
$rec = Post-Json "$base/api/record/update" $recBody $null
if (-not $rec.success) { throw "Report update record failed: $($rec.message)" }
Write-Host 'Update record reported'

Write-Host '== 13. Admin dashboard ==' -ForegroundColor Cyan
$dash = Get-Json "$base/api/admin/dashboard" $token
Write-Host ("myToolCount=" + $dash.data.myToolCount + " versionCount=" + $dash.data.versionCount + " totalClients=" + $dash.data.totalClients + " todayUpdates=" + $dash.data.todayUpdates)

Write-Host ''
Write-Host '==================== E2E ALL PASSED ====================' -ForegroundColor Green
