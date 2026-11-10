# Ephemeral certificate for local 0.6.9 regression fixtures. No OS trust changes.
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$rsa=[Security.Cryptography.RSA]::Create(2048)
try {
    $request=[Security.Cryptography.X509Certificates.CertificateRequest]::new('CN=localhost',$rsa,[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pkcs1)
    $san=[Security.Cryptography.X509Certificates.SubjectAlternativeNameBuilder]::new()
    $san.AddDnsName('localhost')
    $san.AddIpAddress([Net.IPAddress]::Parse('127.0.0.1'))
    $san.AddIpAddress([Net.IPAddress]::Parse('10.0.2.2'))
    $request.CertificateExtensions.Add($san.Build())
    $certificate=$request.CreateSelfSigned([DateTimeOffset]::UtcNow.AddMinutes(-5),[DateTimeOffset]::UtcNow.AddDays(3))
    try {
        [IO.File]::WriteAllText((Join-Path $repo '.tools/regressions-069-cert.pem'),$certificate.ExportCertificatePem())
        [IO.File]::WriteAllText((Join-Path $repo '.tools/regressions-069-key.pem'),$rsa.ExportPkcs8PrivateKeyPem())
    } finally {$certificate.Dispose()}
} finally {$rsa.Dispose()}
