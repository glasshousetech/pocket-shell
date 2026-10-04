# Security

Do not publish credentials, SSH keys, terminal transcripts or exploit details in a public
issue. Use the repository's private vulnerability-reporting feature when available, or
arrange a private channel with the repository maintainers before sharing sensitive material.

Pocket Shell is a terminal, not a sandbox for untrusted commands. PRoot does not isolate
programs from other files and credentials accessible to the app. Review commands, SSH host
keys and package sources before trusting them.

Security-sensitive changes need tests for failure paths and legacy data, not only successful
reads/writes. Retain atomic input/paste behavior, secure credential storage, explicit AI
requests and local-only opt-in logging. Never weaken signing checks or restore plaintext
credential storage to work around a device error.

See [privacy behavior](PRIVACY.md) and [remaining release gates](docs/ARCHITECTURE.md).
