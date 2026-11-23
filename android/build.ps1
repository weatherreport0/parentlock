# Builds parentlock.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$bt = "$sdk\build-tools\36.1.0"
$jar = "$sdk\platforms\android-36\android.jar"
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

$out = Join-Path $here 'build'
$vc = [int](((Get-Date).ToUniversalTime() - [datetime]'2026-01-01').TotalMinutes)
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force "$out\gen", "$out\classes", "$out\dex" | Out-Null

function Check($what) { if ($LASTEXITCODE -ne 0) { throw "$what failed ($LASTEXITCODE)" } }

& "$bt\aapt2.exe" compile --dir "$here\res" -o "$out\res.zip"; Check 'aapt2 compile'
& "$bt\aapt2.exe" link -I $jar --manifest "$here\AndroidManifest.xml" -o "$out\base.apk" `
    --java "$out\gen" --min-sdk-version 30 --target-sdk-version 34 `
    --version-code $vc --version-name "1.0.$vc" "$out\res.zip"; Check 'aapt2 link'

$sources = @(Get-ChildItem -Recurse "$here\src", "$out\gen" -Filter *.java | ForEach-Object FullName)
& javac --release 11 -encoding UTF-8 -classpath $jar -d "$out\classes" @sources; Check 'javac'

$classes = @(Get-ChildItem -Recurse "$out\classes" -Filter *.class | ForEach-Object FullName)
& "$bt\d8.bat" --min-api 30 --lib $jar --output "$out\dex" @classes; Check 'd8'

Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$out\base.apk", 'Update')
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", 'classes.dex') | Out-Null
$zip.Dispose()

& "$bt\zipalign.exe" -p -f 4 "$out\base.apk" "$out\aligned.apk"; Check 'zipalign'

# Signing key. Created on the first build. KEEP parentlock.jks AND BACK IT UP:
# updates (by cable or from the site) only install over an app signed with the same key.
$ks = Join-Path $here 'parentlock.jks'
$ksPass = if ($env:PARENTLOCK_KS_PASS) { $env:PARENTLOCK_KS_PASS } else { 'parentlock' }
if (-not (Test-Path $ks)) {
    & keytool -genkeypair -keystore $ks -alias parentlock -keyalg RSA -keysize 2048 -validity 20000 `
        -storepass $ksPass -keypass $ksPass -dname 'CN=ParentLock' | Out-Null; Check 'keytool'
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass "pass:$ksPass" --key-pass "pass:$ksPass" `
    --out "$here\parentlock.apk" "$out\aligned.apk"; Check 'apksigner'
Write-Host "OK: $here\parentlock.apk"
