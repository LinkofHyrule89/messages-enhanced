# Generates the Messages Enhanced icon SVGs: a Material-style chat bubble
# (Material Symbols "chat", Apache-2.0, Google) on the app's blue.
P = ("M240-400h320v-80H240v80Zm0-120h480v-80H240v80Zm0-120h480v-80H240v80Z"
     "M80-80v-720q0-33 23.5-56.5T160-880h640q33 0 56.5 23.5T880-800v480"
     "q0 33-23.5 56.5T800-240H240L80-80Z")
BLUE = "#3e6ae1"
NOTE = '<!-- Glyph: Material Symbols "chat" (Apache-2.0, Google). -->\n'

def glyph(size_px, fill="#fff"):
    s = size_px / 800  # glyph spans 800 units, centered on (480,-480)
    return (f'<g transform="translate({256 - 480 * s:.2f} {256 + 480 * s:.2f}) scale({s:.5f})">'
            f'<path fill="{fill}" d="{P}"/></g>')

def svg(body):
    return NOTE + f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">{body}</svg>\n'

out = {
    "icon.svg": svg(f'<rect width="512" height="512" rx="116" fill="{BLUE}"/>' + glyph(300)),
    "icon-small.svg": svg(f'<rect width="512" height="512" rx="96" fill="{BLUE}"/>' + glyph(360)),
    "icon-full.svg": svg(f'<rect width="512" height="512" fill="{BLUE}"/>' + glyph(290)),
    # Maskable: solid square, glyph inside the 80% safe-zone circle.
    "icon-maskable.svg": svg(f'<rect width="512" height="512" fill="{BLUE}"/>' + glyph(276)),
    # Android themed icon / notification badge: white glyph on transparent.
    "icon-monochrome.svg": svg(glyph(276)),
    "badge.svg": svg(glyph(420)),
}
for name, body in out.items():
    open(name, "w").write(body)
