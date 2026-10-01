# Generates the SVG variants of the Tesla Messages icon (original artwork).
BG = '''<defs>
<linearGradient id="bg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#1a2744"/><stop offset="1" stop-color="#0b1120"/></linearGradient>
<linearGradient id="bub" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#5b86f2"/><stop offset="1" stop-color="#3e6ae1"/></linearGradient>
</defs>'''
# Bubble with a tail (bottom-left), 512 grid.
BUBBLE = '<path fill="url(#bub)" d="M168 112h176a80 80 0 0 1 80 80v112a80 80 0 0 1-80 80H230l-74 58a8 8 0 0 1-13-7l6-53a80 80 0 0 1-61-78V192a80 80 0 0 1 80-80z"/>'
# Generic sedan side profile in white, sitting in the bubble, plus a road line.
CAR = '''<g>
<path fill="#fff" d="M138 300v-22c0-12 8-20 20-23l52-12c24-24 50-36 82-36h8c30 0 58 14 84 40l20 4c13 3 21 12 21 25v24a8 8 0 0 1-8 8H146a8 8 0 0 1-8-8z"/>
<path fill="#3e6ae1" d="M236 244c17-15 36-23 58-23h2v23zM306 221c19 1 36 9 52 23h-52z"/>
<circle cx="206" cy="306" r="25" fill="#0f1830"/><circle cx="206" cy="306" r="10" fill="#c9d6f7"/>
<circle cx="326" cy="306" r="25" fill="#0f1830"/><circle cx="326" cy="306" r="10" fill="#c9d6f7"/>
</g>'''
DOTS = '<g fill="#fff"><circle cx="186" cy="248" r="30"/><circle cx="256" cy="248" r="30"/><circle cx="326" cy="248" r="30"/></g>'

def svg(inner, bg):
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">{BG}{bg}{inner}</svg>\n'

ROUND = '<rect width="512" height="512" rx="112" fill="url(#bg)"/>'
FULL = '<rect width="512" height="512" fill="url(#bg)"/>'
open('icon.svg','w').write(svg(BUBBLE+CAR, ROUND))
open('icon-full.svg','w').write(svg(BUBBLE+CAR, FULL))                  # apple-touch (iOS rounds it)
# Maskable: content scaled into the 80% safe zone (centered), full-bleed bg.
open('icon-maskable.svg','w').write(svg('<g transform="translate(256 256) scale(.78) translate(-256 -270)">'+BUBBLE+CAR+'</g>', FULL))
open('icon-small.svg','w').write(svg('<g transform="translate(256 256) scale(1.12) translate(-256 -262)">'+BUBBLE+DOTS+'</g>', ROUND))  # 16/32 px
# Monochrome badge for Android's status bar (alpha only).
open('badge.svg','w').write('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512"><g transform="translate(256 256) scale(1.15) translate(-256 -262)"><path fill="#fff" d="M168 112h176a80 80 0 0 1 80 80v112a80 80 0 0 1-80 80H230l-74 58a8 8 0 0 1-13-7l6-53a80 80 0 0 1-61-78V192a80 80 0 0 1 80-80z"/></g></svg>\n')
