# Name, trademark and icon licence

Status 2026-11-27. Written by an AI assistant for the owner. **This is research, not legal advice, and
not a trademark clearance.** The official trademark registers (EUIPO TMview, WIPO Global Brand
Database, USPTO, the Slovak ÚPV SR) are interactive databases that could not be queried from the
build environment, so the register search is still to be done by the owner (checklist below, about
30 minutes). Everything else here comes from public web pages read on the date above; re-read the
linked sources before relying on them.

Both points are release blockers (`docs/FDROID.md`, "What F-Droid requires") and the repository has
been public since 2026-11-25.

## 1. Decide before the first release: the name is part of the app id

`io.github.thowt.shelldon` contains the name. Android treats a different application id as a
different app, so after a release a rename means users have to install a new app and lose in-place
updates, and the signing key story starts again on F-Droid and IzzyOnDroid. A rename costs almost
nothing today and a lot after release 0.1.0. That is the reason to do the register search now.

If the name has to change, the places to touch are: `applicationId` (`app/build.gradle.kts`), the
display name (`strings`, fastlane `title.txt`), README, NOTICE, About screen, the icon `#` pun if the
new name does not fit, and the repository name. The code namespace `org.connectbot` stays as it is.

## 2. Name: what was found

| Finding | Meaning |
| --- | --- |
| No Android, F-Droid or iOS app called "Shelldon" turned up in web searches. | Good, but search engines are weak for this; the store searches below still have to be done by hand. |
| Several unrelated developer tools use "shelldon": an Emacs package, a Rust command-line tool, a Ruby gem, a PyPI package, and a reverse-shell generator. | Different goods, so no trademark conflict by itself. The reverse-shell generator is worth knowing about: search results for the name will be mixed with offensive-security tooling, and a security-minded user may associate the name with it. Not a legal problem; a reputation and findability one. |
| A US trademark application for SHELLDON from about 2009 shows as cancelled. | A cancelled mark is not a blocker. Its goods were not established here; the owner should open the record and check them, and check that nobody re-filed. |
| Many SSH apps start with "Shell": ShellDroid, Shellwave, Shelly, Shellby, SiaShell, and more. | "Shell" is the ordinary word for the product category, so rights in a bare "Shell" prefix are weak for software. It also means the name is not very distinctive, which matters for findability. |
| "Shell" is also the famous mark of the oil company Shell. | Its rights are about its own name and pecten logo and mostly sit in other classes. Software names starting with "Shell" are common, so the risk is low, but TMview should be searched for SHELL-prefixed marks in classes 9 and 42 to see who is active there. |
| "Shelldon" sounds like the given name "Sheldon" (a well-known TV character). | A pun, not a copy, and for a software mark unlikely to matter. The mascot is a turtle and must stay clear of any TV or game character. |
| The application id `io.github.thowt.shelldon` is namespaced under the owner's GitHub account. | No collision possible on F-Droid or Play. |
| The ConnectBot name and logo are not used in the UI or store listing; NOTICE and the About screen credit ConnectBot. | This is what Apache-2.0 section 6 expects: the licence grants no right to use the upstream name or marks, and requires attribution to be kept. |

### What the owner has to search (30 minutes)

Search terms: `SHELLDON`, `SHELDON` and `SHELDON SSH` (the sound-alike), `SHELL DON`. Classes: 9 (downloadable
software) and 42 (software as a service; this matters once sync is a hosted service) and, if sync
ever carries user accounts, 38 (messaging and transmission). Territories: EU, Slovakia, US, and
anywhere else you plan to distribute.

1. TMview: <https://www.tmdn.org/tmview/> (covers EUIPO and most EU national offices, including Slovakia).
2. WIPO Global Brand Database: <https://branddb.wipo.int/> (international registrations).
3. USPTO trademark search: <https://tmsearch.uspto.gov/>.
4. Slovak office (ÚPV SR): <https://www.indprop.gov.sk/>.
5. Stores and indexes, same terms: Google Play, Apple App Store, F-Droid, IzzyOnDroid, GitHub.
6. A plain web search for `"shelldon" ssh` and `"shelldon" android`.

### Rule for deciding

Rename before release if any of these is true:

- A live registration or pending application for SHELLDON, or a near-identical or sound-alike mark,
  covers classes 9 or 42 (or 38) in the EU, Slovakia or the US.
- An app with the same or a very similar name exists in the same category on Google Play, the App
  Store or F-Droid.
- The owner of a famous or active SHELL-prefix mark has recently opposed software names of this kind.

If none is true, keep the name, say so in this file with the date and what was searched, and accept
the residual risk: a small non-commercial open-source project is more likely to get a polite request
than a lawsuit, and a rename after a complaint is possible but costly once there are releases.
Registering the mark is optional and not needed for a FOSS client. It becomes worth thinking about when
the hosted sync service opens to the public (class 42).

## 3. Icon: copyright position

The turtle was made by the owner with an AI image tool. Facts that matter:

- **United States.** The Copyright Office's report on copyrightability of AI outputs (January 2025,
  <https://www.copyright.gov/ai/>) concludes that human authorship is required, that a prompt alone does
  not make the user the author of what the tool produces, and that human selection, arrangement and
  modification can be protected. The Supreme Court declined to review *Thaler v. Perlmutter* on
  2 March 2026, so the human-authorship requirement stands.
