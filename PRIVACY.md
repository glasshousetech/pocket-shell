# Pocket Shell privacy

- Terminal sessions and Linux files live in the Android app sandbox. Commands can access
  shared storage only when the user grants that access. Installed Linux programs have their
  own networking and data behavior.
- Voice recognition is supplied by the user's keyboard. Consult that keyboard provider's
  privacy controls; the no-personalized-learning flag is a request, not a guarantee that
  every keyboard processes speech offline.
- Pocket Shell's optional AI copilot sends a prompt and recent terminal context to the
  endpoint chosen in Settings when the user requests help. Inspect terminal output for
  secrets before using it. An API key is not needed for normal terminal use.
- API keys use Android encrypted storage. If it is unavailable, saving fails visibly;
  Pocket Shell does not fall back to plaintext. Legacy plaintext values are migrated only
  after an encrypted write succeeds.
- Optional transcript logging is off by default and local-only. Logs are sampled and may
  include sensitive output. Session history and crash records are also local; their contents
  are not automatically uploaded. Crash records may include exception details.
- Automatic app backup and device-transfer extraction are excluded because the Linux root
  filesystem can contain SSH keys and credentials. In-place app updates preserve local data;
  uninstalling or clearing app storage deletes it. Keep deliberate backups of important work.
- First-run distro downloads, package installation, user-initiated SSH connections and update
  checks contact their respective servers. This is not a claim of offline-only operation.

See [architecture](docs/ARCHITECTURE.md) for the precise data paths.
