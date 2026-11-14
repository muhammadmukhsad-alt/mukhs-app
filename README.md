# mukhs-app
Chat, voice notes, media and calls with no Firebase / Supabase. Everything is stored on the phone.

## Three ways a message travels
1. **GitHub mailbox (any distance, e.g. Zaria to Abuja)** - the message is sealed (end-to-end encrypted) and put in the
   receiver's box inside a GitHub repository. The receiver's app picks it up when it is open or its background service runs.
   Needs a small internet connection (a few KB per message).
2. **Direct** - Nearby Connections (Bluetooth / Wi-Fi Direct) when two phones are close. No internet.
3. **Carry** - phones carry sealed envelopes for each other and hand them over when they meet.

## Turn the mailbox on (one time)
1. Create a repository, e.g. `mukhs-mailbox` (a **private** repo is best; the app only stores sealed data there).
2. Create a fine-grained token: only that repository, permission **Contents: Read and write**.
3. In this repo: Settings > Secrets and variables > Actions > add `MAILBOX_TOKEN` (the token) and
   `MAILBOX_REPO` (`owner/mukhs-mailbox`).
4. Run the *Build APK* workflow. Without the token the mailbox stays off and the app works as before.

See ROADMAP.md.