- **EU and Slovakia.** Copyright needs a work that is the author's own intellectual creation, made by
  a natural person (Slovak Copyright Act 185/2015 Coll.). No Slovak or EU court decision on AI images
  was found in these searches. The likely reading is the same as in the US: the raw tool output may
  have no copyright owner, and human edits can.
- **What the project added by hand.** `branding/build_icon.py` removes the baked-in floor shadow,
  scales the turtle into the adaptive-icon safe zone, builds the monochrome layer and the legacy
  icons, and the dark background colour was chosen by the project. Those are modest edits; whether
  they reach the level of protectable authorship is not something to rely on.
- **The tool's own terms.** These can be stricter than copyright: some tools limit commercial use on
  free tiers, keep a licence for themselves, or require attribution. They apply no matter what
  copyright law says. **The tool is not named anywhere in the repository yet.** Name it, read the terms
  that applied on the day of generation, and keep a copy.
- **Look-alike risk.** Image models can produce something close to an existing character. A green cartoon
  turtle is a crowded subject (children's TV, games, emoji sets). From looking at
  `branding/shelldon-icon-512.png` I see a generic 3D turtle with a cream `#` on its shell and no
  feature specific to a known character. That is an opinion, not a clearance: run the original
  (`branding/source/turtle-original.png`) through a reverse image search (Google Lens, TinEye) before
  release and write down the result.

Net position: the owner probably holds at most thin rights in the icon, and the tool's terms decide
whether it may be published under a free licence at all. A licence statement that works in both cases
is the safe choice.

## 4. Icon: licence options

F-Droid's inclusion policy requires every asset in the repository to be under a valid free licence or
in the public domain, and forks must have their own name and icon. "All rights reserved for the
artwork only" is therefore not an option for F-Droid and is left out.

| Option | What it says | For | Against |
| --- | --- | --- | --- |
| **A. CC0-1.0 "to the extent any rights exist" (recommended)** | The owner waives whatever rights there are, and where waiving is impossible grants a licence with no conditions. | Works whether or not copyright exists. Nothing to attribute, nothing to get wrong. Accepted by F-Droid and Debian. | Anyone may reuse the picture, including as their own app icon. The name and the "official look" are then protected only by the policy in section 5. |
| B. Apache-2.0, same as the app | One licence for the whole repo. | Simplest to explain; attribution via NOTICE. | Assumes rights that may not exist. Apache-2.0 section 6 already keeps the name and marks out of the grant, which helps. |
| C. CC-BY-4.0 | Reuse with credit. | Familiar for artwork. | Attribution to an author who may not be an author in law is awkward. |

Recommendation: **A**, with the generic statement in `branding/README.md`, plus a note in NOTICE. The
Android resource files derived from it (`app/src/main/res/mipmap-*`, `drawable/ic_launcher_*`) are
part of the Apache-2.0 app and need no separate licence line. This depends on the tool's terms
(section 3): if they forbid publishing under a free licence, the icon has to be replaced, and the
replacement can be hand-drawn vector art, which gives clean authorship.

## 5. Branding policy (short; goes with the licence)

A free licence on the picture does not give anyone the right to pass a fork off as the original. To
say that in the repo, a short policy goes into `branding/README.md`:

- The name "Shelldon SSH" and the turtle-with-`#` identify the project's own builds. Forks and
  redistributions that change the code should use their own name and icon, as F-Droid requires of
  forks anyway.
- Unmodified builds, and packaging the unmodified project (distributions, mirrors), may use the name.
- Nothing here is a trademark registration; it is a statement of intent, and it grants no right to
  the ConnectBot name or logo, which the project does not use.

## 6. What the owner has to do

1. Run the register and store searches (section 2) and record the date, terms and result at the end of
   this file.
2. Name the AI tool in `branding/README.md`, keep a copy of its terms and the prompt or export that
   produced the turtle (a dated screenshot is enough), and check that the terms allow publishing under
   the chosen licence.
3. Reverse image search the original turtle; record the result.
4. Pick the icon licence (A, B or C). Until then `branding/README.md` says "not yet decided", and the
   safe reading for everyone is that the files may only be used inside this project.
5. If anything in section 2's rule triggers: rename before release 0.1.0.

Once 1 to 4 are done, the two open rows in `docs/FDROID.md` and the two bullets in `docs/PLAN.md`
"Open items" can be closed.

## Sources

- F-Droid inclusion policy: <https://f-droid.org/docs/Inclusion_Policy/>
- Apache License 2.0, section 6 (Trademarks): <https://www.apache.org/licenses/LICENSE-2.0>
- US Copyright Office, Copyright and Artificial Intelligence: <https://www.copyright.gov/ai/>
- Creative Commons CC0 1.0 legal code: <https://creativecommons.org/publicdomain/zero/1.0/legalcode>
- Slovak Copyright Act 185/2015 Coll.: <https://www.slov-lex.sk/> (search "185/2015")
- Trademark registers: TMview <https://www.tmdn.org/tmview/>, WIPO <https://branddb.wipo.int/>,
  USPTO <https://tmsearch.uspto.gov/>, ÚPV SR <https://www.indprop.gov.sk/>
