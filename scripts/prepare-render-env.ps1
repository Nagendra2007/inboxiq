<#
.SYNOPSIS
  Prepares every environment variable the Render deployment needs and copies
  them to the clipboard, ready for Render's "Add from .env".

.DESCRIPTION
  - Reads your Google OAuth and OpenRouter keys from backend\.env
  - Asks for your Neon connection string (input hidden) and converts it to
    the JDBC settings Spring Boot expects, switching a pooled Neon host to
    the direct one
  - Generates a production TOKEN_ENCRYPTION_KEY once, and reuses it on
    later runs (changing it would lock users out of their stored Gmail tokens)
  - Writes .env.render at the repo root (git-ignored) and copies it to the
    clipboard. No secret is ever printed.

  Run from the repo root:
    powershell -ExecutionPolicy Bypass -File scripts\prepare-render-env.ps1
#>
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$localEnvPath = Join-Path $root 'backend\.env'
$outputPath = Join-Path $root '.env.render'

function Read-EnvFile([string]$path) {
    $values = @{}
    if (Test-Path $path) {
        foreach ($line in [IO.File]::ReadAllLines($path)) {
            if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
                $values[$Matches[1]] = $Matches[2].Trim().Trim('"')
            }
        }
    }
    return $values
}

if (-not (Test-Path $localEnvPath)) {
    throw "backend\.env not found. Copy backend\.env.example to backend\.env and fill it in first."
}
$local = Read-EnvFile $localEnvPath
foreach ($key in 'GOOGLE_CLIENT_ID', 'GOOGLE_CLIENT_SECRET', 'OPENROUTER_API_KEY') {
    if (-not $local[$key]) { throw "$key is empty in backend\.env - fill it in and run this again." }
}
$previous = Read-EnvFile $outputPath

# --- Neon connection string -> JDBC settings -------------------------------
Write-Host ''
Write-Host 'Paste your Neon connection string and press Enter (it stays hidden).' -ForegroundColor Cyan
Write-Host '  It looks like: postgresql://user:password@ep-xxxx.region.aws.neon.tech/neondb?sslmode=require'
$secure = Read-Host 'Neon connection string' -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try { $neon = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr).Trim() }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }

if ($neon -notmatch '^postgres(?:ql)?://([^:/?#]+):([^@]+)@([^/?#]+)/([^?#]+)') {
    throw "That doesn't look like a Postgres connection string (postgresql://user:password@host/database)."
}
$dbUser = [Uri]::UnescapeDataString($Matches[1])
$dbPassword = [Uri]::UnescapeDataString($Matches[2])
$dbHost = $Matches[3]
$dbName = $Matches[4]
if ($dbHost -match '-pooler\.') {
    $dbHost = $dbHost -replace '-pooler\.', '.'
    Write-Host 'Switched the pooled Neon host to the direct one (better for migrations).' -ForegroundColor DarkGray
}

# --- Production encryption key (generated once, then reused) --------------
$tokenKey = $previous['TOKEN_ENCRYPTION_KEY']
if (-not $tokenKey) {
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $tokenKey = [Convert]::ToBase64String($bytes)
    $keyNote = 'generated a new TOKEN_ENCRYPTION_KEY'
} else {
    $keyNote = 'reused the TOKEN_ENCRYPTION_KEY from the previous run'
}

# --- Assemble ---------------------------------------------------------------
# FRONTEND_URL, CORS_ALLOWED_ORIGINS and GOOGLE_REDIRECT_URI are deliberately
# absent: the app derives them from RENDER_EXTERNAL_URL, which Render sets.
$lines = [Collections.Generic.List[string]]::new()
$lines.Add("DATABASE_URL=jdbc:postgresql://$dbHost/${dbName}?sslmode=require")
$lines.Add("DATABASE_USERNAME=$dbUser")
$lines.Add("DATABASE_PASSWORD=$dbPassword")
$lines.Add("GOOGLE_CLIENT_ID=$($local['GOOGLE_CLIENT_ID'])")
$lines.Add("GOOGLE_CLIENT_SECRET=$($local['GOOGLE_CLIENT_SECRET'])")
$lines.Add("OPENROUTER_API_KEY=$($local['OPENROUTER_API_KEY'])")
$lines.Add("AI_MODEL=$(if ($local['AI_MODEL']) { $local['AI_MODEL'] } else { 'openai/gpt-4o-mini' })")
if ($local['AI_FAST_MODEL']) { $lines.Add("AI_FAST_MODEL=$($local['AI_FAST_MODEL'])") }
$lines.Add("TOKEN_ENCRYPTION_KEY=$tokenKey")
$lines.Add('COOKIE_SECURE=true')
$lines.Add('COOKIE_SAME_SITE=Lax')

$content = ($lines -join "`n") + "`n"
[IO.File]::WriteAllText($outputPath, $content, (New-Object Text.UTF8Encoding $false))
Set-Clipboard -Value $content

Write-Host ''
Write-Host "Done: $($lines.Count) variables copied to your clipboard ($keyNote)." -ForegroundColor Green
Write-Host "Also saved to .env.render (git-ignored) - keep it private."
Write-Host ''
Write-Host 'Next, in Render: your web service > Environment > "Add from .env" > paste > Save.'
