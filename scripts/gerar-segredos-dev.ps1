#Requires -Version 5.1
<#
    Gera, localmente e fora do repositorio versionado, tudo que compose.yaml precisa e que nao
    pode ser commitado (ADR-0013, emenda "infraestrutura em Docker"):

      - .env a partir de .env.example, com senhas aleatorias no lugar dos placeholders;
      - o par de chaves RSA de assinatura de servidor-autorizacao (PKCS8/X.509, PEM em uma
        linha com \n literais, prontos para colar em .env);
      - o certificado autoassinado de desenvolvimento que o nginx usa em ./certs, para que o
        cookie Secure do AC19 seja exercitado de verdade, nao encenado.

    A parte criptografica roda em Java (keytool + scripts/GerarSegredosDev.java), nao na API do
    .NET: o Windows PowerShell 5.1 roda sobre .NET Framework 4.x, que nao expoe
    RSACng.ExportPkcs8PrivateKey nem CertificateRequest -- Java, ja garantido neste projeto, tem
    API publica e estavel para as duas coisas. Precisa de `java`/`keytool` no PATH (ou JAVA_HOME
    apontando para o JDK 25 usado no restante do projeto).

    Reexecutar e seguro: arquivos ja existentes nao sao sobrescritos, a menos que -Forcar seja
    passado.
#>
param(
    [switch]$Forcar
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot
$envExample = Join-Path $raiz '.env.example'
$envDestino = Join-Path $raiz '.env'
$certsDir = Join-Path $raiz 'certs'
$helperJava = Join-Path $PSScriptRoot 'GerarSegredosDev.java'

function Resolver-ExecutavelJava([string]$Nome) {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\$Nome.exe"))) {
        return Join-Path $env:JAVA_HOME "bin\$Nome.exe"
    }
    $comando = Get-Command $Nome -ErrorAction SilentlyContinue
    if ($comando) {
        return $comando.Source
    }
    throw "$Nome nao encontrado no PATH nem em `$env:JAVA_HOME\bin. Aponte JAVA_HOME para o JDK 25 usado no restante do projeto."
}

$javaExe = Resolver-ExecutavelJava 'java'
$keytoolExe = Resolver-ExecutavelJava 'keytool'

function New-SenhaAleatoria([int]$Tamanho = 32) {
    $bytes = New-Object byte[] $Tamanho
    $rng = [System.Security.Cryptography.RNGCryptoServiceProvider]::new()
    try {
        $rng.GetBytes($bytes)
    } finally {
        $rng.Dispose()
    }
    return [Convert]::ToBase64String($bytes).Replace('+', 'A').Replace('/', 'B').Replace('=', '')
}

