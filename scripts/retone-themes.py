#!/usr/bin/env python3
"""Re-derive each theme's elevation ladder so app regions never blend together.

The terminal background, foreground, cursor and ANSI palette are left alone.
What changes is the chrome around it: the four background roles are rebuilt as a
ladder with a guaranteed contrast step between every pair, the border is pushed
clear of all of them, and any text role that the new backgrounds would make
hard to read is nudged until it passes 4.5:1 again.

  dark, near-black terminal   terminal < chrome < surface < control   (chrome raised)
  dark, mid-tone terminal     chrome < surface < terminal < control   (chrome recessed,
                              the way Dracula, Nord, Gruvbox and Solarized ports do it)
  light                       terminal > surface > chrome > control

Themes that already satisfy the ladder are left untouched, so running this twice
changes nothing; pass --force to re-derive every theme. Run it, then
scripts/generate-themes.py, which enforces the same ladder.
"""
import argparse
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'themes/ght-themes.json'

CHROME_STEP = 1.25    # chrome against the terminal
SURFACE_STEP = 1.15   # tabs and cards against chrome
CONTROL_STEP = 1.15   # keys and inputs against the nearest neighbour
LIGHT_SURFACE_STEP = 1.10
BORDER_STEP = 1.30    # border against the most extreme background
RECESS_ROOM = 1.40    # contrast against pure black needed before chrome may go darker
TEXT_MIN = 4.5
TEXT_ROLES = ('text', 'muted', 'accent', 'error', 'success', 'warning', 'info')


