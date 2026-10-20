# Security policy

## Supported versions

VVideo has not had a stable release yet. Security fixes go to the `main` branch.

## Reporting a vulnerability

Please do not open a public issue for a security problem. Use GitHub's private vulnerability reporting ("Report a vulnerability" on the repository's **Security** tab) to reach the maintainer.

Include the affected version or commit, a description of the problem and its impact, and steps to reproduce. Do not include real media, tokens or server addresses.

## Security model

VVideo is meant for a trusted home network:

- Every API request needs the bearer token from `auth.token`. Use a long random value and keep the configuration file private (`chmod 600`).
- The server reads media through descriptor-relative paths without following symlinks and never writes inside a media root.
- The Android app accepts plain HTTP so it can reach a NAS on the LAN. HTTP does not encrypt the token or the media; on any network you do not trust, put the server behind HTTPS.
- Exposing the server directly to the internet is not supported.