# --- .env a partir de .env.example -----------------------------------------------------------
if ((Test-Path $envDestino) -and -not $Forcar) {
    Write-Host ".env ja existe -- mantido como esta (use -Forcar para recriar)." -ForegroundColor Yellow
} else {
    $conteudo = Get-Content -Path $envExample -Raw

    $substituicoes = @{
        'POSTGRES_SUPERUSER_PASSWORD=troque-esta-senha-do-superuser'        = "POSTGRES_SUPERUSER_PASSWORD=$(New-SenhaAleatoria)"
        'CARTEIRA_DB_MIGRATOR_PASSWORD=troque-esta-senha-migrator'          = "CARTEIRA_DB_MIGRATOR_PASSWORD=$(New-SenhaAleatoria)"
        'CARTEIRA_DB_APP_PASSWORD=troque-esta-senha-app'                    = "CARTEIRA_DB_APP_PASSWORD=$(New-SenhaAleatoria)"
        'REDIS_PASSWORD=troque-esta-senha-redis'                            = "REDIS_PASSWORD=$(New-SenhaAleatoria)"
        'AUTH_SERVER_BFF_CLIENT_SECRET=troque-este-client-secret'           = "AUTH_SERVER_BFF_CLIENT_SECRET=$(New-SenhaAleatoria)"
        'AUTH_SERVER_CREDITO_CLIENT_SECRET=troque-este-client-secret-credito' = "AUTH_SERVER_CREDITO_CLIENT_SECRET=$(New-SenhaAleatoria)"
        'gerente.a:troque-senha-a:GERENTE_RELACIONAMENTO;gerente.b:troque-senha-b:GERENTE_RELACIONAMENTO' = "gerente.a:$(New-SenhaAleatoria -Tamanho 12):GERENTE_RELACIONAMENTO;gerente.b:$(New-SenhaAleatoria -Tamanho 12):GERENTE_RELACIONAMENTO"
    }
    foreach ($chave in $substituicoes.Keys) {
        $conteudo = $conteudo.Replace($chave, $substituicoes[$chave])
    }

    $tmpPriv = [System.IO.Path]::GetTempFileName()
    $tmpPub = [System.IO.Path]::GetTempFileName()
    try {
        & $javaExe $helperJava rsa-jwt $tmpPriv $tmpPub
        if ($LASTEXITCODE -ne 0) { throw "Falha ao gerar o par RSA de assinatura (java exit=$LASTEXITCODE)" }

        $privadaUmaLinha = (Get-Content -Path $tmpPriv -Raw) -replace "`r`n", "`n" -replace "`n", '\n'
        $publicaUmaLinha = (Get-Content -Path $tmpPub -Raw) -replace "`r`n", "`n" -replace "`n", '\n'

        $conteudo = $conteudo.Replace('AUTH_SERVER_SIGNING_KEY_PRIVATE=', "AUTH_SERVER_SIGNING_KEY_PRIVATE=$privadaUmaLinha")
        $conteudo = $conteudo.Replace('AUTH_SERVER_SIGNING_KEY_PUBLIC=', "AUTH_SERVER_SIGNING_KEY_PUBLIC=$publicaUmaLinha")
    } finally {
        Remove-Item $tmpPriv, $tmpPub -Force -ErrorAction SilentlyContinue
    }

    # -Encoding utf8 no Windows PowerShell 5.1 sempre grava BOM; o parser de .env do Docker
    # Compose nao ignora um BOM na primeira linha. UTF8Encoding($false) grava sem BOM.
    [System.IO.File]::WriteAllText($envDestino, $conteudo, [System.Text.UTF8Encoding]::new($false))
    Write-Host ".env gerado com senhas e chave de assinatura aleatorias." -ForegroundColor Green
}

# --- Certificado TLS de desenvolvimento -------------------------------------------------------
New-Item -ItemType Directory -Force -Path $certsDir | Out-Null
$caminhoCert = Join-Path $certsDir 'dev-localhost.crt'
$caminhoChave = Join-Path $certsDir 'dev-localhost.key'

if ((Test-Path $caminhoCert) -and (Test-Path $caminhoChave) -and -not $Forcar) {
    Write-Host "certs/dev-localhost.crt ja existe -- mantido como esta (use -Forcar para recriar)." -ForegroundColor Yellow
} else {
    $p12Temp = Join-Path ([System.IO.Path]::GetTempPath()) "dev-localhost-$([Guid]::NewGuid().ToString('N')).p12"
    $senhaP12 = New-SenhaAleatoria -Tamanho 24
    try {
        & $keytoolExe -genkeypair -alias dev-localhost -keyalg RSA -keysize 2048 -validity 730 `
            -keystore $p12Temp -storetype PKCS12 -storepass $senhaP12 `
            -dname 'CN=localhost' -ext 'SAN=dns:localhost,ip:127.0.0.1' -ext 'KeyUsage=digitalSignature,keyEncipherment'
        if ($LASTEXITCODE -ne 0) { throw "keytool falhou ao gerar o certificado (exit=$LASTEXITCODE)" }

        & $javaExe $helperJava export-p12 $p12Temp $senhaP12 dev-localhost $caminhoCert $caminhoChave
        if ($LASTEXITCODE -ne 0) { throw "Falha ao exportar o certificado/chave para PEM (java exit=$LASTEXITCODE)" }

        Write-Host "Certificado autoassinado gerado em certs/ (CN=localhost, valido 2 anos)." -ForegroundColor Green
        Write-Host "O browser vai alertar 'nao confiavel' -- esperado para um certificado de desenvolvimento." -ForegroundColor Yellow
    } finally {
        Remove-Item $p12Temp -Force -ErrorAction SilentlyContinue
    }
}

Write-Host "`nPronto. Revise .env antes de 'docker compose up' -- ele nunca deve ser commitado." -ForegroundColor Cyan