def to_linear(hex_color):
    rgb = [int(hex_color[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    return [c / 12.92 if c <= .04045 else ((c + .055) / 1.055) ** 2.4 for c in rgb]


def luminance(hex_color):
    return sum(c * w for c, w in zip(to_linear(hex_color), (.2126, .7152, .0722)))


def contrast(a, b):
    low, high = sorted((luminance(a), luminance(b)))
    return (high + .05) / (low + .05)


def to_oklch(hex_color):
    r, g, b = to_linear(hex_color)
    l = (.4122214708 * r + .5363325363 * g + .0514459929 * b) ** (1 / 3)
    m = (.2119034982 * r + .6806995451 * g + .1073969566 * b) ** (1 / 3)
    s = (.0883024619 * r + .2817188376 * g + .6299787005 * b) ** (1 / 3)
    L = .2104542553 * l + .7936177850 * m - .0040720468 * s
    A = 1.9779984951 * l - 2.4285922050 * m + .4505937099 * s
    B = .0259040371 * l + .7827717662 * m - .8086757660 * s
    return L, math.hypot(A, B), math.atan2(B, A)


def from_oklch(L, C, h):
    """Nearest in-gamut sRGB hex: chroma is reduced until the color fits."""
    for step in range(41):
        c = C * (1 - step / 40)
        A, B = c * math.cos(h), c * math.sin(h)
        l = (L + .3963377774 * A + .2158037573 * B) ** 3
        m = (L - .1055613458 * A - .0638541728 * B) ** 3
        s = (L - .0894841775 * A - 1.2914855480 * B) ** 3
        rgb = (4.0767416621 * l - 3.3077115913 * m + .2309699292 * s,
               -1.2684380046 * l + 2.6097574011 * m - .3413193965 * s,
               -.0041960863 * l - .7034186147 * m + 1.7076147010 * s)
        if all(-1e-4 <= v <= 1 + 1e-4 for v in rgb):
            break
    out = []
    for v in rgb:
        v = min(1, max(0, v))
        v = 12.92 * v if v <= .0031308 else 1.055 * v ** (1 / 2.4) - .055
        out.append(round(min(1, max(0, v)) * 255))
    return '#%02X%02X%02X' % tuple(out)


def stepped(reference, ratio, lighter, tint):
    """The color tinted like [tint] that sits [ratio] away from [reference]."""
    _, C, h = to_oklch(tint)
    lo, hi = (to_oklch(reference)[0], 1.0) if lighter else (0.0, to_oklch(reference)[0])
    for _ in range(40):
        mid = (lo + hi) / 2
        far_enough = contrast(from_oklch(mid, C, h), reference) >= ratio
        if lighter:
            lo, hi = (lo, mid) if far_enough else (mid, hi)
        else:
            lo, hi = (mid, hi) if far_enough else (lo, mid)
    for L in ((hi, lo) if lighter else (lo, hi)):
        candidate = from_oklch(L, C, h)
        if contrast(candidate, reference) >= ratio:
            return candidate
    raise ValueError(f'no color {ratio}:1 from {reference}')


def readable(color, backgrounds, lighter):
    """[color], moved only as far as needed to read on every background."""
    if all(contrast(color, bg) >= TEXT_MIN for bg in backgrounds):
        return color
    L, C, h = to_oklch(color)
    lo, hi = (L, 1.0) if lighter else (0.0, L)
    for _ in range(40):
        mid = (lo + hi) / 2
        ok = all(contrast(from_oklch(mid, C, h), bg) >= TEXT_MIN for bg in backgrounds)
        if lighter:
            lo, hi = (lo, mid) if ok else (mid, hi)
        else:
            lo, hi = (mid, hi) if ok else (lo, mid)
    for candidate_l in ((hi, 1.0) if lighter else (lo, 0.0)):
        candidate = from_oklch(candidate_l, C, h)
        if all(contrast(candidate, bg) >= TEXT_MIN for bg in backgrounds):
            return candidate
    raise ValueError(f'{color} cannot be made readable')


def retone(theme):
    ui, background = theme['ui'], theme['terminal']['background']
    dark = theme['mode'] == 'dark'
    # Dark chrome keeps the theme's own surface tint. Light chrome takes its tint from the paper,
    # because the old light surfaces were near-white and carried no color to keep.
    tint = ui['surface'] if dark else background
    if not dark:
        chrome = stepped(background, CHROME_STEP, False, tint)
        surface = stepped(background, LIGHT_SURFACE_STEP, False, tint)
        control = stepped(chrome, CONTROL_STEP, False, tint)
        elevation = 'light'
    elif contrast(background, '#000000') >= RECESS_ROOM:
        chrome = stepped(background, CHROME_STEP, False, tint)
        surface = stepped(chrome, SURFACE_STEP - .03, True, tint)
        control = stepped(background, CONTROL_STEP, True, tint)
        elevation = 'recessed'
    else:
        chrome = stepped(background, CHROME_STEP, True, tint)
        surface = stepped(chrome, SURFACE_STEP, True, tint)
        control = stepped(surface, CONTROL_STEP, True, tint)
        elevation = 'raised'
    backgrounds = [background, chrome, surface, control]
    extreme = max(backgrounds, key=luminance) if dark else min(backgrounds, key=luminance)
    ui.update(surface=surface, surfaceAlt=chrome, control=control,
              border=stepped(extreme, BORDER_STEP, dark, ui['border']))
    for role in TEXT_ROLES:
        ui[role] = readable(ui[role], backgrounds, dark)
    return elevation


def blends(theme):
    """True when the theme breaks a rule scripts/generate-themes.py enforces."""
    ui, background = theme['ui'], theme['terminal']['background']
    backgrounds = [background, ui['surface'], ui['surfaceAlt'], ui['control']]
    pairs = [(a, b) for i, a in enumerate(backgrounds) for b in backgrounds[i + 1:]]
    return (any(contrast(a, b) < 1.10 for a, b in pairs)
            or contrast(ui['surfaceAlt'], background) < CHROME_STEP
            or contrast(ui['control'], ui['surfaceAlt']) < CONTROL_STEP
            or any(contrast(ui['border'], bg) < BORDER_STEP for bg in backgrounds)
            or any(contrast(ui[role], bg) < TEXT_MIN for role in TEXT_ROLES for bg in backgrounds))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--force', action='store_true', help='Re-derive every theme, not only the ones whose regions blend')
    args = parser.parse_args()
    data = json.loads(SOURCE.read_text())
    for theme in data['themes']:
        if not args.force and not blends(theme):
            print(f"{theme['id']:15} already distinct")
            continue
        print(f"{theme['id']:15} {retone(theme):9} chrome {theme['ui']['surfaceAlt']} surface {theme['ui']['surface']} "
              f"control {theme['ui']['control']} border {theme['ui']['border']} muted {theme['ui']['muted']}")
    SOURCE.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n')
