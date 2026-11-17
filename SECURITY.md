# Security policy

Shelldon SSH handles SSH keys, passwords and remote shells, so security reports are taken seriously.
It is a pre-alpha project run by one maintainer: reports are handled on a best-effort basis, and
there are no bug bounties.

## Supported versions

There is no release yet. Until there is one, only the latest commit on `main` is supported. Once
releases exist, this section will say which ones receive fixes.

## Reporting a vulnerability

**Please do not open a public issue for a security problem.**

Use GitHub's private vulnerability reporting: on the repository page, open the **Security** tab and
choose **Report a vulnerability**. That gives the maintainer a private place to talk with you and to
prepare a fix before the details are public. If that button is not shown, open a normal issue that
says only that you want to report a security problem, without any details, and the maintainer will
arrange a private channel.

Please include:

- what you found and what it lets an attacker do,
- the Shelldon version or commit and the Android version,
- steps to reproduce, or a proof of concept,
- whether you already told anyone else.

You will get an acknowledgement as soon as the maintainer can manage, and an honest answer about
whether it is accepted and when a fix can be expected. Credit in the release notes is given if you
want it.

## What is in scope

- The vault and everything it protects: master password handling, key storage, encrypted backups.
- Host key verification, known hosts, and any way to connect without the checks the app promises.
- Handling of keys, passwords and other secrets in memory, logs, the clipboard and on disk.
- Port forwarding binding to something other than what the user chose.
- Anything that sends data off the device that the user did not ask for. The app has no telemetry
  and should never gain any.

## Out of scope here

- Weaknesses in the SSH protocol or in a server you connect to. Old servers that only offer weak
  algorithms are reachable on purpose, through an explicit, visible per-host choice.
- Problems in third-party libraries with no way to reach them from this app. If you are unsure,
  report it anyway.
- Bugs that need a rooted or already compromised phone.
- Issues in ConnectBot's own code that are not specific to this fork. Those are best reported to
  [ConnectBot](https://github.com/connectbot/connectbot) too, so that every project benefits.

Thank you for helping to keep users safe.
