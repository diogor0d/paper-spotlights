# Security

## Reporting

Use GitHub's private vulnerability reporting form: **Security → Advisories → Report a vulnerability**. If that form is unavailable, open an issue asking the owner to enable a private reporting channel, but do not include exploit details or sensitive server data in the issue.

## Update trust model

The optional updater accepts only the configured public GitHub repository, the latest non-prerelease semantic version, an exact asset name, and GitHub's SHA-256 asset digest. It also validates the downloaded JAR's plugin identity, release version, and exact Paper API channel. It writes only to Paper's configured update directory and never replaces a running plugin.

This integrity check does not make a malicious release safe. Anyone who can publish a release in the configured repository can publish executable server code. Use 2FA, least-privilege tokens, protected branches/tags, and reviewed release workflows.
