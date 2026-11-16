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
terms, so this is an open question for the owner before any public release (see `docs/PLAN.md`).
The artwork is not ConnectBot's and is not taken from any existing mascot.
`icons/` at the repository root is still ConnectBot's upstream artwork and is not used by the app.
