# Branding

- `source/turtle-original.png`: the turtle as delivered by the owner (432x432, transparent).
- `build_icon.py`: turns it into the launcher layers in `app/src/main/res/`: removes the baked-in
  floor shadow, scales the turtle into the adaptive-icon safe zone, builds the monochrome layer for
  themed icons and the legacy (pre-Android 8) icons.
- `shelldon-icon-512.png`: 512 px store artwork. `icon-preview.png`: how the layers look under a
  circle and a squircle mask, plus the themed (monochrome) variant.

Background colour is `#0C1916` (see `drawable/ic_launcher_background.xml`).

Provenance: the turtle artwork was made by the owner with an AI image tool and handed over for this
project. Whether and how such images are protected by copyright differs between countries and tool
terms. The artwork is not ConnectBot's and is not taken from any existing mascot.

Provenance record (the owner fills this in; `docs/NAME-AND-ICON.md` explains why each line matters):

- Tool and model: _not recorded yet_
- Date generated: _not recorded yet_
- Terms in force that day (URL and saved copy): _not recorded yet_
- Prompt or export kept: _not recorded yet_
- Reverse image search done (date, result): _not done yet_

**Licence: not yet decided.** The owner has to settle this before the first public release (see
`docs/NAME-AND-ICON.md`, `docs/PLAN.md` "Open items" and `docs/FDROID.md`). The recommendation there is
CC0-1.0 "to the extent any rights exist", because it works whether or not the artwork has a copyright
owner and F-Droid accepts it. It depends on the AI tool's terms allowing publication under a free
licence. Until a licence is stated here, do not assume the icon or the files in this folder may be
reused outside this project.

Proposed branding policy (takes effect when the owner accepts it): the name "Shelldon SSH" and the
turtle with the `#` identify this project's own builds. A fork that changes the code should use its own
name and icon, as F-Droid asks of forks too. Unmodified redistribution may keep them. This is a
statement of intent, not a trademark registration, and it grants no right to the ConnectBot name or logo.
