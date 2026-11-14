# Local latency TLS fixture

`latency-localhost-cert.pem` and `latency-localhost-key.pem` are a public,
self-signed test pair for isolated loopback latency checks. They contain no
production credentials and must not be used by an actual service.

The test configuration explicitly trusts the certificate. Tests do not install
it in the system certificate store. Node templates retain normal certificate
validation; the sing-box node test therefore accepts the expected TLS failure
after checking that selection and connection reload do not cancel the probe.
