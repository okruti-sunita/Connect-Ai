param(
    [switch]$ConfigureGoogleOAuth,
    [switch]$LaunchIntelliJ,
    [switch]$CopyKeyForIntelliJ,
    [string]$IntelliJExecutable
)

$ErrorActionPreference = 'Stop'
$keyPath = Join-Path $PSScriptRoot 'config\connectai-encryption-key.dpapi'
$configDirectory = Split-Path -Parent $keyPath
$previousClientId = $env:GOOGLE_OAUTH_CLIENT_ID
$previousClientSecret = $env:GOOGLE_OAUTH_CLIENT_SECRET
$previousEncryptionKey = $env:CONNECTAI_ENCRYPTION_KEY
$clientSecretSecure = $null
$clientSecretPlain = $null
$keySecure = $null

try {
    Write-Host 'Connect AI local backend setup'

    $clientId = $previousClientId
    $clientSecretPlain = $previousClientSecret

    if ($ConfigureGoogleOAuth) {
        Write-Host 'The client secret is hidden while you type and is not saved by this script.'
        if ([string]::IsNullOrWhiteSpace($clientId)) {
            $clientId = (Read-Host 'Google OAuth client ID').Trim()
        }
        if ([string]::IsNullOrWhiteSpace($clientSecretPlain)) {
            $clientSecretSecure = Read-Host 'Google OAuth client secret' -AsSecureString
            $clientSecretPlain = [System.Net.NetworkCredential]::new('', $clientSecretSecure).Password
        }
        if ([string]::IsNullOrWhiteSpace($clientId) -or [string]::IsNullOrWhiteSpace($clientSecretPlain)) {
            throw 'Both the Google OAuth client ID and client secret are required when -ConfigureGoogleOAuth is used.'
        }
    }

    $keyPlain = $previousEncryptionKey
    if ([string]::IsNullOrWhiteSpace($keyPlain)) {
        if (-not (Test-Path -LiteralPath $keyPath)) {
            New-Item -ItemType Directory -Path $configDirectory -Force | Out-Null
            $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
            $bytes = New-Object byte[] 32
            try { $random.GetBytes($bytes) } finally { $random.Dispose() }
            $generatedKey = [Convert]::ToBase64String($bytes)
            $generatedKeySecure = ConvertTo-SecureString -String $generatedKey -AsPlainText -Force
            $protectedKey = ConvertFrom-SecureString -SecureString $generatedKeySecure
            Set-Content -LiteralPath $keyPath -Value $protectedKey -Encoding ASCII -NoNewline
            $generatedKeySecure.Dispose()
            $generatedKey = $null
            [Array]::Clear($bytes, 0, $bytes.Length)
            Write-Host 'Created a per-Windows-user protected local encryption key.'
        }

        try {
            $protectedKey = Get-Content -LiteralPath $keyPath -Raw
            $keySecure = ConvertTo-SecureString -String $protectedKey
        } catch {
            throw 'Could not read the local protected encryption key. Run this script as the same Windows user that created it.'
        }
        $keyPlain = [System.Net.NetworkCredential]::new('', $keySecure).Password
    }

    if ($keyPlain.Length -lt 32) {
        throw 'CONNECTAI_ENCRYPTION_KEY must contain at least 32 characters. Do not replace an existing key if credentials are already stored.'
    }

    if ($ConfigureGoogleOAuth) {
        $env:GOOGLE_OAUTH_CLIENT_ID = $clientId
        $env:GOOGLE_OAUTH_CLIENT_SECRET = $clientSecretPlain
    }
    $env:CONNECTAI_ENCRYPTION_KEY = $keyPlain

    $required = @('CONNECTAI_ENCRYPTION_KEY')
    $missing = @($required | Where-Object { [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_)) })
    if ($missing.Count -gt 0) {
        throw ('Required local settings are missing: ' + ($missing -join ', '))
    }

    if ($CopyKeyForIntelliJ) {
        Set-Clipboard -Value $keyPlain
        Write-Host 'Encryption key copied to clipboard without displaying it. Paste it only into a private IntelliJ run configuration.'
        [void](Read-Host 'After pasting the value into IntelliJ, press Enter here to clear the clipboard')
        Set-Clipboard -Value ' '
        return
    }

    if ($ConfigureGoogleOAuth) {
        Write-Host 'Backend encryption key and Google OAuth settings are present (values hidden). Starting the backend...'
    } elseif (-not [string]::IsNullOrWhiteSpace($previousClientId) -and -not [string]::IsNullOrWhiteSpace($previousClientSecret)) {
        Write-Host 'Backend encryption key and Google OAuth settings are present (values hidden). Starting the backend...'
    } else {
        Write-Host 'Backend encryption key is present (value hidden). Google OAuth is not configured in this session; the backend will still start.'
    }

    if ($LaunchIntelliJ) {
        if (@(Get-Process -Name idea64, idea -ErrorAction SilentlyContinue).Count -gt 0) {
            throw 'Close all IntelliJ IDEA windows first, then rerun this command so IntelliJ inherits the encryption key.'
        }

        if ([string]::IsNullOrWhiteSpace($IntelliJExecutable)) {
            $IntelliJExecutable = (Read-Host 'Full path to IntelliJ idea64.exe').Trim().Trim('"')
        }
        if (-not (Test-Path -LiteralPath $IntelliJExecutable -PathType Leaf)) {
            throw 'IntelliJ executable was not found. Enter the full path to idea64.exe.'
        }

        $projectRoot = Split-Path -Parent $PSScriptRoot
        Write-Host 'Launching IntelliJ with CONNECTAI_ENCRYPTION_KEY set in its process environment (value hidden).'
        Start-Process -FilePath $IntelliJExecutable -ArgumentList "`"$projectRoot`"" -WorkingDirectory $projectRoot -Wait
    } else {
        if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
            throw 'Maven was not found on PATH. Install Maven, open a new PowerShell window, and run this script again.'
        }

        Push-Location $PSScriptRoot
        try {
            & mvn spring-boot:run
            if ($LASTEXITCODE -ne 0) {
                throw "Backend exited with code $LASTEXITCODE. Check the application error above; no credentials were printed by this script."
            }
        } finally {
            Pop-Location
        }
    }
} finally {
    $env:GOOGLE_OAUTH_CLIENT_ID = $previousClientId
    $env:GOOGLE_OAUTH_CLIENT_SECRET = $previousClientSecret
    $env:CONNECTAI_ENCRYPTION_KEY = $previousEncryptionKey
    if ($clientSecretPlain -ne $previousClientSecret) { $clientSecretPlain = $null }
    if ($clientSecretSecure) { $clientSecretSecure.Dispose() }
    if ($keySecure) { $keySecure.Dispose() }
    if ($keyPlain -ne $previousEncryptionKey) { $keyPlain = $null }
}
